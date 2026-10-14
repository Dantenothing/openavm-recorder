package com.dante.zeekrcapabilitylab.recorder

import android.media.*
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.dante.zeekrcapabilitylab.preflight.continuous.ProbeCodecFormat
import com.dante.zeekrcapabilitylab.probe.camera.CameraFormatProfile
import com.dante.zeekrcapabilitylab.probe.camera.ProfileSize
import com.dante.zeekrcapabilitylab.service.recorder.*
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/** Exercises the actual product raster and backend. Unsupported emulator codecs are not a pass. */
class ProductRecorderPipelineAndroidTest {
    @Test fun codecStartupFailureWaitsQuietlyAndRetainsOutputUntilTerminalCleanup() {
        assumeTrue("Developer emulator only", Build.HARDWARE == "ranchu")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.filesDir, "stability-product/failed-codec-${UUID.randomUUID()}").apply { mkdirs() }
        val lanes = SegmentLaneLayoutFactory.forProfile(1280, 5140,
            listOf("1", "2", "3", "4"), listOf(1, 2, 3, 4), listOf(0, 0, 0, 0))
        val config = RecorderConfig(SessionSourceSnapshot(RecordingSourceRole.SURROUND, "synthetic",
            CameraFormatProfile(ProfileSize(1280, 5140), 14_000_000), RecordingLayoutKind.FOUR_LANE_V1,
            laneLayout = lanes), 60, 5L shl 30, sharedInputRecordingEnabled = true)
        val problem = AtomicReference<String?>()
        val output = TestOutput(File(directory, "not-started.mp4"))
        val recorder = ProductContinuousRecorder("openavm.intentional.missing.codec", 1, 0,
            { _, reason -> problem.compareAndSet(null, reason) }, { _, _, _, _, _ -> },
            { _, _ -> fail("A startup failure cannot publish a successful recording") }, { _, unused -> unused.close() },
            ContinuousFailureCapture("failed-codec", SystemClock::elapsedRealtime, System::currentTimeMillis), { _, _ -> })
        val names = listOf("product-codec-drain", "product-file-writer")
        fun snapshots(): Map<String, Long> = names.associateWith { name ->
            val task = File("/proc/self/task").listFiles()!!.single { runCatching { File(it, "comm").readText().trim() == name.take(15) }.getOrDefault(false) }
            File(task, "status").readLines().first { it.startsWith("voluntary_ctxt_switches:") }
                .substringAfter(':').trim().toLong()
        }
        try {
            assertNotNull("Real MediaCodec creation must fail", runCatching { recorder.prepare(config, output) }.exceptionOrNull())
            assertNotNull(problem.get())
            Thread.sleep(100)
            val before = snapshots()
            Thread.sleep(600)
            val after = snapshots()
            val wakes = names.associateWith { after.getValue(it) - before.getValue(it) }
            File(directory, "result.txt").writeText("scope=ACTUAL_PRODUCT_BACKEND_CODEC_CREATION_FAILURE_NO_AVC_NO_CAMERA\n" +
                "waitMs=600\n" + wakes.entries.joinToString("\n") { "${it.key}=${it.value}" } +
                "\noutputReleasedBeforeStop=${output.nativeReleaseConfirmed}\n")
            assertFalse("A failed worker cannot assume its owner authorized output cleanup", output.nativeReleaseConfirmed)
            assertTrue("Terminal wait must not poll every 2 ms: $wakes", wakes.values.all { it <= 5 })
        } finally {
            recorder.release()
            assertTrue("Release still closes the real admitted FD", output.nativeReleaseConfirmed)
        }
        assertTrue(Thread.getAllStackTraces().keys.none { it.isAlive && it.name in names + "product-close-watchdog" })
    }

    @Test fun actualSurroundRasterCutsAndReleasesThroughTheProductBackend() {
        assumeTrue("Developer emulator only", Build.HARDWARE == "ranchu")
        assumeTrue("Explicit full-raster capability trial",
            InstrumentationRegistry.getArguments().getString("stabilityProductRaster") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.filesDir, "stability-product/${UUID.randomUUID()}").apply { mkdirs() }
        val lanes = SegmentLaneLayoutFactory.forProfile(1280, 5140,
            listOf("1", "2", "3", "4"), listOf(1, 2, 3, 4), listOf(0, 0, 0, 0))
        val config = RecorderConfig(SessionSourceSnapshot(RecordingSourceRole.SURROUND, "synthetic",
            CameraFormatProfile(ProfileSize(1280, 5140), 14_000_000), RecordingLayoutKind.FOUR_LANE_V1,
            laneLayout = lanes), 60, 5L shl 30, sharedInputRecordingEnabled = true, requestedFrameRate = 15)
        val format = ProbeCodecFormat.create(ProductContinuousRecorder.spec(config))
        val codec = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format)
        File(directory, "capability.txt").writeText("input=1280x5140\noutput=3840x1728\nfps=15\n" +
            "codec=${codec ?: "NONE"}\n" + MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { it.isEncoder && it.supportedTypes.contains("video/avc") }.joinToString("\n") {
                    "${it.name}: ${runCatching { it.getCapabilitiesForType("video/avc").isFormatSupported(format) }.getOrNull()}"
                })
        check(codec != null) { "DEVELOPMENT_ENVIRONMENT_UNSUPPORTED: exact product AVC format; see capability.txt" }
        val problem = AtomicReference<String?>()
        val closed = CopyOnWriteArrayList<ProductEncodedFile>()
        val outputs = mutableListOf<TestOutput>()
        val capture = ContinuousFailureCapture("synthetic-product", SystemClock::elapsedRealtime, System::currentTimeMillis)
        val recorder = ProductContinuousRecorder(codec, 1, 0,
            { _, message -> problem.compareAndSet(null, message) }, { _, _, _, _, _ -> },
            { _, file -> closed += file }, { _, output -> output.close() }, capture,
            { _, message -> problem.compareAndSet(null, message) })
        val producer = PaintProducer()
        var stopped = false
        var released = false
        try {
            fun output(number: Int) = TestOutput(File(directory, "segment-$number.mp4")).also { outputs += it }
            recorder.prepare(config, output(1)); recorder.start(); producer.attach(recorder.surface)
            recorder.prepareNext(2, output(2))
            val start = SystemClock.elapsedRealtime()
            var frame = 0; var cuts = 0; var prepared = 2
            while (SystemClock.elapsedRealtime() - start < 10_000) {
                problem.get()?.let { error(it) }
                if (closed.size >= cuts && prepared == cuts + 1 && cuts < 3) {
                    prepared++; recorder.prepareNext(prepared, output(prepared))
                }
                val ready = recorder.evidence().continuousStages?.get("nextPreparedFile")?.jsonPrimitive?.intOrNull
                if (cuts < 3 && closed.size == cuts && ready == cuts + 2 &&
                    SystemClock.elapsedRealtime() - start >= (cuts + 1) * 2_000) {
                    recorder.requestCut(); cuts++
                }
                producer.frame(frame++, 1280, 5140)
                val remaining = start + frame * 1_000L / 15 - SystemClock.elapsedRealtime()
                if (remaining > 0) Thread.sleep(remaining)
            }
            recorder.prepareForStop(); producer.close(); recorder.stop(); stopped = true
            recorder.release(); released = true
            assertNull(problem.get())
            assertEquals(3, cuts); assertEquals(4, closed.size)
            val evidence = recorder.evidence()
            assertEquals(evidence.frames, evidence.writtenFrames)
            assertEquals(evidence.writtenFrames, closed.sumOf { it.frames })
            assertEquals(0, evidence.queueItems)
            assertTrue(outputs.all { it.nativeReleaseConfirmed })
            closed.forEach { file ->
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(file.output.localWorkingFile!!.absolutePath)
                    extractor.selectTrack(0)
                    assertEquals(3840, extractor.getTrackFormat(0).getInteger(MediaFormat.KEY_WIDTH))
                    assertEquals(1728, extractor.getTrackFormat(0).getInteger(MediaFormat.KEY_HEIGHT))
                    assertEquals(0L, extractor.sampleTime)
                    assertTrue(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                    var samples = 0L
                    while (extractor.sampleTime >= 0) { samples++; extractor.advance() }
                    assertEquals(file.frames, samples)
                } finally { extractor.release() }
            }
            File(directory, "result.txt").writeText("backend=${recorder.name}\ninput=SYNTHETIC_EGL\n" +
                "output=REAL_FD_NOT_USB_PROVIDER\nencoded=${evidence.frames}\nwritten=${evidence.writtenFrames}\nfiles=${closed.size}\n")
        } finally {
            recorder.prepareForStop(); producer.close()
            if (!stopped) runCatching { recorder.stop() }
            if (!released) recorder.release()
            outputs.forEach { it.close() }
        }
    }

    private class TestOutput(override val localWorkingFile: File) : RecordingOutputHandle {
        private var descriptor: ParcelFileDescriptor? = ParcelFileDescriptor.open(localWorkingFile,
            ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE)
        override val storage = RecordingStorageIdentity(RecordingStorageKind.INTERNAL)
        override val displayName get() = localWorkingFile.name
        override val nativeReleaseConfirmed get() = descriptor == null
        override fun bind(recorder: MediaRecorder) = error("Expected product continuous codec")
        override fun createMuxer() = MediaMuxer(requireNotNull(descriptor).fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        override fun close() { descriptor?.close(); descriptor = null }
    }
}
