package com.dante.zeekrcapabilitylab.service.recorder

import android.content.Context
import android.util.AtomicFile
import com.dante.zeekrcapabilitylab.diagnostic.RecordingGroupFailureEvidence
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import java.io.FileNotFoundException

/** IO only. AtomicFile recovery reads and writes must share the same lock. */
internal object RecordingChannelJournal {
    const val MAX_BYTES = 32 * 1024
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    data class Saved(val readStatus: String, val channels: List<RecordingChannelHealth> = emptyList())

    fun write(context: Context, channels: List<RecordingChannelHealth>) = synchronized(lock) {
        writeFile(context, "channel-health.json", json.encodeToString(channels.takeLast(9)))
    }

    fun read(context: Context): Saved = synchronized(lock) {
        try {
            Saved("AVAILABLE", json.decodeFromString<List<RecordingChannelHealth>>(
                readFile(context, "channel-health.json")).takeLast(9))
        } catch (_: FileNotFoundException) { Saved("NOT_RECORDED") }
        catch (_: Exception) { Saved("READ_FAILED") }
    }

    fun writeFailure(context: Context, snapshot: JsonObject) = synchronized(lock) {
        require(snapshot["format"]?.jsonPrimitive?.content == RecordingGroupFailureEvidence.FORMAT)
        writeFile(context, "channel-first-failure.json", snapshot.toString())
    }

    fun readFailure(context: Context): JsonObject = synchronized(lock) {
        try {
            val value = Json.parseToJsonElement(readFile(context, "channel-first-failure.json")).jsonObject
            val clean = RecordingGroupFailureEvidence.sanitize(value)
            buildJsonObject { put("readStatus", "AVAILABLE"); put("data", clean) }
        } catch (_: FileNotFoundException) { buildJsonObject { put("readStatus", "NOT_RECORDED") } }
        catch (_: Exception) { buildJsonObject { put("readStatus", "READ_FAILED") } }
    }

    private fun readFile(context: Context, name: String): String =
        AtomicFile(File(context.filesDir, "recordings/$name")).openRead().use { input ->
            require(input.channel.size() in 1..MAX_BYTES.toLong())
            input.bufferedReader().readText()
        }

    private fun writeFile(context: Context, name: String, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..MAX_BYTES)
        val file = AtomicFile(File(context.filesDir, "recordings/$name"))
        file.baseFile.parentFile?.mkdirs()
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (error: Throwable) { file.failWrite(output); throw error }
    }
}
