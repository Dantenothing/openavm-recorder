package com.dante.zeekrcapabilitylab.sentry.runtime

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.util.AtomicFile
import com.dante.zeekrcapabilitylab.ZeekrApp
import com.dante.zeekrcapabilitylab.service.recorder.*
import com.dante.zeekrcapabilitylab.sentry.canary.syncCanaryDirectory
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Contains only this feature's UUID journals and assets. No broad recording cleanup. */
class GuardEventStore(
    context: Context,
    private val reservationBytes: Long = MAX_EVENT_BYTES,
    private val safetyBytes: Long = 256L * 1024 * 1024,
) {
    init { require(reservationBytes in 1..MAX_EVENT_BYTES && safetyBytes >= 0) }
    val root = File(context.filesDir, "sentry/events").apply { mkdirs() }
    fun directory(id: String): File {
        require(id.matches(Regex("[a-f0-9-]{36}")))
        return File(root, id)
    }
    fun save(event: GuardEvent) {
        val bytes = json.encodeToString(event).toByteArray()
        require(bytes.size <= MAX_JOURNAL_BYTES) { "EVENT_METADATA_LIMIT" }
        val dir = directory(event.id).apply { mkdirs() }
        val target = AtomicFile(File(dir, "event.json"))
        val out = target.startWrite()
        try { out.write(bytes); target.finishWrite(out) }
        catch (t: Throwable) { target.failWrite(out); throw t }
        syncCanaryDirectory(dir)
    }
    fun list(): List<GuardEvent> = root.listFiles().orEmpty().filter { it.isDirectory && it.name.matches(Regex("[a-f0-9-]{36}")) }
        .mapNotNull { dir -> runCatching {
            val file = File(dir, "event.json")
            val text = AtomicFile(file).openRead().use { stream ->
                require(stream.channel.size() in 1..MAX_JOURNAL_BYTES.toLong())
                stream.bufferedReader().readText()
            }
            json.decodeFromString<GuardEvent>(text)
                .takeIf { it.id == dir.name && it.assets.size <= 8 && it.triggers.size <= 64 &&
                    it.assets.all { a -> validName(a.name) } && (it.pending == null || validName(it.pending.name)) }
        }.getOrNull() }.sortedByDescending { it.createdAtEpochMs }

    fun files(event: GuardEvent): List<File> = event.assets.map { File(directory(event.id), it.name) }
        .filter { RecorderLibrary.isManaged(it) }

    fun admit() {
        val used = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        check(used + reservationBytes < MAX_DIRECTORY_BYTES && root.usableSpace > reservationBytes + safetyBytes) {
            "SENTRY_STORAGE_FULL_EXPORT_OR_DELETE_EVENTS"
        }
    }

    /** An interrupted journal only recovers explicitly named, finalized, decodable assets. */
    fun recover(): Int {
        var count = 0
        for (event in list().filter { it.state == "WRITING" }) {
            val recovered = (event.assets + listOfNotNull(event.pending)).distinctBy { it.name }.mapNotNull { asset ->
                runCatching {
                    val file = File(directory(event.id), asset.name)
                    check(file.isFile && file.length() <= MAX_EVENT_BYTES)
                    val duration = GuardMediaVerifier.verify(file, asset.samples)
                    val hash = GuardMediaVerifier.hash(file)
                    check(asset.sha256 == null || hash == asset.sha256)
                    val trusted = asset.copy(bytes = file.length(), sha256 = hash, actualDurationMs = duration)
                    sidecar(event, trusted)
                    trusted
                }.getOrNull()
            }
            val updated = event.copy(state = if (recovered.isEmpty()) "FAILED" else "PARTIAL",
                assets = recovered, pending = event.pending?.takeUnless { pending -> recovered.any { it.name == pending.name } },
                reason = "PROCESS_INTERRUPTED_VERIFIED_SEGMENTS_RETAINED")
            save(withCoverage(updated)); count++
        }
        return count
    }

    fun sidecar(event: GuardEvent, asset: GuardAsset) {
        val file = File(directory(event.id), asset.name)
        val startMs = event.createdAtEpochMs + (asset.firstPtsUs - event.triggerPtsUs) / 1000
        val durationMs = asset.actualDurationMs ?: (asset.lastPtsUs - asset.firstPtsUs) / 1000
        SegmentSidecarIO.writeAtomic(file, SegmentSidecar(
            file = file.absolutePath, cameraId = event.source.cameraId, profile = event.source.profile.copy(bitrateBps = 4_000_000),
            sourceRole = event.source.sourceRole, layoutKind = event.source.layoutKind,
            mappingRevision = event.source.mappingRevision, segmentSeconds = 60, segmentNumber = asset.number,
            processStartId = ZeekrApp.processStartId, recordingSessionId = event.id,
            requestedAtEpochMs = startMs, startedAtEpochMs = startMs, stoppedAtEpochMs = startMs + durationMs,
            startedAtElapsedRealtimeMs = asset.firstPtsUs / 1000, stoppedAtElapsedRealtimeMs = asset.lastPtsUs / 1000,
            result = SegmentSidecar.RESULT_SUCCESS, fileBytes = file.length(), protected = true,
            eventId = event.id, eventRequestedAtEpochMs = event.createdAtEpochMs, eventRole = "SENTRY",
            triggerMarkers = GuardVideoPresentation.markers(event, asset),
            laneLayout = event.source.laneLayout, realDurationMs = durationMs,
            actualTrack = ActualTrackInfo(event.source.profile.size.width, event.source.profile.size.height,
                bitrateBps = if (durationMs > 0) file.length() * 8_000 / durationMs else null,
                durationMs = asset.actualDurationMs, frameCount = asset.samples.toLong()), finalizeReason = "SENTRY_EVENT",
        ))
        RandomAccessFile(SegmentSidecarIO.sidecarFileFor(file), "rw").use { it.fd.sync() }
        syncCanaryDirectory(directory(event.id))
    }

    companion object {
        val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }
        const val MAX_EVENT_BYTES = 800L * 1024 * 1024
        const val MAX_JOURNAL_BYTES = 2 * 1024 * 1024
        const val MAX_DIRECTORY_BYTES = 10L * 1024 * 1024 * 1024
        private fun validName(value: String) = GuardVideoPresentation.validAssetName(value)
        fun withCoverage(event: GuardEvent): GuardEvent {
            val first = event.assets.firstOrNull()?.firstPtsUs ?: event.triggerPtsUs
            val last = event.assets.lastOrNull()?.lastPtsUs ?: event.triggerPtsUs
            return event.copy(preAchievedUs = (event.triggerPtsUs - first).coerceAtLeast(0),
                postAchievedUs = (last - event.triggerPtsUs).coerceAtLeast(0))
        }
    }
}

internal object GuardMediaVerifier {
    fun verify(file: File, expectedCount: Int): Long? {
        val extractor = MediaExtractor()
        var durationMs: Long? = null
        try {
            extractor.setDataSource(file.absolutePath)
            check(extractor.trackCount == 1 && extractor.getTrackFormat(0).getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_VIDEO_AVC)
            val format = extractor.getTrackFormat(0)
            if (format.containsKey(MediaFormat.KEY_DURATION)) durationMs = (format.getLong(MediaFormat.KEY_DURATION) / 1000).takeIf { it > 0 }
            extractor.selectTrack(0)
            check(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
            var count = 0; var previous = -1L
            while (extractor.sampleTime >= 0) {
                check(extractor.sampleTime > previous && ++count <= expectedCount)
                previous = extractor.sampleTime
                if (!extractor.advance()) break
            }
            check(count == expectedCount && count > 1 && previous > 0)
        } finally { extractor.release() }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val bitmap = if (android.os.Build.VERSION.SDK_INT >= 27)
                retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 160, 640)
            else retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            check(bitmap != null) { "SENTRY_FIRST_FRAME_DECODE_FAILED" }; bitmap.recycle()
        } finally { retriever.release() }
        return durationMs
    }
    fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val scratch = ByteArray(64 * 1024)
        FileInputStream(file).use { input ->
            while (true) { val size = input.read(scratch); if (size < 0) break; digest.update(scratch, 0, size) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
