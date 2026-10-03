package com.dante.zeekrcapabilitylab.player

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import com.dante.zeekrcapabilitylab.service.recorder.PlaybackPinRegistry
import com.dante.zeekrcapabilitylab.service.recorder.SegmentSidecar
import com.dante.zeekrcapabilitylab.service.recorder.SegmentSidecarIO
import io.github.dantenothing.avmtransfer.protocol.*
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID

internal data class SessionClipResult(val file: File, val extraLeadMs: Long, val durationMs: Long)

/** Bounded same-channel remux; no decoder, locations, account data, audio removal, or guessed gaps.
 * Caller runs on IO and supplies cancellation. Every source remains pinned until completion. */
internal object SessionClipExporter {
    const val MAX_CLIP_MS = 20 * 60_000L
    const val MAX_BYTES = 2L * 1024 * 1024 * 1024
    private const val BUFFER_BYTES = 16 * 1024 * 1024

    fun export(context: Context, timeline: SessionPlaybackTimeline, filesById: Map<String, File>, channel: String,
        fromMs: Long, toMs: Long, checkActive: () -> Unit, progress: (Float) -> Unit = {}): SessionClipResult {
        require(toMs - fromMs in 1..MAX_CLIP_MS) { "CLIP_RANGE_LIMIT" }
        val slices = requireNotNull(timeline.range(channel, fromMs, toMs)) { "CLIP_HAS_GAP_OR_OVERLAP" }
        require(slices.size <= 256) { "CLIP_SEGMENT_LIMIT" }
        val files = slices.map { requireNotNull(filesById[it.segmentId]) { "CLIP_SOURCE_MISSING" } }
        val cache = File(context.cacheDir, "session-clips").apply { check(isDirectory || mkdirs()) }
        val output = File(cache, "clip-${UUID.randomUUID()}.mp4")
        val outputLimit = minOf(MAX_BYTES, cache.usableSpace - 256L * 1024 * 1024)
        require(outputLimit > 16 * 1024 * 1024) { "CLIP_STORAGE_FULL" }
        var muxer: MediaMuxer? = null
        var started = false
        var completed = false
        var totalBytes = 0L
        var baseUs = 0L
        var previousOutputUs = -1L
        var extraLeadUs = 0L
        var reference: FormatFingerprint? = null
        var referenceRaster: SessionClipRaster? = null
        var referenceSidecar: SegmentSidecar? = null
        files.forEach(PlaybackPinRegistry::acquire)
        try {
            checkActive()
            val buffer = ByteBuffer.allocateDirect(BUFFER_BYTES)
            val info = MediaCodec.BufferInfo()
            var outputTrack = -1
            for ((index, slice) in slices.withIndex()) {
                checkActive()
                val file = files[index]
                require(file.isFile && file.length() > 0) { "CLIP_SOURCE_MISSING" }
                val sourceSize = file.length()
                val sourceModified = file.lastModified()
                val expected = requireNotNull(timeline.segments.singleOrNull { it.id == slice.segmentId }) { "CLIP_SOURCE_CHANGED" }
                val sidecar = readSidecar(file)
                SessionClipPolicy.requireBinding(sidecar, expected, channel)
                val raster = SessionClipPolicy.raster(sidecar)
                val declaredRaster = raster?.let { RecordingRasterMetadata.Repacked(it.contract) } ?: RecordingRasterMetadata.Original
                require(RecordingRasterReader.read(file) == declaredRaster) { "CLIP_LAYOUT_UNSUPPORTED" }
                require(index == 0 || (raster == referenceRaster && sidecar.layoutKind == referenceSidecar?.layoutKind &&
                    sidecar.laneLayout == referenceSidecar?.laneLayout)) { "CLIP_LAYOUT_MISMATCH" }
                if (index == 0) { referenceRaster = raster; referenceSidecar = sidecar }
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(file.absolutePath)
                    val tracks = (0 until extractor.trackCount).map { it to extractor.getTrackFormat(it) }
                    // Reject additional tracks rather than silently dropping audio or alternative video.
                    require(tracks.size == 1 && tracks.single().second.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                        "CLIP_TRACKS_UNSUPPORTED"
                    }
                    val (track, format) = tracks.single()
                    val fingerprint = FormatFingerprint.of(format)
                    require(declaredRaster.trackError(fingerprint.width, fingerprint.height) == null &&
                        (raster == null || fingerprint.rotation == 0) &&
                        (sidecar.actualTrack?.width == null || sidecar.actualTrack.width == fingerprint.width) &&
                        (sidecar.actualTrack?.height == null || sidecar.actualTrack.height == fingerprint.height)) { "CLIP_TRACK_LAYOUT_MISMATCH" }
                    require(format.containsKey(MediaFormat.KEY_DURATION)) { "CLIP_DURATION_UNAVAILABLE" }
                    SessionClipPolicy.requireDuration(expected.mediaDurationMs, sidecar.actualTrack?.durationMs, format.getLong(MediaFormat.KEY_DURATION))
                    require(index == 0 || fingerprint == reference) { "CLIP_CODEC_MISMATCH" }
                    reference = fingerprint
                    if (index == 0) {
                        muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                        val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
                        require(rotation in setOf(0, 90, 180, 270)) { "CLIP_ROTATION_UNSUPPORTED" }
                        muxer.setOrientationHint(rotation)
                        outputTrack = muxer.addTrack(format)
                        muxer.start(); started = true
                    }
                    extractor.selectTrack(track)
                    val requestedFromUs = slice.fromMediaMs * 1000
                    val toUs = slice.toMediaMs * 1000
                    extractor.seekTo(requestedFromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    val firstUs = extractor.sampleTime
                    require(firstUs >= 0 && firstUs <= requestedFromUs && extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                        "CLIP_KEYFRAME_UNAVAILABLE"
                    }
                    if (index == 0) {
                        extraLeadUs = requestedFromUs - firstUs
                        require(extraLeadUs <= 10_000_000) { "CLIP_KEYFRAME_TOO_FAR" }
                    } else require(firstUs == requestedFromUs) { "CLIP_BOUNDARY_NOT_KEYFRAME" }
                    var written = 0
                    var lastUs = firstUs
                    while (extractor.sampleTime in 0 until toUs) {
                        checkActive()
                        val sampleUs = extractor.sampleTime
                        require(sampleUs >= firstUs && (written == 0 || sampleUs > lastUs)) { "CLIP_NON_MONOTONIC_VIDEO" }
                        require(extractor.sampleFlags and (MediaExtractor.SAMPLE_FLAG_ENCRYPTED or MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) == 0) {
                            "CLIP_SAMPLE_UNSUPPORTED"
                        }
                        buffer.clear()
                        // API 26/27 have no sampleSize getter. readSampleData still uses the fixed
                        // capacity; an oversized sample fails and the incomplete output is removed.
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                            require(extractor.sampleSize in 1..BUFFER_BYTES.toLong()) { "CLIP_SAMPLE_TOO_LARGE" }
                        val bytes = extractor.readSampleData(buffer, 0)
                        require(bytes in 1..BUFFER_BYTES) { "CLIP_READ_FAILED" }
                        totalBytes += bytes
                        require(totalBytes <= outputLimit) { "CLIP_STORAGE_LIMIT" }
                        val pts = baseUs + sampleUs - firstUs
                        require(pts > previousOutputUs) { "CLIP_NON_MONOTONIC_OUTPUT" }
                        info.set(0, bytes, pts, if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                        buffer.position(0); buffer.limit(bytes)
                        muxer!!.writeSampleData(outputTrack, buffer, info)
                        previousOutputUs = pts; lastUs = sampleUs; written++
                        if (written % 120 == 0) progress(((index + (sampleUs - firstUs).toDouble() / (toUs - firstUs)) / slices.size).toFloat())
                        if (!extractor.advance()) break
                    }
                    require(written > 0 && (extractor.sampleTime >= toUs || toUs - lastUs <= 200_000)) { "CLIP_SOURCE_TRUNCATED" }
                    baseUs += toUs - firstUs
                } finally { extractor.release() }
                checkActive()
                require(file.length() == sourceSize && file.lastModified() == sourceModified) { "CLIP_SOURCE_CHANGED" }
                val after = readSidecar(file)
                SessionClipPolicy.requireBinding(after, expected, channel)
                require(SessionClipPolicy.raster(after) == raster && after.laneLayout == sidecar.laneLayout &&
                    after.actualTrack == sidecar.actualTrack) { "CLIP_SOURCE_CHANGED" }
            }
            checkActive()
            // Explicit empty EOS fixes the requested last-frame duration in the MP4 time table.
            info.set(0, 0, baseUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            muxer!!.writeSampleData(outputTrack, ByteBuffer.allocate(0), info)
            muxer.stop(); started = false
            muxer.release(); muxer = null
            checkActive()
            referenceRaster?.document(baseUs / 1000)?.let { document ->
                val inspection = EmbeddedRecordingMetadata.inspect(output)
                require(inspection.appendable && inspection.error == null) { "CLIP_METADATA_FAILED" }
                output.appendBytes(EmbeddedRecordingMetadata.box(document))
            }
            require(output.isFile && output.length() > 0) { "CLIP_OUTPUT_EMPTY" }
            val verification = MediaExtractor()
            try {
                verification.setDataSource(output.absolutePath)
                require(verification.trackCount == 1) { "CLIP_OUTPUT_INVALID" }
                val format = verification.getTrackFormat(0)
                require(format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) { "CLIP_OUTPUT_INVALID" }
                val durationUs = format.getLong(MediaFormat.KEY_DURATION)
                require(kotlin.math.abs(durationUs - baseUs) <= 1_000) { "CLIP_OUTPUT_DURATION_MISMATCH" }
                verification.selectTrack(0)
                buffer.clear()
                require(verification.sampleTime == 0L && verification.readSampleData(buffer, 0) in 1..BUFFER_BYTES) { "CLIP_OUTPUT_INVALID" }
            } finally { verification.release() }
            checkActive(); progress(1f); checkActive(); completed = true
            return SessionClipResult(output, extraLeadUs / 1000, baseUs / 1000)
        } finally {
            if (started) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            if (!completed) output.delete()
            files.forEach(PlaybackPinRegistry::release)
        }
    }

    private fun readSidecar(file: File): SegmentSidecar {
        val path = listOf(SegmentSidecarIO.sidecarFileFor(file), File(file.parentFile, file.nameWithoutExtension + ".sidecar.json"))
            .firstOrNull { it.isFile } ?: error("CLIP_SOURCE_CHANGED")
        val text = path.inputStream().use(RecordingMetadataReader::readBounded)
        return SegmentSidecarIO.json.decodeFromString(SegmentSidecar.serializer(), text)
    }

    private data class FormatFingerprint(val mime: String, val width: Int, val height: Int, val rotation: Int, val csd: List<List<Byte>?>) {
        companion object {
            fun of(format: MediaFormat) = FormatFingerprint(
                format.getString(MediaFormat.KEY_MIME).orEmpty(), format.getInteger(MediaFormat.KEY_WIDTH), format.getInteger(MediaFormat.KEY_HEIGHT),
                if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0,
                (0..2).map { index -> format.getByteBuffer("csd-$index")?.duplicate()?.let { value ->
                    require(value.remaining() <= 256 * 1024) { "CLIP_CODEC_CONFIG_LIMIT" }
                    ByteArray(value.remaining()).also(value::get).toList()
                } })
        }
    }
}
