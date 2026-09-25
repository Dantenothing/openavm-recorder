package com.dante.zeekrcapabilitylab.preflight.cloud

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import com.dante.zeekrcapabilitylab.preflight.obj
import kotlinx.serialization.json.*
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class DiagnosticConnection(val origin: String, val device: String, val token: String, val paired: Boolean)
internal class DiagnosticSettings(context: Context) {
    private val prefs = context.getSharedPreferences("preflight_cloud",Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("openavm_diagnostic_upload_v1",null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("openavm_diagnostic_upload_v1",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun load(): DiagnosticConnection? {
        val origin = prefs.getString("origin",null) ?: return null
        val raw = Base64.decode(prefs.getString("credential",null) ?: error("PAIRING_CREDENTIAL_UNAVAILABLE"),Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,raw.copyOfRange(0,12))) }
        val token = cipher.doFinal(raw.copyOfRange(12,raw.size)).toString(Charsets.UTF_8)
        require(Regex("[0-9a-f]{64}").matches(token))
        return DiagnosticConnection(DiagnosticPolicy.origin(origin),prefs.getString("device",null) ?: error("PAIRING_DEVICE_UNAVAILABLE"),token,prefs.getBoolean("paired",false))
    }
    /** Save before contacting the server: a lost pairing response can safely retry the same identity. */
    fun prepare(origin: String): DiagnosticConnection {
        load()?.let { require(it.origin == origin) { "ALREADY_BOUND_TO_ANOTHER_RECEIVER" }; return it }
        val token = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key()) }
        val credential = Base64.encodeToString(cipher.iv + cipher.doFinal(token.toByteArray()),Base64.NO_WRAP)
        val device = UUID.randomUUID().toString()
        check(prefs.edit().putString("origin",origin).putString("device",device).putString("credential",credential)
            .putBoolean("paired",false).putBoolean("enabled",false).commit()) { "PAIRING_SAVE_FAILED" }
        return DiagnosticConnection(origin,device,token,false)
    }
    fun paired() { check(prefs.edit().putBoolean("paired",true).putBoolean("enabled",true).commit()) }
    fun enabled() = prefs.getBoolean("enabled",false)
    fun enable(value: Boolean) { check(prefs.edit().putBoolean("enabled",value).commit()) }
}

internal interface DiagnosticAtomicFiles {
    fun read(file: File): ByteArray
    fun write(file: File, bytes: ByteArray)
    fun delete(file: File)
}

/** AtomicFile itself does not synchronize readers against a concurrent writer. */
private object AndroidDiagnosticFiles: DiagnosticAtomicFiles {
    @Synchronized override fun read(file: File) = AtomicFile(file).readFully()
    @Synchronized override fun write(file: File, bytes: ByteArray) {
        val atomic=AtomicFile(file); val output=atomic.startWrite()
        try { output.write(bytes); atomic.finishWrite(output) } catch(t:Throwable) { atomic.failWrite(output); throw t }
    }
    @Synchronized override fun delete(file: File) { AtomicFile(file).delete() }
}

internal class DiagnosticOutbox(private val dir: File, private val files: DiagnosticAtomicFiles) {
    constructor(context: Context): this(File(context.filesDir,"diagnostic-outbox"), AndroidDiagnosticFiles)
    init { check(dir.isDirectory || dir.mkdirs()) { "UPLOAD_STORAGE_UNAVAILABLE" } }
    private fun write(file: File, bytes: ByteArray) = files.write(file,bytes)
    fun pending(): List<File> = dir.listFiles().orEmpty().map { it.name.removeSuffix(".bak") }.distinct().filter {
        it.matches(Regex("[0-9a-f-]{36}-[0-9a-f]{64}\\.json"))
    }.map { File(dir,it) }.sortedBy { it.lastModified() }
    fun read(file: File): ByteArray = files.read(file)
    fun receipt(): JsonObject? = runCatching { Json.parseToJsonElement(files.read(File(dir,"receipt.json")).toString(Charsets.UTF_8)).jsonObject }.getOrNull()
    fun enqueue(bytes: ByteArray): DiagnosticIdentity {
        val report=DiagnosticPolicy.identity(bytes); val path=File(dir,DiagnosticPolicy.fileName(report))
        receipt()?.takeIf { it["reportSha256"] == JsonPrimitive(report.hash) }?.let {
            DiagnosticPolicy.verifyReceipt(it,report); return report
        }
        if(path.exists() || File(dir,path.name+".bak").exists()) { check(files.read(path).contentEquals(bytes)); return report }
        check(pending().size < DiagnosticPolicy.MAX_PENDING) { "LOCAL_UPLOAD_QUEUE_FULL" }
        write(path,bytes); return report
    }
    fun acknowledge(file: File, report: DiagnosticIdentity, value: JsonObject) {
        DiagnosticPolicy.verifyReceipt(value,report)
        require(file.parentFile.canonicalFile == dir.canonicalFile && file.name == DiagnosticPolicy.fileName(report))
        write(File(dir,"receipt.json"),value.toString().toByteArray())
        files.delete(file) // Only a verified, uploaded private outbox copy; original diagnostic report remains.
        files.delete(File(dir,file.name+".retry"))
    }
    fun attempts(file: File): Int {
        val state=File(dir,file.name+".retry")
        if(!state.exists() && !File(dir,state.name+".bak").exists()) return 0
        // Corrupt retry bookkeeping must stop automatically, never create an unbounded retry loop.
        return runCatching { files.read(state).toString(Charsets.UTF_8).toInt().takeIf { it>=0 } }
            .getOrNull() ?: DiagnosticPolicy.MAX_ATTEMPTS
    }
    fun attempting(file: File): Int = (attempts(file)+1).also { write(File(dir,file.name+".retry"),it.toString().toByteArray()) }
    fun resetRetries() { pending().forEach { write(File(dir,it.name+".retry"),"0".toByteArray()) } }
    fun status(value: String) { write(File(dir,"status.json"),obj("message" to value).toString().toByteArray()) }
    fun status() = runCatching { Json.parseToJsonElement(files.read(File(dir,"status.json")).toString(Charsets.UTF_8)).jsonObject["message"]!!.jsonPrimitive.content }.getOrDefault("尚未上传")
}
