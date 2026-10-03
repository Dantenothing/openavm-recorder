package com.dante.zeekrcapabilitylab.telemetry

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

@Serializable
data class VehicleTelemetryHeader(
    val version: Int = 1,
    val sessionId: String,
    val startedElapsedMs: Long,
    val clock: String = "ANDROID_ELAPSED_REALTIME_GETTER_RETURN",
)

data class VehicleTelemetryTrack(val header: VehicleTelemetryHeader, val samples: List<VehicleTelemetrySample>)

/** App-private sidecar; video files and GPS history are never opened by this store. */
class VehicleTelemetryStore(private val directory: File) {
    companion object {
        const val MAX_FILE_BYTES = 8L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
        const val MAX_SAMPLES = 115_200
        const val MAX_LINE_BYTES = 4_096
        const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1_000
        private val id = Regex("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")
        private val json = Json { ignoreUnknownKeys = false; encodeDefaults = false }
        private val journalLock = Any()
        private val openPaths = mutableSetOf<String>()
        fun validId(value: String) = id.matches(value)
    }

    private fun file(sessionId: String): File {
        require(validId(sessionId))
        return File(directory, "$sessionId.jsonl").also { require(it.canonicalFile.parentFile == directory.canonicalFile) }
    }

    private data class Scan(val track: VehicleTelemetryTrack, val records: Int)
    fun load(sessionId: String): VehicleTelemetryTrack? = runCatching { scan(sessionId)?.track }.getOrNull()

    private fun scan(sessionId: String): Scan? {
        val target = file(sessionId)
        val length = target.length()
        if (!target.isFile || length !in 1..MAX_FILE_BYTES) return null
        target.inputStream().buffered().use { input ->
            // Fix the byte budget before reading: a concurrently appended file cannot grow this parse.
            val lines = Lines(input, length)
            val first = lines.next() ?: return null
            if (first.oversize) return null
            val header = runCatching { json.decodeFromString<VehicleTelemetryHeader>(first.text) }.getOrNull() ?: return null
            if (header.version != 1 || header.sessionId != sessionId || header.startedElapsedMs < 0 ||
                header.clock != "ANDROID_ELAPSED_REALTIME_GETTER_RETURN") return null
            val samples = ArrayList<VehicleTelemetrySample>()
            var broken = true
            var count = 0
            while (true) {
                val line = lines.next() ?: break
                if (++count > MAX_SAMPLES) return null
                val sample = if (line.oversize) null else runCatching { json.decodeFromString<VehicleTelemetrySample>(line.text) }.getOrNull()
                if (sample == null || !VehicleTelemetryPolicy.validStored(sample) || sample.elapsedMs < header.startedElapsedMs ||
                    sample.values.any { it.value.readElapsedMs < header.startedElapsedMs } ||
                    samples.lastOrNull()?.let { sample.elapsedMs <= it.elapsedMs } == true) {
                    broken = true
                    continue
                }
                val gap = samples.lastOrNull()?.let { sample.elapsedMs - it.elapsedMs > VehicleTelemetryPolicy.PLAYBACK_AGE_MS } == true
                samples += sample.copy(breakBefore = broken || gap || sample.breakBefore)
                broken = false
            }
            return Scan(VehicleTelemetryTrack(header, samples), count)
        }
    }

    /** A bounded line reader ignores an uncommitted final line, including valid JSON without '\n'. */
    private class Lines(private val input: BufferedInputStream, private var remaining: Long) {
        data class Line(val text: String, val oversize: Boolean)
        private val bytes = ByteArray(MAX_LINE_BYTES)
        fun next(): Line? {
            var count = 0
            var oversize = false
            while (remaining-- > 0) {
                val byte = input.read()
                if (byte < 0) return null
                if (byte == 10) return Line(if (oversize) "" else String(bytes, 0, count, Charsets.UTF_8), oversize)
                if (count < bytes.size) bytes[count++] = byte.toByte() else oversize = true
            }
            return null
        }
    }

    fun open(sessionId: String, startedElapsedMs: Long, nowEpochMs: Long): Writer = synchronized(journalLock) {
        require(startedElapsedMs >= 0)
        check(directory.isDirectory || directory.mkdirs())
        val target = file(sessionId)
        val path = target.canonicalPath
        check(path !in openPaths) { "HISTORY_BUSY" }
        prune(nowEpochMs, target)
        check(!target.exists() || target.length() <= MAX_FILE_BYTES) { "HISTORY_LIMIT" }
        val handle = RandomAccessFile(target, "rw")
        try {
            if (handle.length() == 0L) {
                val header = VehicleTelemetryHeader(sessionId = sessionId, startedElapsedMs = startedElapsedMs)
                handle.write((json.encodeToString(header) + "\n").toByteArray(Charsets.UTF_8))
            } else {
                var end = handle.length()
                var committed = 0L
                while (end > 0 && committed == 0L) {
                    val start = (end - 4_096).coerceAtLeast(0)
                    val bytes = ByteArray((end - start).toInt())
                    handle.seek(start); handle.readFully(bytes)
                    val newline = bytes.indexOfLast { it == 10.toByte() }
                    if (newline >= 0) committed = start + newline + 1 else end = start
                }
                check(committed > 0) { "INVALID_HISTORY" }
                handle.setLength(committed)
            }
            val existing = scan(sessionId) ?: error("INVALID_HISTORY")
            check(existing.track.header.startedElapsedMs <= startedElapsedMs &&
                (existing.track.samples.lastOrNull()?.elapsedMs ?: 0) <= startedElapsedMs) { "CLOCK_MISMATCH" }
            handle.seek(handle.length())
            openPaths += path
            Writer(handle, existing.records, existing.track.samples.lastOrNull()?.elapsedMs, startedElapsedMs) {
                synchronized(journalLock) { openPaths -= path }
            }
        } catch (error: Exception) { handle.close(); throw error }
    }

    private fun journals() = directory.listFiles().orEmpty().filter {
        it.isFile && it.extension == "jsonl" && validId(it.nameWithoutExtension) && it.canonicalFile.parentFile == directory.canonicalFile
    }
    private fun prune(now: Long, active: File) {
        var total = journals().sumOf { it.length() }
        for (entry in journals().filter { it != active && it.canonicalPath !in openPaths }.sortedBy { it.lastModified() }) {
            if (now - entry.lastModified() <= MAX_AGE_MS && total <= MAX_TOTAL_BYTES - MAX_FILE_BYTES) continue
            val length = entry.length()
            if (entry.delete()) total -= length
        }
        check(total - active.length() <= MAX_TOTAL_BYTES - MAX_FILE_BYTES) { "HISTORY_LIMIT" }
    }

    /** Refuse while any owned journal has an active writer. No media or unrelated files are deleted. */
    fun clear(): Boolean = synchronized(journalLock) {
        val entries = journals()
        if (entries.any { it.canonicalPath in openPaths }) false else entries.map { it.delete() }.all { it }
    }

    class Writer internal constructor(
        private val handle: RandomAccessFile,
        private var count: Int,
        private var lastMs: Long?,
        private val startedElapsedMs: Long,
        private val onClose: () -> Unit,
    ) : Closeable {
        private var closed = false
        private var first = true
        @Synchronized fun append(sample: VehicleTelemetrySample) {
            check(!closed)
            require(VehicleTelemetryPolicy.validStored(sample) && sample.elapsedMs >= startedElapsedMs &&
                sample.values.all { it.value.readElapsedMs >= startedElapsedMs })
            require(lastMs == null || sample.elapsedMs > lastMs!!)
            val broken = first || lastMs?.let { sample.elapsedMs - it > VehicleTelemetryPolicy.PLAYBACK_AGE_MS } == true
            val bytes = (json.encodeToString(sample.copy(breakBefore = sample.breakBefore || broken)) + "\n").toByteArray(Charsets.UTF_8)
            check(count < MAX_SAMPLES && bytes.size <= MAX_LINE_BYTES && handle.length() + bytes.size <= MAX_FILE_BYTES) { "HISTORY_LIMIT" }
            handle.write(bytes); count++; lastMs = sample.elapsedMs; first = false
            if (count % 20 == 0) handle.fd.sync()
        }
        @Synchronized override fun close() {
            if (closed) return
            closed = true
            try { handle.fd.sync() } finally { try { handle.close() } finally { onClose() } }
        }
    }
}
