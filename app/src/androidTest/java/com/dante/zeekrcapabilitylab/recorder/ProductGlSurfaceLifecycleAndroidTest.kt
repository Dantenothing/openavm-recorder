package com.dante.zeekrcapabilitylab.recorder

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.os.Build
import android.os.SystemClock
import android.view.Surface
import androidx.test.platform.app.InstrumentationRegistry
import com.dante.zeekrcapabilitylab.preflight.continuous.ProbeEglNode
import com.dante.zeekrcapabilitylab.preflight.continuous.ProbeGlDraw
import com.dante.zeekrcapabilitylab.service.recorder.ContinuousCameraInput
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** A second full-raster GL fixture without CPU ImageReader buffers or their acquire fences.
 * It observes SurfaceTexture frames, not AVC output or a car Camera2 implementation. */
class ProductGlSurfaceLifecycleAndroidTest {
    @Test fun failedInputWaitsForProducerAcknowledgementWithoutPolling() {
        assumeTrue("Disposable emulator only", Build.HARDWARE == "ranchu")
        assumeTrue("Explicit full-raster trial", InstrumentationRegistry.getArguments().getString("stabilityProductRaster") == "true")
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            "stability-product/failed-input-${SystemClock.elapsedRealtime()}").apply { mkdirs() }
        val encoder = TextureSink(3840, 1728, "stability-texture-encoder")
        val failure = AtomicReference<String?>()
        val failed = CountDownLatch(1)
        val inputTid = AtomicInteger()
        val input = ContinuousCameraInput(encoder.surface, { error, stage ->
            inputTid.set(android.os.Process.myTid())
            failure.set("$stage:${error.message}")
            failed.countDown()
        }, { failure.compareAndSet(null, it) })
        val producer = PaintProducer()
        fun switches(): Long = File("/proc/self/task/${inputTid.get()}/status").readLines()
            .first { it.startsWith("voluntary_ctxt_switches:") }.substringAfter(':').trim().toLong()
        try {
            input.initialize(); producer.attach(input.surface)
            producer.frame(1, 1280, 5140, timestampNs = 2_000_000_000L)
            val deadline = SystemClock.elapsedRealtime() + 4_000
            while (input.submittedFrames < 1 && SystemClock.elapsedRealtime() < deadline) Thread.sleep(10)
            assertTrue("First frame passed through the real GL graph", input.submittedFrames >= 1)
            producer.frame(2, 1280, 5140, timestampNs = 1_000_000_000L)
            assertTrue("Real timestamp regression fails input", failed.await(4, TimeUnit.SECONDS))
            assertEquals("INPUT:PRODUCT_SOURCE_TIMESTAMP_REGRESSED", failure.get())
            Thread.sleep(100)
            val before = switches()
            Thread.sleep(600)
            val after = switches()
            File(directory, "result.txt").writeText("scope=ACTUAL_PRODUCT_GL_SYNTHETIC_TIMESTAMP_FAULT\n" +
                "failure=${failure.get()}\ninputTid=${inputTid.get()}\nwaitMs=600\n" +
                "voluntaryContextSwitches=${after - before}\nreleasedBeforeProducerAck=${input.released()}\n")
            assertFalse("Failure must keep the producer-owned input reserved", input.released())
            assertTrue("Producer Surface must survive the delayed acknowledgement", input.surface.isValid)
            assertTrue("Failed input must sleep on its producer fence, not wake every 2 ms; switches=${after - before}", after - before <= 5)
        } finally {
            producer.close()
            input.stopAfterCameraFence()
            assertTrue("Late producer acknowledgement still completes cleanup", input.released())
            encoder.close()
        }
    }

    @Test fun bareEglThreadsProvideAPlatformResourceControl() {
        assumeTrue("Disposable emulator only", Build.HARDWARE == "ranchu")
        assumeTrue("Explicit context-only comparison", InstrumentationRegistry.getArguments().getString("glContextControl") == "true")
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            "stability-product/bare-egl-${SystemClock.elapsedRealtime()}").apply { mkdirs() }
        repeat(20) { cycle ->
            val error = AtomicReference<Throwable?>()
            val worker = Thread({
                try {
                    val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                    val version = IntArray(2)
                    check(EGL14.eglInitialize(display, version, 0, version, 1))
                    val configs = arrayOfNulls<EGLConfig>(1); val count = IntArray(1)
                    check(EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                        EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_NONE), 0, configs, 0, 1, count, 0))
                    check(count[0] > 0)
                    val context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                        intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
                    check(context != EGL14.EGL_NO_CONTEXT)
                    val surface = EGL14.eglCreatePbufferSurface(display, configs[0],
                        intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
                    check(surface != EGL14.EGL_NO_SURFACE)
                    check(EGL14.eglMakeCurrent(display, surface, surface, context))
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT); GLES20.glFinish()
                    check(GLES20.glGetError() == GLES20.GL_NO_ERROR)
                    check(EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT))
                    check(EGL14.eglDestroySurface(display, surface)); check(EGL14.eglDestroyContext(display, context))
                    check(EGL14.eglTerminate(display)); check(EGL14.eglReleaseThread())
                } catch (failure: Throwable) { error.set(failure) }
            }, "stability-bare-egl").apply { start() }
            worker.join(4_000); assertFalse(worker.isAlive); assertNull(error.get())
            File(directory, "resources.tsv").appendText(listOf(cycle, File("/proc/self/fd").list()?.size,
                File("/proc/self/task").list()?.size, android.os.Debug.getNativeHeapAllocatedSize()).joinToString("\t") + "\n")
            File(directory, "fds-$cycle.tsv").writeText(File("/proc/self/fd").list().orEmpty().mapNotNull { fd ->
                runCatching { "$fd\t${android.system.Os.readlink("/proc/self/fd/$fd")}" }.getOrNull()
            }.sorted().joinToString("\n"))
        }
        File(directory, "result.txt").writeText("scope=ANDROID_EGL_APIS_ONLY_NO_OPENAVM_GRAPHICS\n" +
            "lifetimes=20\npbuffer=1x1\nallEglCloseCalls=TRUE\n")
    }

    @Test fun fullRasterSurfaceConsumersReleaseAcrossRepeatedLifetimes() {
        assumeTrue("Disposable emulator only", Build.HARDWARE == "ranchu")
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit full-raster trial", args.getString("stabilityProductRaster") == "true")
        val control = args.getString("glPlatformControl") == "true"
        val cycles = args.getString("glLifetimes")?.toIntOrNull()?.coerceIn(3, 20) ?: 6
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            "stability-product/surface-${if (control) "control" else "product"}-${SystemClock.elapsedRealtime()}").apply { mkdirs() }
        repeat(cycles) { cycle ->
            val encoder = TextureSink(3840, 1728, "stability-texture-encoder")
            val display = TextureSink(1280, 5140, "stability-texture-display")
            val errors = AtomicReference<String?>()
            val previewReleased = CountDownLatch(1)
            val input = if (control) null else ContinuousCameraInput(encoder.surface,
                { error, stage -> errors.compareAndSet(null, "$stage:${error.message}") },
                { errors.compareAndSet(null, it) })
            val producer = PaintProducer()
            var released = false
            try {
                if (input == null) {
                    producer.attach(encoder.surface)
                    repeat(20) { producer.frame(it, 3840, 1728); Thread.sleep(50) }
                    producer.close(); producer.attach(display.surface)
                    repeat(20) { producer.frame(it, 1280, 5140); Thread.sleep(50) }
                } else {
                    input.initialize(); input.setPreview(display.surface) { previewReleased.countDown() }
                    input.enablePreview(true); input.startCaptureWatchdog(); producer.attach(input.surface)
                    repeat(20) { producer.frame(it, 1280, 5140); Thread.sleep(100) }
                }
                producer.close(); input?.stopCaptureWatchdog(); input?.stopAfterCameraFence(); released = true
                assertNull(errors.get()); assertNull(encoder.error.get()); assertNull(display.error.get())
                assertTrue("encoder frames=${encoder.frames.get()}", encoder.frames.get() >= 10)
                assertTrue("display frames=${display.frames.get()}", display.frames.get() >= 10)
                if (input != null) assertTrue(previewReleased.await(1, TimeUnit.SECONDS) && input.released())
                File(directory, "frames.tsv").appendText("$cycle\t${input?.submittedFrames}\t${encoder.frames.get()}\t${display.frames.get()}\n")
            } finally {
                producer.close(); input?.stopCaptureWatchdog()
                if (!released) input?.stopAfterCameraFence()
                encoder.close(); display.close()
            }
            assertTrue(Thread.getAllStackTraces().keys.none {
                it.isAlive && it.name in setOf("recording-shared-input", "recording-encoder", "recording-display_a",
                    "stability-texture-encoder", "stability-texture-display")
            })
            File(directory, "resources.tsv").appendText(listOf(cycle, File("/proc/self/fd").list()?.size,
                File("/proc/self/task").list()?.size, android.os.Debug.getNativeHeapAllocatedSize()).joinToString("\t") + "\n")
            File(directory, "fds-$cycle.tsv").writeText(File("/proc/self/fd").list().orEmpty().mapNotNull { fd ->
                runCatching { "$fd\t${android.system.Os.readlink("/proc/self/fd/$fd")}" }.getOrNull()
            }.sorted().joinToString("\n"))
        }
        File(directory, "result.txt").writeText("scope=${if (control) "SURFACE_CONSUMER_CONTROL" else "PRODUCT_GL_SURFACE_CONSUMERS"}\n" +
            "avc=NOT_TESTED\ncamera2=NOT_TESTED\ninput=1280x5140\nencoder=3840x1728\n" +
            "sharedEglWrapper=ProbeEglNode\nlifetimes=$cycles\n")
    }

    private class TextureSink(width: Int, height: Int, name: String) {
        val frames = AtomicInteger()
        val error = AtomicReference<Throwable?>()
        private val ready = CountDownLatch(1)
        private val ended = CountDownLatch(1)
        @Volatile private var stopping = false
        @Volatile private var output: Surface? = null
        private val worker = Thread({
            val node = ProbeEglNode()
            val id = IntArray(1)
            var texture: SurfaceTexture? = null
            try {
                node.initialize()
                GLES20.glGenTextures(1, id, 0); GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id[0])
                ProbeGlDraw.textureParameters(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
                texture = SurfaceTexture(id[0]).apply { setDefaultBufferSize(width, height) }
                output = Surface(texture); ready.countDown()
                var previous = 0L
                while (!stopping) {
                    texture.updateTexImage()
                    val timestamp = texture.timestamp
                    if (timestamp > previous) { previous = timestamp; frames.incrementAndGet() }
                    Thread.sleep(2)
                }
            } catch (failure: Throwable) { error.compareAndSet(null, failure) }
            finally {
                ready.countDown()
                try {
                    node.finish(); output?.release(); output = null; texture?.release()
                    if (node.usable) GLES20.glDeleteTextures(1, id, 0)
                    node.close()
                } catch (failure: Throwable) { error.compareAndSet(null, failure) }
                ended.countDown()
            }
        }, name).apply { start() }
        val surface: Surface get() {
            check(ready.await(4, TimeUnit.SECONDS))
            error.get()?.let { throw it }
            return requireNotNull(output)
        }
        /** Called only after all EGL producers have detached from this Surface. */
        fun close() {
            stopping = true; check(ended.await(4, TimeUnit.SECONDS)); worker.join(500)
            check(!worker.isAlive); error.get()?.let { throw it }
        }
    }
}
