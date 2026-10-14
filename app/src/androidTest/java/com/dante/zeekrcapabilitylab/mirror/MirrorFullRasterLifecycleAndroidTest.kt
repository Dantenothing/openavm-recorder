package com.dante.zeekrcapabilitylab.mirror

import android.content.Intent
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Build
import android.os.SystemClock
import android.view.Surface
import androidx.test.platform.app.InstrumentationRegistry
import com.dante.zeekrcapabilitylab.preflight.continuous.ProbeEglNode
import com.dante.zeekrcapabilitylab.preflight.continuous.ProbeGlDraw
import com.dante.zeekrcapabilitylab.probe.camera.CameraFormatProfile
import com.dante.zeekrcapabilitylab.probe.camera.ProfileSize
import com.dante.zeekrcapabilitylab.product.SettingsStore
import com.dante.zeekrcapabilitylab.recorder.PaintProducer
import com.dante.zeekrcapabilitylab.service.CameraRecordingService
import com.dante.zeekrcapabilitylab.service.recorder.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Full production preview graph, synthetic EGL input and a draining encoder Surface.
 * Deliberately does not claim Camera2, AVC, USB or vehicle sleep coverage. */
class MirrorFullRasterLifecycleAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val size = ProfileSize(1280, 5140)
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(message: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 15_000
        while (!condition() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(50)
        assertTrue("$message; ${MirrorPreviewRuntime.evidence}", condition())
    }

    @Test fun fullRasterDirectPreviewSurvivesHomeOverlayMovesAndLateProducerRelease() = runPreview(false)

    @Test fun fullRasterDirectPreviewStartsInCorrectedRearOverlayAndKeepsFrames() = runPreview(false, true)

    @Test fun fullRasterSharedInputAndRecordingDisplaySurviveWindowMovesAndStop() = runPreview(true)

    private fun runPreview(shared: Boolean, initialRearOverlay: Boolean = false) {
        assumeTrue("Disposable emulator only", Build.HARDWARE == "ranchu")
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit full-raster graphics test", args.getString("fullRasterPreview") == "true")
        val seconds = args.getString("previewSoakSeconds")?.toIntOrNull()?.coerceIn(0, 180) ?: 0
        val moves = args.getString("previewWindowMoves")?.toIntOrNull()?.coerceIn(2, 20) ?: 2
        val output = File(context.filesDir, "stability-preview/${UUID.randomUUID()}").apply { mkdirs() }
        val activity = instrumentation.startActivitySync(Intent(context, MirrorGlTestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MirrorGlTestActivity
        var controller: MirrorPreviewController? = null
        val source = SyntheticSource(shared)
        try {
            main {
                SettingsStore.get(context).setMirrorPreviewEnabled(true)
                MirrorPresentation(context).apply { selectedLane = 1; grid = true; videoVisible = true }
                if (initialRearOverlay) MirrorPresentation(context).apply {
                    selectedLane = 2; grid = false; triple = false
                    setLens(2, com.dante.zeekrcapabilitylab.product.FourLaneLensMode.STANDARD)
                }
                activity.setContentView(MirrorHomeHost(activity))
                if (initialRearOverlay) activity.moveTaskToBack(true)
            }
            if (initialRearOverlay) SystemClock.sleep(500)
            source.start()
            main {
                controller = MirrorPreviewController(context, source.config, source, source.runId, 1,
                    declaredSizeLookup = { size })
            }
            await("full-size initial preview displays fresh frames") {
                MirrorPreviewRuntime.evidence.let { it.destination == (if (initialRearOverlay) "OVERLAY" else "HOME") && it.reason == "LIVE" && it.frames > 3 }
            }
            if (initialRearOverlay) repeat(12) {
                val before = MirrorPreviewRuntime.evidence.frames
                SystemClock.sleep(1_000)
                source.assertHealthy()
                assertTrue("The corrected rear view keeps consuming after its first frame", MirrorPreviewRuntime.evidence.frames > before)
            }
            val initialSurface = source.previewSurface
            assertNotNull(initialSurface)
            assertEquals(size.width, MirrorPreviewRuntime.evidence.bufferWidth)
            assertEquals(size.height, MirrorPreviewRuntime.evidence.bufferHeight)
            repeat(moves) { cycle ->
                var before = MirrorPreviewRuntime.evidence.frames
                main { activity.moveTaskToBack(true) }
                await("full-size overlay has new frames at move $cycle") {
                    MirrorPreviewRuntime.evidence.let { it.destination == "OVERLAY" && it.reason == "LIVE" && it.frames > before + 2 }
                }
                before = MirrorPreviewRuntime.evidence.frames
                main { activity.startActivity(Intent(activity, MirrorGlTestActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
                await("full-size home has new frames at move $cycle") {
                    MirrorPreviewRuntime.evidence.let { it.destination == "HOME" && it.reason == "LIVE" && it.frames > before + 2 }
                }
                assertSame("Window moves must retain the producer-facing Surface", initialSurface, source.previewSurface)
                assertEquals("One initial preview insertion", 1, source.replacements.get())
                source.assertHealthy()
                File(output, "moves.tsv").appendText("$cycle\t${MirrorPreviewRuntime.evidence}\n")
            }
            val until = SystemClock.elapsedRealtime() + seconds * 1_000
            while (SystemClock.elapsedRealtime() < until) {
                val before = MirrorPreviewRuntime.evidence.frames
                SystemClock.sleep(1_000)
                source.assertHealthy()
                await("continuing full-size preview") { MirrorPreviewRuntime.evidence.frames > before && MirrorPreviewRuntime.evidence.reason == "LIVE" }
            }
            main { controller!!.close() }
            assertFalse("View removal is not producer-release evidence", controller!!.cleanupConfirmed())
            source.finishProducer()
            // Actual native producer is gone, but hold its notification to exercise the late-ACK path.
            SystemClock.sleep(300)
            assertFalse("Native handles remain reserved until the callback", controller!!.cleanupConfirmed())
            source.deliverRelease()
            await("late native completion retires the preview") { controller!!.cleanupConfirmed() && MirrorGlPreview.isIdle() }
            assertFalse("Synthetic preview cannot start a recorder service", CameraRecordingService.isRunning())
            File(output, "result.txt").writeText("scope=SYNTHETIC_FULL_RASTER_PREVIEW_ONLY\n" +
                "sharedInput=$shared\ninput=1280x5140\nencoderConsumer=${if (shared) "3840x1728_SURFACETEXTURE_NO_AVC" else "NONE"}\n" +
                "windowMoves=$moves\nsoakSeconds=$seconds\nproducedFrames=${source.produced.get()}\n" +
                "cleanupConfirmed=true\ncamera2=false\nusb=false\n")
        } finally {
            main { controller?.close(); activity.finish() }
            source.finishProducer()
            source.deliverRelease()
            if (controller != null) await("final full-raster cleanup") { controller!!.cleanupConfirmed() && MirrorGlPreview.isIdle() }
            source.closeSink()
        }
    }

    private inner class SyntheticSource(private val shared: Boolean) : MirrorCameraSource {
        val runId = UUID.randomUUID().toString()
        val config = RecorderConfig(SessionSourceSnapshot(RecordingSourceRole.SURROUND, "synthetic-only",
            CameraFormatProfile(size, 28_000_000), RecordingLayoutKind.FOUR_LANE_V1),
            60, 5L * 1024 * 1024 * 1024, mirrorPreviewEnabled = true, sharedInputRecordingEnabled = shared)
        override val previewOnly = !shared
        override val state = RecorderState(status = if (shared) RecorderStatus.RECORDING else RecorderStatus.PREVIEWING,
            cameraId = config.cameraId, profile = config.profile, sourceRole = RecordingSourceRole.SURROUND,
            layoutKind = RecordingLayoutKind.FOUR_LANE_V1, mirrorPreviewManaged = true, recordingSessionId = runId,
            cameraGeneration = 1, segmentNumber = 1, recordingBackend = if (shared) "SHARED_INPUT_CONTINUOUS_CODEC" else "SYNTHETIC_PREVIEW")
        val error = AtomicReference<Throwable?>()
        val produced = AtomicInteger()
        val replacements = AtomicInteger()
        @Volatile var previewSurface: Surface? = null
            private set
        @Volatile private var enabled = true
        @Volatile private var stopping = false
        private var finished = false
        private val callbacks = mutableListOf<() -> Unit>()
        private var sink: TextureSink? = null
        private var input: ContinuousCameraInput? = null
        private var worker: Thread? = null
        private var directRelease: (() -> Unit)? = null

        fun start() {
            if (shared) {
                sink = TextureSink()
                input = ContinuousCameraInput(sink!!.surface,
                    { failure, _ -> error.compareAndSet(null, failure) },
                    { error.compareAndSet(null, IllegalStateException(it)) }).also { it.initialize() }
            }
            worker = Thread({
                val producer = PaintProducer(alphaBits = 8)
                try {
                    while (!stopping && input == null && previewSurface == null) Thread.sleep(10)
                    if (!stopping) {
                        producer.attach(input?.surface ?: requireNotNull(previewSurface))
                        while (!stopping) {
                            if (shared || enabled) producer.frame(produced.incrementAndGet(), size.width, size.height)
                            Thread.sleep(100)
                        }
                    }
                } catch (failure: Throwable) { error.compareAndSet(null, failure) }
                finally { runCatching { producer.close() }.onFailure { error.compareAndSet(null, it) } }
            }, "stability-mirror-producer").apply { start() }
        }
        override fun enable(enabled: Boolean, runId: String, generation: Long) {
            check(runId == this.runId && generation == 1L)
            this.enabled = enabled
            input?.enablePreview(enabled)
        }
        override fun replace(surface: Surface, runId: String, generation: Long, released: () -> Unit) {
            check(runId == this.runId && generation == 1L && previewSurface == null)
            replacements.incrementAndGet(); previewSurface = surface
            if (shared) {
                input!!.setPreview(surface) { value -> value.release(); synchronized(callbacks) { callbacks += released } }
                input!!.enablePreview(true)
            } else directRelease = { surface.release(); synchronized(callbacks) { callbacks += released } }
        }
        override fun stop() { error.compareAndSet(null, IllegalStateException("UNEXPECTED_PREVIEW_STOP")) }
        override fun bookmark() = Unit
        fun assertHealthy() { error.get()?.let { throw AssertionError("Synthetic producer failed", it) }; sink?.error?.get()?.let { throw it } }
        fun finishProducer() {
            if (finished) return
            stopping = true; worker?.join(10_000)
            check(worker?.isAlive != true) { "SYNTHETIC_PRODUCER_NOT_RELEASED" }
            input?.stopAfterCameraFence()
            directRelease?.invoke(); directRelease = null
            finished = true
            assertHealthy()
        }
        fun deliverRelease() {
            val pending = synchronized(callbacks) { callbacks.toList().also { callbacks.clear() } }
            pending.forEach { it() }
        }
        fun closeSink() { sink?.close() }
    }

    private class TextureSink {
        val error = AtomicReference<Throwable?>()
        private val ready = CountDownLatch(1)
        @Volatile private var stopping = false
        @Volatile private var output: Surface? = null
        private val worker = Thread({
            val node = ProbeEglNode(); val ids = IntArray(1); var texture: SurfaceTexture? = null
            try {
                node.initialize()
                GLES20.glGenTextures(1, ids, 0); GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
                ProbeGlDraw.textureParameters(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
                texture = SurfaceTexture(ids[0]).apply { setDefaultBufferSize(3840, 1728) }
                output = Surface(texture); ready.countDown()
                while (!stopping) { texture.updateTexImage(); Thread.sleep(5) }
            } catch (failure: Throwable) { error.compareAndSet(null, failure) }
            finally {
                ready.countDown()
                runCatching { node.finish(); output?.release(); texture?.release()
                    if (node.usable) GLES20.glDeleteTextures(1, ids, 0)
                    node.close() }.onFailure { error.compareAndSet(null, it) }
            }
        }, "stability-mirror-encoder-sink").apply { start() }
        val surface: Surface get() { check(ready.await(5, TimeUnit.SECONDS)); error.get()?.let { throw it }; return requireNotNull(output) }
        fun close() { stopping = true; worker.join(5_000); check(!worker.isAlive); error.get()?.let { throw it } }
    }
}
