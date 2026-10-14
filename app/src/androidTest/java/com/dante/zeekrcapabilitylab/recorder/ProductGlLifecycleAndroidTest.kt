package com.dante.zeekrcapabilitylab.recorder

import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.dante.zeekrcapabilitylab.service.recorder.ContinuousCameraInput
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Full-size product GL input/pool/encoder/display branches. ImageReader is NOT an AVC encoder. */
class ProductGlLifecycleAndroidTest {
    @Test fun fullSizeInputAndBothConsumersReleaseAcrossThreeLifetimes() {
        assumeTrue("Developer emulator only", Build.HARDWARE == "ranchu")
        assumeTrue("Explicit full-raster GL trial",
            InstrumentationRegistry.getArguments().getString("stabilityProductRaster") == "true")
        val control = InstrumentationRegistry.getArguments().getString("glPlatformControl") == "true"
        val cycles = InstrumentationRegistry.getArguments().getString("glLifetimes")?.toIntOrNull()?.coerceIn(3, 12) ?: 3
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            "stability-product/gl-${if (control) "control" else "product"}-${SystemClock.elapsedRealtime()}").apply { mkdirs() }
        val resources = File(directory, "resources.tsv")
        repeat(cycles) { cycle ->
            val worker = HandlerThread("stability-image-observer").apply { start() }
            val error = AtomicReference<String?>()
            val encoderFrames = AtomicInteger(); val displayFrames = AtomicInteger()
            val encoderLit = AtomicInteger(); val displayLit = AtomicInteger()
            fun sink(width: Int, height: Int, frames: AtomicInteger, lit: AtomicInteger): ImageReader {
                return ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3).apply {
                    setOnImageAvailableListener({ source ->
                        try {
                            source.acquireLatestImage()?.use { image ->
                                val plane = image.planes[0]
                                val at = (height / 2) * plane.rowStride + (width / 2) * plane.pixelStride
                                val rgb = (0..2).sumOf { plane.buffer.get(at + it).toInt() and 255 }
                                if (rgb > 10) lit.incrementAndGet()
                                frames.incrementAndGet()
                            }
                        } catch (failure: Throwable) { error.compareAndSet(null, "IMAGE_SINK:${failure.javaClass.simpleName}") }
                    }, Handler(worker.looper))
                }
            }
            val encoded = sink(3840, 1728, encoderFrames, encoderLit)
            val displayed = sink(1280, 5140, displayFrames, displayLit)
            val previewReleased = CountDownLatch(1)
            val input = if (control) null else ContinuousCameraInput(encoded.surface,
                { failure, stage -> error.compareAndSet(null, "$stage:${failure.message}") },
                { message -> error.compareAndSet(null, message) })
            val producer = PaintProducer(alphaBits = if (control) 8 else 0)
            var released = false
            try {
                if (input == null) {
                    // Platform-only comparison: same EGL producer and image consumers, no OpenAVM GL path.
                    producer.attach(encoded.surface)
                    repeat(20) { producer.frame(it, 3840, 1728); Thread.sleep(100) }
                    producer.close(); producer.attach(displayed.surface)
                    repeat(20) { producer.frame(it, 1280, 5140); Thread.sleep(100) }
                } else {
                    input.initialize(); input.setPreview(displayed.surface) { previewReleased.countDown() }
                    input.enablePreview(true); input.startCaptureWatchdog(); producer.attach(input.surface)
                    repeat(20) { frame ->
                        error.get()?.let { error(it) }
                        producer.frame(frame, 1280, 5140); Thread.sleep(100)
                    }
                }
                val until = SystemClock.elapsedRealtime() + 3_000
                while ((encoderFrames.get() < 10 || displayFrames.get() < 10) && SystemClock.elapsedRealtime() < until) Thread.sleep(20)
                input?.stopCaptureWatchdog(); producer.close() // Synthetic producer fence precedes GL release.
                input?.stopAfterCameraFence(); released = true
                assertNull(error.get())
                assertTrue(encoderFrames.get() >= 10); assertTrue(displayFrames.get() >= 10)
                assertTrue(encoderLit.get() >= 10); assertTrue(displayLit.get() >= 10)
                if (input != null) {
                    assertTrue(previewReleased.await(1, TimeUnit.SECONDS)); assertTrue(input.released())
                }
                File(directory, "frames.tsv").appendText("$cycle\t${input?.submittedFrames}\t${encoderFrames.get()}\t${displayFrames.get()}\n")
            } finally {
                input?.stopCaptureWatchdog(); producer.close()
                if (!released) input?.stopAfterCameraFence()
                encoded.close(); displayed.close(); worker.quitSafely(); worker.join(1_000)
            }
            assertTrue(Thread.getAllStackTraces().keys.none {
                it.isAlive && it.name in setOf("recording-shared-input", "recording-encoder", "recording-display_a", "stability-image-observer")
            })
            resources.appendText(listOf(cycle, File("/proc/self/fd").list()?.size, File("/proc/self/task").list()?.size,
                android.os.Debug.getNativeHeapAllocatedSize()).joinToString("\t") + "\n")
            File(directory, "fds-$cycle.tsv").writeText(File("/proc/self/fd").list().orEmpty().mapNotNull { fd ->
                runCatching { "$fd\t${android.system.Os.readlink("/proc/self/fd/$fd")}" }.getOrNull()
            }.sorted().joinToString("\n"))
        }
        File(directory, "result.txt").writeText("scope=${if (control) "PLATFORM_EGL_IMAGEREADER_CONTROL" else "PRODUCT_GL_ONLY_NOT_AVC_OR_CAMERA2"}\n" +
            "input=1280x5140\nencoderSink=3840x1728_RGBA_IMAGE_READER\npreviewSink=1280x5140_RGBA_IMAGE_READER\nlifetimes=$cycles\n")
    }
}
