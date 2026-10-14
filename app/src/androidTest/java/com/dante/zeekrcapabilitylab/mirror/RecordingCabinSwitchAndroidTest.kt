package com.dante.zeekrcapabilitylab.mirror

import android.content.Intent
import android.media.*
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import androidx.test.platform.app.InstrumentationRegistry
import com.dante.zeekrcapabilitylab.probe.camera.CameraFormatProfile
import com.dante.zeekrcapabilitylab.probe.camera.ProfileSize
import com.dante.zeekrcapabilitylab.product.SettingsStore
import com.dante.zeekrcapabilitylab.recorder.PaintProducer
import com.dante.zeekrcapabilitylab.service.recorder.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real cabin AVC encoder, decoder, MP4 and mirror buttons, with synthetic EGL camera frames.
 * Does not claim vehicle HAL, concurrent hardware encoding, USB or sleep acceptance. */
class RecordingCabinSwitchAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun await(message: String, test: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 12_000
        while (!test() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(30)
        assertTrue("$message; ${MirrorPreviewRuntime.evidence}", test())
    }

    @Test fun logoSwitchesExistingCabinWithoutReplacingSurroundOrCuttingRecording() {
        assumeTrue("Disposable emulator only", Build.HARDWARE == "ranchu")
        val activity = instrumentation.startActivitySync(Intent(context, MirrorGlTestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MirrorGlTestActivity
        val source = SurroundSource()
        var controller: MirrorPreviewController? = null
        val cabinConfig = RecorderConfig(SessionSourceSnapshot(RecordingSourceRole.CABIN, "synthetic-cabin",
            CameraFormatProfile(ProfileSize(640, 480), 2_000_000), RecordingLayoutKind.SINGLE_V1), 60, 5L shl 30)
        val error = AtomicReference<String?>()
        val codec = requireNotNull(MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(ContinuousVideoRecorder.format(cabinConfig)))
        val recorder = ContinuousVideoRecorder(codec, { _, reason -> error.set(reason) }, cabinPreviewSessionId = source.run)
        val dir = File(context.filesDir, "mirror-cabin-switch/${source.run}").apply { mkdirs() }
        val output = FdOutput(File(dir, "continuous-cabin.mp4"))
        val stop = AtomicBoolean()
        val produced = AtomicInteger()
        val producerError = AtomicReference<Throwable?>()
        var producer: Thread? = null
        var recorderStopped = false
        try {
            main {
                SettingsStore.get(context).setMirrorPreviewEnabled(true)
                MirrorPresentation(context).apply {
                    videoVisible = true; selectedLane = 1; grid = false; dock = 0
                    controlsVisible = true; widthDp = 720
                    repeat(4) { setDirection(it, it + 1) }
                }
                activity.setContentView(MirrorHomeHost(activity))
                controller = MirrorPreviewController(context, source.config, source, source.run, 1,
                    declaredSizeLookup = { ProfileSize(320, 1280) })
            }
            await("surround preview") { MirrorPreviewRuntime.evidence.reason == "LIVE" }
            main { activity.moveTaskToBack(true) }
            await("floating controls") { MirrorPreviewRuntime.evidence.destination == "OVERLAY" && MirrorPreviewRuntime.evidence.reason == "LIVE" }
            fun window(): MirrorOverlayWindow = MirrorPreviewController::class.java.getDeclaredField("overlayWindow")
                .apply { isAccessible = true }.get(controller) as MirrorOverlayWindow
            fun selector(): MirrorCameraSelector = descendants(window().root).filterIsInstance<MirrorCameraSelector>().first()
            fun cabinView(): RecordingCabinView? = MirrorPreviewController::class.java.getDeclaredField("recordingCabinView")
                .apply { isAccessible = true }.get(controller) as? RecordingCabinView
            // Missing cabin must not use the legacy record/stop handoff.
            main { assertTrue(selector().cabin.performClick()) }
            assertNull(cabinView()); assertEquals(0, source.stops.get()); assertEquals(1, source.replacements.get())
            recorder.prepare(cabinConfig, output); recorder.start()
            producer = Thread({
                val gl = PaintProducer()
                try {
                    gl.attach(recorder.surface)
                    while (!stop.get()) { gl.frame(produced.incrementAndGet(), 640, 480); Thread.sleep(34) }
                } catch (failure: Throwable) { producerError.set(failure) }
                finally { runCatching { gl.close() }.onFailure { producerError.set(it) } }
            }, "test-cabin-encoder-producer").apply { start() }
            await("encoded cabin feed") { (RecordingCabinFeed.find(source.run)?.read(0)?.frames?.size ?: 0) > 3 }
            val surface = source.surface
            val start = SystemClock.elapsedRealtime()
            repeat(8) { cycle ->
                val before = cabinView()?.frames ?: 0
                main { assertTrue(selector().cabin.performClick()) }
                await("cabin displayed at switch $cycle") { (cabinView()?.frames ?: 0) > before + 2 }
                main { assertTrue(selector().cabin.isSelected) }
                if (cycle == 0) screenshot(File(dir, "cabin-overlay.png"))
                val encoded = recorder.evidence().frames
                main { assertTrue(selector().directions[cycle % 4].performClick()) }
                await("surround selected at switch $cycle") { !selector().cabin.isSelected }
                await("encoder continues") { recorder.evidence().frames > encoded + 2 }
                if (cycle == 0) screenshot(File(dir, "surround-overlay.png"))
                assertSame(surface, source.surface)
                assertEquals(1, source.replacements.get()); assertEquals(0, source.stops.get())
                assertEquals(source.run, source.state.recordingSessionId)
                assertNull(error.get()); assertNull(producerError.get()); assertNull(source.error.get())
            }
            // Rapid tapping cannot multiply decoders, replace camera outputs or queue recording starts.
            main { repeat(30) { selector().cabin.performClick(); selector().directions[it % 4].performClick() } }
            val soakSeconds = InstrumentationRegistry.getArguments().getString("cabinSwitchSoakSeconds")
                ?.toIntOrNull()?.coerceIn(0, 180) ?: 0
            val soakUntil = SystemClock.elapsedRealtime() + soakSeconds * 1000L
            var soakSwitches = 0
            while (SystemClock.elapsedRealtime() < soakUntil) {
                main { selector().cabin.performClick() }
                val before = requireNotNull(cabinView()).frames
                await("soak cabin frames") { cabinView()!!.frames > before + 2 }
                SystemClock.sleep(250)
                main { selector().directions[soakSwitches++ % 4].performClick() }
                SystemClock.sleep(250)
                assertNull(error.get()); assertNull(producerError.get()); assertNull(source.error.get())
                assertEquals(1, source.replacements.get()); assertEquals(0, source.stops.get())
            }
            main { selector().cabin.performClick() }
            val view = requireNotNull(cabinView())
            val previous = view.frames
            main { activity.startActivity(Intent(activity, MirrorGlTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
            await("same cabin display moved home") { MirrorPreviewRuntime.evidence.destination == "HOME" && view.frames > previous + 2 }
            assertSame(view, cabinView())
            assertEquals(1, source.replacements.get()); assertEquals(0, source.stops.get())
            // Even an invalid display decoder format cannot fail the real encoder/writer.
            val beforeDisplayFailure = recorder.evidence().frames
            RecordingCabinFeed.find(source.run)!!.format(CabinPreviewFormat("video/openavm-invalid-test", 640, 480, emptyList()))
            await("failed optional decoder retires") { CabinPreviewDecoder.isIdle() }
            await("recording survives decoder failure") { recorder.evidence().frames > beforeDisplayFailure + 15 }
            assertNull(error.get()); assertEquals(0, source.stops.get())
            main { controller!!.selectRecordingSource(RecordingSourceRole.SURROUND) }
            await("surround remains live after cabin display failure") { MirrorPreviewRuntime.evidence.reason == "LIVE" }
            main { controller!!.close() }
            stop.set(true); producer.join(5_000); assertFalse(producer.isAlive)
            recorder.stop(); recorderStopped = true
            source.finish()
            await("display resources released") { controller!!.cleanupConfirmed() && CabinPreviewDecoder.isIdle() && MirrorGlPreview.isIdle() }
            val evidence = recorder.evidence()
            assertNull(error.get()); assertNull(producerError.get()); assertNull(source.error.get())
            assertEquals(produced.get().toLong(), evidence.frames)
            assertEquals(evidence.frames, evidence.writtenFrames)
            assertEquals(1L, evidence.completedFiles)
            val extractor = MediaExtractor()
            var samples = 0L
            try {
                extractor.setDataSource(output.localWorkingFile.absolutePath); extractor.selectTrack(0)
                var last = -1L
                while (extractor.sampleTime >= 0) {
                    assertTrue(extractor.sampleTime > last); last = extractor.sampleTime; samples++; extractor.advance()
                }
            } finally { extractor.release() }
            assertEquals(evidence.frames, samples)
            File(dir, "result.txt").writeText("scope=synthetic_input_real_AVC_MP4_display\n" +
                "elapsedMs=${SystemClock.elapsedRealtime() - start}\nswitches=${38 + soakSwitches}\nframes=$samples\n" +
                "surroundSurfaceReplacements=${source.replacements.get()}\nstopCommands=${source.stops.get()}\n" +
                "files=1\ncleanupConfirmed=true\nvehicleHalTested=false\n")
        } finally {
            main { controller?.close(); activity.finish() }
            stop.set(true); producer?.join(5_000)
            if (!recorderStopped) runCatching { recorder.stop() }
            recorder.release(); output.close(); source.finish()
            await("final decoder cleanup") { CabinPreviewDecoder.isIdle() }
        }
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun screenshot(file: File) {
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            try { file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
            finally { bitmap.recycle() }
        }
    }

    private class SurroundSource : MirrorCameraSource {
        val run = UUID.randomUUID().toString()
        val config = RecorderConfig(SessionSourceSnapshot(RecordingSourceRole.SURROUND, "synthetic-surround",
            CameraFormatProfile(ProfileSize(1280, 5140), 28_000_000), RecordingLayoutKind.FOUR_LANE_V1),
            60, 5L shl 30, mirrorPreviewEnabled = true, sharedInputRecordingEnabled = true)
        override val state = RecorderState(status = RecorderStatus.RECORDING, cameraId = config.cameraId,
            profile = config.profile, sourceRole = RecordingSourceRole.SURROUND, layoutKind = RecordingLayoutKind.FOUR_LANE_V1,
            recordingSessionId = run, cameraGeneration = 1, mirrorPreviewManaged = true, videoFlowConfirmed = true,
            recordingBackend = "SHARED_INPUT_CONTINUOUS_CODEC", segmentNumber = 1)
        val stops = AtomicInteger(); val replacements = AtomicInteger(); val error = AtomicReference<Throwable?>()
        @Volatile var surface: Surface? = null
        private val stopping = AtomicBoolean()
        private var worker: Thread? = null
        private var released: (() -> Unit)? = null
        override fun enable(enabled: Boolean, runId: String, generation: Long) = Unit
        override fun replace(surface: Surface, runId: String, generation: Long, released: () -> Unit) {
            check(this.surface == null); this.surface = surface; this.released = released; replacements.incrementAndGet()
            worker = Thread({
                val gl = PaintProducer(alphaBits = 8)
                try {
                    gl.attach(surface); var frames = 0
                    while (!stopping.get()) { gl.frame(++frames, 320, 1280); Thread.sleep(100) }
                } catch (failure: Throwable) { error.set(failure) }
                finally { runCatching { gl.close() }.onFailure { error.set(it) } }
            }, "test-surround-display").apply { start() }
        }
        override fun stop() { stops.incrementAndGet() }
        override fun bookmark() = Unit
        fun finish() {
            stopping.set(true); worker?.join(5_000); check(worker?.isAlive != true)
            released?.let { surface?.release(); it(); released = null }
        }
    }

    private class FdOutput(override val localWorkingFile: File) : RecordingOutputHandle {
        private var fd: ParcelFileDescriptor? = ParcelFileDescriptor.open(localWorkingFile,
            ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE)
        override val storage = RecordingStorageIdentity(RecordingStorageKind.INTERNAL)
        override val displayName get() = localWorkingFile.name
        override val nativeReleaseConfirmed get() = fd == null
        override fun bind(recorder: MediaRecorder) = error("Expected continuous codec")
        override fun createMuxer() = MediaMuxer(requireNotNull(fd).fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        override fun close() { fd?.close(); fd = null }
    }
}
