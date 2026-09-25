package com.dante.zeekrcheck

import android.content.Context
import android.util.AtomicFile
import com.dante.zeekrcheck.core.PresenceLease
import com.dante.zeekrcheck.core.PresenceLeaseStorage
import kotlinx.serialization.json.*
import java.io.File

/** Pending EXIT only; no credentials or raw vehicle identifier and no automatic ENTER on restart. */
class OnlinePresenceStore(context: Context, name: String = "online-presence") : PresenceLeaseStorage {
    init { require(name.matches(Regex("[a-z-]{1,64}"))) }
    private val file = AtomicFile(File(context.noBackupFilesDir, "$name.json"))
    @Synchronized override fun load(): PresenceLease? = runCatching {
        val backup = File(file.baseFile.path + ".bak")
        if (!file.baseFile.exists() && !backup.exists()) return null
        require(maxOf(file.baseFile.length(), backup.length()) <= 1024)
        val root = Json.parseToJsonElement(file.openRead().bufferedReader().use { it.readText() }).jsonObject
        require(root["schema"] == JsonPrimitive(1))
        val identity = root.getValue("identity").jsonPrimitive.content
        val vehicle = root.getValue("vehicleKey").jsonPrimitive.content
        require(identity.matches(Regex("[a-f0-9]{64}")) && vehicle.matches(Regex("[a-f0-9]{64}")))
        PresenceLease(identity, vehicle, root.getValue("started").jsonPrimitive.long.also { require(it > 0) })
    }.getOrNull()
    @Synchronized override fun save(lease: PresenceLease) {
        val bytes = buildJsonObject {
            put("schema", 1); put("identity", lease.identity); put("vehicleKey", lease.vehicleKey); put("started", lease.started)
        }.toString().toByteArray(Charsets.UTF_8)
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }
    @Synchronized override fun clear(lease: PresenceLease) { if (load() == lease) file.delete() }
}
