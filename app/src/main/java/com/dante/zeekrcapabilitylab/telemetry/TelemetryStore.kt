package com.dante.zeekrcapabilitylab.telemetry

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

@Serializable data class TelemetryHeader(val version: Int = 1, val sessionId: String, val startedElapsedMs: Long)
data class TelemetryTrack(val header: TelemetryHeader, val points: List<TelemetryFix>)

/** Append-only, bounded, app-private journal. A crash can lose the last line, never rewrite the video. */
class TelemetryStore(private val directory: File) {
    companion object {
        const val MAX_FILE_BYTES = 8L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
        const val MAX_POINTS = 28_800
        const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
        private val id = Regex("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val journalLock = Any()
        private val openPaths = mutableSetOf<String>()
        fun validId(value: String) = id.matches(value)
    }
    private fun file(sessionId: String): File {
        require(validId(sessionId))
        return File(directory, "$sessionId.jsonl").also { require(it.canonicalFile.parentFile == directory.canonicalFile) }
    }

    fun load(sessionId: String): TelemetryTrack? = runCatching {
        val target = file(sessionId)
        if (!target.isFile || target.length() > MAX_FILE_BYTES) return null
        target.bufferedReader().use { reader ->
            val header = json.decodeFromString<TelemetryHeader>(reader.readLine() ?: return null)
            if (header.version != 1 || header.sessionId != sessionId || header.startedElapsedMs < 0) return null
            val points = ArrayList<TelemetryFix>()
            var gap = false
            var lines = 0
            while (true) {
                val line = reader.readLine() ?: break
                if (++lines > MAX_POINTS) break
                val point = runCatching { json.decodeFromString<TelemetryFix>(line) }.getOrNull()
                if (point == null || !TelemetryPolicy.validStored(point) || point.elapsedMs < header.startedElapsedMs ||
                    (points.lastOrNull()?.elapsedMs?.let { point.elapsedMs <= it } == true)) { gap = true; continue }
                points += point.copy(breakBefore = point.breakBefore || gap || points.isEmpty())
                gap = false
            }
            TelemetryTrack(header, points)
        }
    }.getOrNull()

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
                handle.write((json.encodeToString(TelemetryHeader(sessionId = sessionId, startedElapsedMs = startedElapsedMs)) + "\n").toByteArray(Charsets.UTF_8))
            } else {
                // Trim only an incomplete trailing line after a killed process.
                var end = handle.length()
                var committed = 0L
                while (end > 0 && committed == 0L) {
                    val start = (end - 4096).coerceAtLeast(0)
                    val bytes = ByteArray((end - start).toInt())
                    handle.seek(start); handle.readFully(bytes)
                    val newline = bytes.indexOfLast { it == 10.toByte() }
                    if (newline >= 0) committed = start + newline + 1 else end = start
                }
                check(committed > 0) { "INVALID_HISTORY" }; handle.setLength(committed)
            }
            // Read after trimming, so an uncommitted final line cannot advance the append cursor.
            val existing = load(sessionId) ?: error("INVALID_HISTORY")
            // Corrupt/skipped lines still consume the parser's bounded record budget.
            val records = target.useLines { it.drop(1).take(MAX_POINTS + 1).count() }
            check(records <= MAX_POINTS) { "HISTORY_LIMIT" }
            check(existing.header.startedElapsedMs <= startedElapsedMs &&
                (existing.points.lastOrNull()?.elapsedMs ?: 0) <= startedElapsedMs) { "CLOCK_MISMATCH" }
            handle.seek(handle.length())
            openPaths += path
            Writer(handle, records, existing.points.lastOrNull()?.elapsedMs) {
                synchronized(journalLock) { openPaths -= path }
            }
        } catch (e: Exception) { handle.close(); throw e }
    }

    private fun journals() = directory.listFiles().orEmpty().filter {
        it.isFile && it.extension == "jsonl" && validId(it.nameWithoutExtension) && it.canonicalFile.parentFile == directory.canonicalFile
    }
    private fun prune(now: Long, active: File) {
        var total = journals().sumOf { it.length() }
        for (entry in journals().filter { it != active && it.canonicalPath !in openPaths }.sortedBy { it.lastModified() }) {
            if (now - entry.lastModified() <= MAX_AGE_MS && total <= MAX_TOTAL_BYTES - MAX_FILE_BYTES) continue
            val size = entry.length()
            if (entry.delete()) total -= size
        }
        check(total - active.length() <= MAX_TOTAL_BYTES - MAX_FILE_BYTES) { "HISTORY_LIMIT" }
    }
    /** Caller must first stop capture. Only owned journals are removed. */
    fun clear(): Boolean = synchronized(journalLock) {
        val entries = journals()
        if (entries.any { it.canonicalPath in openPaths }) false else entries.map { it.delete() }.all { it }
    }

    class Writer internal constructor(private val handle: RandomAccessFile, private var count: Int, private var lastMs: Long?,
        private val onClose: () -> Unit) : Closeable {
        private var closed = false
        fun append(fix: TelemetryFix) {
            check(!closed && TelemetryPolicy.validStored(fix))
            require(lastMs == null || fix.elapsedMs > lastMs!!)
            val bytes = (json.encodeToString(fix) + "\n").toByteArray(Charsets.UTF_8)
            check(count < MAX_POINTS && handle.length() + bytes.size <= MAX_FILE_BYTES) { "HISTORY_LIMIT" }
            handle.write(bytes); count++; lastMs = fix.elapsedMs
            if (count % 10 == 0) handle.fd.sync()
        }
        override fun close() { if (!closed) { closed = true; try { handle.fd.sync() } finally {
            try { handle.close() } finally { onClose() }
        } } }
    }
}
