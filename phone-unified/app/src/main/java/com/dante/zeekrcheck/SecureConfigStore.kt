package com.dante.zeekrcheck

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.dante.zeekrcheck.core.ProtocolConfig
import com.dante.zeekrcheck.core.ImportedProtocol
import com.dante.zeekrcheck.core.SealedConfig
import com.dante.zeekrcheck.core.SavedSession
import com.dante.zeekrcheck.core.SessionStorage
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Only the explicit importer writes this authenticated provenance record. No default fallback. */
class SecureConfigStore(private val context: Context, private val name: String = "connection-profile") {
    private val store = SecureRecordStore(context, name, SealedConfig.Purpose.IMPORTED_PROTOCOL) { ImportedProtocol.parse(it) }
    fun profile(): ImportedProtocol? = store.load()?.let(ImportedProtocol::parse)
    fun load(): String? = profile()?.text
    fun save(text: String) = save(ImportedProtocol.create(text))
    fun save(profile: ImportedProtocol) = store.save(profile.encode())
    fun clear() = store.clear()
    companion object {
        fun legacyExists(context: Context) = listOf("protocol.sealed", "protocol.sealed.bak", "protocol.sealed.new")
            .any { File(context.noBackupFilesDir, it).exists() }
        fun clearLegacy(context: Context) = SecureRecordStore(context, "protocol", SealedConfig.Purpose.PROTOCOL) { }.clear()
    }
}

class SecureSessionStore(context: Context, name: String = "session") : SessionStorage {
    private val store = SecureRecordStore(context, name, SealedConfig.Purpose.SESSION) { SavedSession.parse(it) }
    override fun load(): SavedSession? = store.load()?.let(SavedSession::parse)
    override fun save(session: SavedSession) = store.save(session.encode())
    override fun clear() = store.clear()
}

/** Separate keys and authenticated purposes prevent protocol/session substitution. */
internal class SecureRecordStore(context: Context, name: String, private val purpose: SealedConfig.Purpose,
    private val validate: (String) -> Unit) {
    init { require(name.matches(Regex("[a-z-]{1,32}"))) }
    private val file = AtomicFile(File(context.noBackupFilesDir, "$name.sealed"))
    private val alias = "com.dante.zeekrcheck.$name.v1"
    private fun keys() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun existingKey(): SecretKey? = keys().getKey(alias, null) as? SecretKey

    fun load(): String? {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        val key = existingKey() ?: error("本机加密记录无法解密。")
        val sealed = file.openRead().use { input ->
            // AtomicFile can restore a previous file. Bound reads after recovery, not just before it.
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                check(out.size() + count <= SealedConfig.MAX_BYTES + 29)
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        }
        val bytes = SealedConfig.open(sealed, key, purpose)
        return try { bytes.toString(Charsets.UTF_8).also(validate) }
        finally { bytes.fill(0) }
    }

    fun save(text: String) {
        validate(text)
        val key = existingKey() ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build())
            generateKey()
        }
        val bytes = text.toByteArray(Charsets.UTF_8)
        val sealed = try { SealedConfig.seal(bytes, key, purpose) } finally { bytes.fill(0) }
        val output = file.startWrite()
        try { output.write(sealed); file.finishWrite(output) }
        catch (e: Exception) { file.failWrite(output); throw e }
        check(load() == text)
    }

    fun clear() {
        keys().deleteEntry(alias)
        file.delete()
        check(!file.baseFile.exists() && existingKey() == null)
    }
}
