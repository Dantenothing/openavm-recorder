package com.dante.zeekrcapabilitylab.player

import android.media.*
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.dante.zeekrcapabilitylab.probe.camera.CameraFormatProfile
import com.dante.zeekrcapabilitylab.probe.camera.ProfileSize
import com.dante.zeekrcapabilitylab.service.recorder.*
import io.github.dantenothing.avmtransfer.protocol.*
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.UUID

/** Synthetic encoder fixture, no cameras/location/vehicle access. Compiling is not execution. */
class SessionClipExporterAndroidTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val session = UUID.randomUUID().toString()
    private lateinit var directory: File

    @Before fun setup() {
        assumeTrue(Build.HARDWARE == "ranchu" || InstrumentationRegistry.getArguments().getString("openavmSyntheticMedia") == "true")
        directory = File(context.cacheDir, "synthetic-clips-${UUID.randomUUID()}").apply { mkdirs() }
    }
    @After fun cleanup() { if (::directory.isInitialized) directory.deleteRecursively() }

    @Test fun selectedRangeAcrossTwoFilesProducesOneVerifiedMp4WithoutDriveAttachment() {
        val a = fixture(File(directory, "a.mp4"), 1_000)
        // Same bitstream configuration also avoids depending on a second encoder's chosen SPS.
        val b = File(directory, "b.mp4").apply { a.copyTo(this) }
        val first = SegmentSidecarIO.readForMedia(a)!!
        SegmentSidecarIO.writeAtomic(b, first.copy(file = b.absolutePath, startedAtElapsedRealtimeMs = 2_000,
            stoppedAtElapsedRealtimeMs = 3_000, segmentNumber = 2))
        val timeline = SessionPlaybackTimeline(session, listOf(
            SessionVideoSegment("a", "FRONT_CABIN", session, 1_000, 2_000, 1_000),
            SessionVideoSegment("b", "FRONT_CABIN", session, 2_000, 3_000, 1_000)))
        val result = SessionClipExporter.export(context, timeline, mapOf("a" to a, "b" to b), "FRONT_CABIN", 0, 1_500, {})
        try {
            assertEquals(1_500L, result.durationMs)
            assertEquals(0L, result.extraLeadMs)
            assertTrue(result.file.length() > 0)
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(result.file.absolutePath)
                assertEquals(1, extractor.trackCount)
                assertEquals(128, extractor.getTrackFormat(0).getInteger(MediaFormat.KEY_WIDTH))
                extractor.selectTrack(0)
                assertTrue(extractor.readSampleData(java.nio.ByteBuffer.allocateDirect(1024 * 1024), 0) > 0)
            } finally { extractor.release() }
            assertFalse(File(result.file.absolutePath + ".drive.json").exists())
        } finally { result.file.delete() }
    }

    @Test fun cancellationDoesNotPublishAClip() {
        val file = fixture(File(directory, "a.mp4"), 1_000)
        val timeline = SessionPlaybackTimeline(session, listOf(SessionVideoSegment("a", "FRONT_CABIN", session, 1_000, 2_000, 1_000)))
        val cache = File(context.cacheDir, "session-clips")
        val before = cache.listFiles().orEmpty().map { it.name }.toSet()
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            SessionClipExporter.export(context, timeline, mapOf("a" to file), "FRONT_CABIN", 0, 900,
                { throw kotlinx.coroutines.CancellationException("synthetic cancellation") })
        }
        assertEquals(before, cache.listFiles().orEmpty().map { it.name }.toSet())
        assertFalse(PlaybackPinRegistry.isPinned(file))
    }

    @Test fun cancellationAfterSamplesWereWrittenReleasesPinsAndDeletesPartialOutput() {
        val file = fixture(File(directory, "cancel-mid.mp4"), 1_000)
        val cache = File(context.cacheDir, "session-clips")
        val before = cache.listFiles().orEmpty().map { it.name }.toSet()
        var checks = 0
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            SessionClipExporter.export(context, singleTimeline(), mapOf("a" to file), "FRONT_CABIN", 0, 900, {
                assertTrue(PlaybackPinRegistry.isPinned(file))
                if (++checks == 7) throw kotlinx.coroutines.CancellationException("after several muxed samples")
            })
        }
        assertEquals(7, checks)
        assertFalse(PlaybackPinRegistry.isPinned(file))
        assertEquals(before, cache.listFiles().orEmpty().map { it.name }.toSet())
    }

    @Test fun cancellationAtFinalProgressStillDeletesVerifiedOutput() {
        val file = fixture(File(directory, "cancel-late.mp4"), 1_000)
        val cache = File(context.cacheDir, "session-clips")
        val before = cache.listFiles().orEmpty().map { it.name }.toSet()
        var cancel = false
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            SessionClipExporter.export(context, singleTimeline(), mapOf("a" to file), "FRONT_CABIN", 0, 900,
                { if (cancel) throw kotlinx.coroutines.CancellationException("after output verification") },
                { if (it == 1f) cancel = true })
        }
        assertTrue(cancel)
        assertFalse(PlaybackPinRegistry.isPinned(file))
        assertEquals(before, cache.listFiles().orEmpty().map { it.name }.toSet())
    }

    @Test fun trimUsesPreviousRealKeyframeAndReportsItsExtraLead() {
        val file = fixture(File(directory, "trim.mp4"), 1_000)
        val reader = MediaExtractor()
        val previousSync = try {
            reader.setDataSource(file.absolutePath); reader.selectTrack(0)
            reader.seekTo(300_000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            reader.sampleTime
        } finally { reader.release() }
        assertTrue(previousSync in 0..300_000L)
        val result = SessionClipExporter.export(context, singleTimeline(), mapOf("a" to file), "FRONT_CABIN", 300, 800, {})
        try {
            assertEquals((300_000 - previousSync) / 1000, result.extraLeadMs)
            assertEquals((800_000 - previousSync) / 1000, result.durationMs)
            val output = MediaExtractor()
            try {
                output.setDataSource(result.file.absolutePath); output.selectTrack(0)
                assertEquals(0L, output.sampleTime)
                assertTrue(output.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                var previous = -1L
                do {
                    assertTrue(output.sampleTime > previous)
                    assertTrue(output.sampleTime < result.durationMs * 1000)
                    previous = output.sampleTime
                } while (output.advance())
                assertEquals(result.durationMs * 1000, output.getTrackFormat(0).getLong(MediaFormat.KEY_DURATION))
            } finally { output.release() }
        } finally { result.file.delete() }
    }

    @Test fun repackedExportRoundTripsFrozenGeometryWithoutOriginalPrivateMetadata() {
        val file = fixture(File(directory, "repacked.mp4"), 1_000)
        val contract = StripRepackContract(inputWidth = 64, inputHeight = 256, stripHeight = 128, encodedWidth = 128, encodedHeight = 128)
        val layout = SegmentLaneLayout(64, 256, "VERTICAL_1X4", (1..4).map {
            SegmentLaneInfo(it, 0, 64, (it - 1) * 64, it * 64, "View $it", 90, 5 - it)
        })
        val original = SegmentSidecarIO.readForMedia(file)!!
        SegmentSidecarIO.writeAtomic(file, original.copy(sourceRole = RecordingSourceRole.SURROUND,
            layoutKind = RecordingLayoutKind.FOUR_LANE_V1, profile = CameraFormatProfile(ProfileSize(64, 256), 128_000),
            laneLayout = layout, rasterLayout = JsonObject(Json.encodeToJsonElement(StripRepackContract.serializer(), contract).jsonObject +
                ("unknownPrivateValue" to JsonPrimitive("PRIVATE_FIXTURE_SENTINEL")))))
        val timeline = SessionPlaybackTimeline(session, listOf(SessionVideoSegment("a", "SURROUND", session, 1_000, 2_000, 1_000)))
        val result = SessionClipExporter.export(context, timeline, mapOf("a" to file), "SURROUND", 300, 800, {})
        try {
            val restored = RecordingMetadataReader.read(result.file, emptyList())
            assertEquals(RecordingRasterMetadata.Repacked(contract), restored.raster)
            val document = restored.text!!
            val obj = Json.parseToJsonElement(document).jsonObject
            assertEquals(Json.encodeToJsonElement(SegmentLaneLayout.serializer(), layout), obj["laneLayout"])
            assertEquals(128, obj["actualTrack"]!!.jsonObject["width"]!!.jsonPrimitive.int)
            assertEquals(result.durationMs, obj["actualTrack"]!!.jsonObject["durationMs"]!!.jsonPrimitive.long)
            listOf("PRIVATE_FIXTURE_SENTINEL", file.absolutePath, session, "cameraId", "processStartId", "videoClockAnchor", "latitude")
                .forEach { assertFalse(it, document.contains(it)) }
            assertFalse(SegmentSidecarIO.sidecarFileFor(result.file).exists())
        } finally { result.file.delete() }
        assertFalse(PlaybackPinRegistry.isPinned(file))
    }

    @Test fun sameSessionReplacedChannelAnchorAndDurationAreRejectedWithoutOutput() {
        val file = fixture(File(directory, "changed.mp4"), 1_000)
        val original = SegmentSidecarIO.readForMedia(file)!!
        val cache = File(context.cacheDir, "session-clips")
        val before = cache.listFiles().orEmpty().map { it.name }.toSet()
        listOf(original.copy(channelId = "REAR_CABIN"), original.copy(videoClockAnchor = VideoClockAnchor(
            elapsedRealtimeNs = 1_000_000_000, source = "CODEC_OUTPUT_OBSERVED")),
            original.copy(actualTrack = original.actualTrack!!.copy(durationMs = 2_000))).forEach { changed ->
            SegmentSidecarIO.writeAtomic(file, changed)
            assertThrows(IllegalArgumentException::class.java) {
                SessionClipExporter.export(context, singleTimeline(), mapOf("a" to file), "FRONT_CABIN", 0, 900, {})
            }
            assertFalse(PlaybackPinRegistry.isPinned(file))
            assertEquals(before, cache.listFiles().orEmpty().map { it.name }.toSet())
        }
    }

    private fun singleTimeline() = SessionPlaybackTimeline(session,
        listOf(SessionVideoSegment("a", "FRONT_CABIN", session, 1_000, 2_000, 1_000)))

    private fun fixture(file: File, start: Long): File {
        val codec = MediaCodec.createEncoderByType("video/avc")
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        try {
            val format = MediaFormat.createVideoFormat("video/avc", 128, 128).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000); setInteger(MediaFormat.KEY_FRAME_RATE, 10)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start()
            val info = MediaCodec.BufferInfo()
            var frame = 0; var inputDone = false; var outputDone = false; var track = -1
            val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
            while (!outputDone && android.os.SystemClock.elapsedRealtime() < deadline) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        if (frame == 10) { codec.queueInputBuffer(index, 0, 0, 1_000_000, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else {
                            val buffer = codec.getInputBuffer(index)!!
                            buffer.clear(); buffer.put(ByteArray(128 * 128 * 3 / 2) { 96.toByte() })
                            codec.queueInputBuffer(index, 0, 128 * 128 * 3 / 2, frame * 100_000L, 0); frame++
                        }
                    }
                }
                when (val index = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(codec.outputFormat); muxer.start(); started = true }
                    else -> if (index >= 0) {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) muxer.writeSampleData(track, codec.getOutputBuffer(index)!!, info)
                        outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(index, false)
                    }
                }
            }
            check(outputDone)
            info.set(0, 0, 1_000_000, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            muxer.writeSampleData(track, java.nio.ByteBuffer.allocate(0), info)
        } finally { runCatching { codec.stop() }; codec.release(); if (started) muxer.stop(); muxer.release() }
        SegmentSidecarIO.writeAtomic(file, SegmentSidecar(file = file.absolutePath, cameraId = "synthetic",
            profile = CameraFormatProfile(ProfileSize(128, 128), 128_000), sourceRole = RecordingSourceRole.CABIN,
            layoutKind = RecordingLayoutKind.SINGLE_V1, segmentSeconds = 1, segmentNumber = 1, processStartId = "fixture",
            recordingSessionId = session, startedAtElapsedRealtimeMs = start, stoppedAtElapsedRealtimeMs = start + 1_000,
            result = "SUCCESS", actualTrack = ActualTrackInfo(width = 128, height = 128, durationMs = 1_000)))
        return file
    }
}
