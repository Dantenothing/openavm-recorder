package com.dante.zeekrcapabilitylab.sentry.runtime

import com.dante.zeekrcapabilitylab.data.ProbeEvent
import com.dante.zeekrcapabilitylab.event.EventLogRotationPolicy
import java.io.File
import java.util.TreeMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class GuardDiagnosticHistorySnapshot(
    val filesRead: Int = 0, val bytesRead: Long = 0, val malformedLines: Int = 0,
    val truncated: Boolean = false, val events: List<ProbeEvent> = emptyList(),
    val readError: String? = null,
)

/** Read-only export of bounded product logs, including records surviving app/vehicle restart. */
internal object GuardDiagnosticHistory {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private data class Key(val epoch: Long, val elapsed: Long, val sequence: Long, val name: String) : Comparable<Key> {
        override fun compareTo(other: Key) = compareValuesBy(this, other, Key::epoch, Key::elapsed, Key::sequence, Key::name)
    }

    fun collect(directory: File, memory: List<ProbeEvent>, nowEpochMs: Long,
                maxReadBytes: Long = 12L * 1024 * 1024): GuardDiagnosticHistorySnapshot {
        require(maxReadBytes > 0)
        val important = TreeMap<Key, ProbeEvent>()
        val routine = TreeMap<Key, ProbeEvent>()
        var truncated = false
        fun offer(value: ProbeEvent) {
            if (value.epochMs < nowEpochMs - 24 * 60 * 60_000L || value.epochMs > nowEpochMs) return
            val name = value.eventName
            if (!(name.startsWith("RECORDER_") || name.startsWith("PRODUCT_MANUAL_PREVIEW_") ||
                name.startsWith("USB_MANUAL_DELETE_") || name == "EVENT_LOG_OVERFLOW_DROPPED")) return
            val keyEvent = value.severity != "INFO" || name in setOf("RECORDER_START", "RECORDER_STOP",
                "RECORDER_SEGMENT_START", "RECORDER_SEGMENT_STOP", "RECORDER_USB_FALLBACK") ||
                name.startsWith("RECORDER_TIME_LAPSE_") || name.startsWith("USB_MANUAL_DELETE_")
            val bucket = if (keyEvent) important else routine
            val limit = if (keyEvent) 80 else 40
            val compact = value.copy(sourcePackage = null, payload = value.payload.entries.take(12)
                .associate { it.key.take(80) to it.value.take(240) }, errorType = value.errorType?.take(160),
                errorMessage = value.errorMessage?.take(400))
            bucket[Key(value.epochMs, value.elapsedRealtimeMs, value.sequence, name)] = compact
            if (bucket.size > limit) { bucket.pollFirstEntry(); truncated = true }
        }
        val files = directory.listFiles().orEmpty().filter { it.isFile &&
            (it.name == EventLogRotationPolicy.ACTIVE_FILE_NAME || it.name == EventLogRotationPolicy.LEGACY_FILE_NAME ||
                EventLogRotationPolicy.isRotatedName(it.name)) }.sortedByDescending { it.lastModified() }
        var remaining = maxReadBytes
        var bytes = 0L
        var read = 0
        var malformed = 0
        val errors = mutableListOf<String>()
        for (file in files.take(8)) {
            if (remaining == 0L) { truncated = true; break }
            val length = file.length()
            val window = minOf(length, remaining)
            val skip = length - window
            if (skip > 0) truncated = true
            remaining -= window; bytes += window; read++
            runCatching {
                file.inputStream().use { input ->
                    input.channel.position(skip)
                    val limited = object : java.io.FilterInputStream(input) {
                        var left = window
                        override fun read(): Int {
                            if (left == 0L) return -1
                            return input.read().also { if (it >= 0) left-- }
                        }
                        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                            if (length == 0) return 0
                            if (left == 0L) return -1
                            return input.read(buffer, offset, minOf(left, length.toLong()).toInt())
                                .also { if (it > 0) left -= it }
                        }
                    }
                    limited.bufferedReader(Charsets.UTF_8).use { reader ->
                        if (skip > 0) reader.readLine() // Discard the partial line at the byte-window boundary.
                        reader.lineSequence().forEach { line ->
                            if (line.isBlank()) return@forEach
                            val event = if (line.length <= 16_000) runCatching {
                                json.decodeFromString(ProbeEvent.serializer(), line)
                            }.getOrNull() else null
                            if (event == null) malformed++ else offer(event)
                        }
                    }
                }
            }.onFailure { errors += "${file.name}:${it.javaClass.simpleName}" }
        }
        if (files.size > 8) truncated = true
        memory.forEach(::offer)
        val chosen = mutableListOf<ProbeEvent>()
        var exportedBytes = 0
        // Key events receive the output budget first; display the final selection chronologically.
        for (value in important.descendingMap().values + routine.descendingMap().values) {
            val size = json.encodeToString(value).toByteArray(Charsets.UTF_8).size
            if (exportedBytes + size > 60 * 1024) { truncated = true; continue }
            chosen += value; exportedBytes += size
        }
        return GuardDiagnosticHistorySnapshot(read, bytes, malformed, truncated,
            chosen.sortedWith(compareBy<ProbeEvent> { it.epochMs }.thenBy { it.elapsedRealtimeMs }.thenBy { it.sequence }),
            errors.take(8).joinToString("; ").takeIf { it.isNotEmpty() })
    }
}
