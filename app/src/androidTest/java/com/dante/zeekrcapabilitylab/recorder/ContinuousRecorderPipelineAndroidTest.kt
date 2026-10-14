package com.dante.zeekrcapabilitylab.recorder

import android.media.*
import android.opengl.*
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.Surface
import androidx.test.platform.app.InstrumentationRegistry
import com.dante.zeekrcapabilitylab.probe.camera.CameraFormatProfile
import com.dante.zeekrcapabilitylab.probe.camera.ProfileSize
import com.dante.zeekrcapabilitylab.service.recorder.*
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference

/** Real production codec/muxer, synthetic EGL producer. Never opens a camera or uses vehicle data. */
class ContinuousRecorderPipelineAndroidTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var directory: File
    private val config = RecorderConfig(SessionSourceSnapshot(RecordingSourceRole.CABIN, "synthetic",
        CameraFormatProfile(ProfileSize(640, 480), 2_000_000), RecordingLayoutKind.SINGLE_V1), 60, 5L shl 30)

    @Before fun emulatorOnly() {
        assumeTrue("Developer emulator only", Build.HARDWARE == "ranchu")
        directory = File(context.filesDir, "stability-pipeline/${UUID.randomUUID()}").apply { mkdirs() }
    }

    @Test fun stopBeforePendingCutSavesTailInCurrentFileAndReleasesUnusedDestination() {
        val error = AtomicReference<String?>()
        val unused = CopyOnWriteArrayList<RecordingOutputHandle>()
        val recorder = newRecorder(error, unused)
        val first = FdOutput(File(directory, "cancel-first.mp4"))
        val next = FdOutput(File(directory, "cancel-unused.mp4"))
        val producer = PaintProducer()
        var stopped = false
        try {
            recorder.prepare(config, first); recorder.start(); producer.attach(recorder.surface)
            repeat(20) { producer.frame(it, 640, 480); Thread.sleep(34) }
            await { recorder.evidence().frames == 20L }
            recorder.requestCut(next)
            recorder.prepareForStop() // No post-cut frame has been supplied yet.
            repeat(15) { producer.frame(it + 20, 640, 480); Thread.sleep(34) }
            producer.close(); recorder.stop(); stopped = true
            assertNull(error.get())
            assertEquals(35L, recorder.evidence().frames)
            assertEquals(35L, recorder.evidence().writtenFrames)
            assertEquals(35L, sampleCount(first.localWorkingFile))
            assertEquals(listOf(next), unused)
            assertTrue(next.nativeReleaseConfirmed)
            assertEquals(1L, recorder.evidence().completedFiles)
        } finally {
            producer.close()
            if (!stopped) runCatching { recorder.stop() }
            recorder.release(); first.close(); next.close()
        }
        assertEquals(0L, recorder.evidence().queueBytes)
        assertEquals(0, recorder.evidence().queueItems)
    }

    @Test fun stopWhileOldDescriptorIsClosingStillWritesTheCommittedNextFile() {
        val error = AtomicReference<String?>()
        val recorder = newRecorder(error)
        val closeEntered = CountDownLatch(1)
        val allowClose = CountDownLatch(1)
        val first = FdOutput(File(directory, "blocked-close-first.mp4")) {
            closeEntered.countDown(); check(allowClose.await(6, TimeUnit.SECONDS))
        }
        val next = FdOutput(File(directory, "blocked-close-next.mp4"))
        val producer = PaintProducer()
        val closer = Executors.newSingleThreadExecutor()
        var stopped = false
        try {
            recorder.prepare(config, first); recorder.start(); producer.attach(recorder.surface)
            repeat(20) { producer.frame(it, 640, 480); Thread.sleep(34) }
            await { recorder.evidence().frames == 20L }
            recorder.requestCut(next)
            repeat(15) { producer.frame(it + 20, 640, 480); Thread.sleep(34) }
            assertTrue(closeEntered.await(4, TimeUnit.SECONDS))
            producer.close()
            recorder.prepareForStop()
            val result = closer.submit { recorder.stop() }
            Thread.sleep(100) // Stop is allowed to overlap the real writer's descriptor close.
            allowClose.countDown(); result.get(5, TimeUnit.SECONDS); stopped = true
            assertNull(error.get())
            assertEquals(35L, recorder.evidence().frames)
            assertEquals(35L, recorder.evidence().writtenFrames)
            assertEquals(35L, sampleCount(first.localWorkingFile) + sampleCount(next.localWorkingFile))
            assertEquals(2L, recorder.evidence().completedFiles)
        } finally {
            allowClose.countDown(); producer.close()
            closer.shutdown(); assertTrue(closer.awaitTermination(6, TimeUnit.SECONDS))
            if (!stopped) runCatching { recorder.stop() }
            recorder.release(); first.close(); next.close()
        }
    }

    @Test fun realCodecAndFdMuxerKeepAllSamplesAcrossRepeatedCuts() = runCuts(1_500, 5)

    @Test fun changedFormatBeforeIdrWaitsForPreparedFileAndPreservesTheRealGap() {
        val errors = AtomicReference<String?>()
        val requested = CountDownLatch(1)
        val files = CopyOnWriteArrayList<EncodedSegmentSummary>()
        val codec = requireNotNull(MediaCodecList(MediaCodecList.REGULAR_CODECS)
            .findEncoderForFormat(ContinuousVideoRecorder.format(config)))
        val recorder = ContinuousVideoRecorder(codec, { _, e -> errors.set(e) }, onClosed = { _, s -> files += s },
            onFormatBoundaryNeeded = { requested.countDown() })
        val first = FdOutput(File(directory, "format-old.mp4"))
        val next = FdOutput(File(directory, "format-new.mp4"))
        val producer = PaintProducer()
        var stopped = false
        try {
            recorder.prepare(config, first); recorder.start(); producer.attach(recorder.surface)
            repeat(20) { producer.frame(it, 640, 480); Thread.sleep(34) }
            await { recorder.evidence().writtenFrames == 20L }
            injectSameLayoutFormat(recorder)
            assertTrue(requested.await(1, TimeUnit.SECONDS))
            // Destination is deliberately delayed. Dependent frames must not hit the old muxer.
            repeat(12) { producer.frame(20 + it, 640, 480); Thread.sleep(34) }
            assertNull(errors.get())
            assertEquals(20L, recorder.evidence().frames)
            recorder.requestCut(next)
            repeat(30) { producer.frame(32 + it, 640, 480); Thread.sleep(34) }
            await { recorder.evidence().completedFiles == 1L && !recorder.formatBoundaryNeeded }
            producer.close(); recorder.stop(); stopped = true
            assertNull(errors.get()); assertEquals(2, files.size)
            val evidence = recorder.evidence()
            assertEquals(evidence.frames, evidence.writtenFrames)
            assertEquals(evidence.frames, sampleCount(first.localWorkingFile) + sampleCount(next.localWorkingFile))
            assertTrue("Transition samples cannot be silently counted as saved", evidence.frames < 62)
            val before = requireNotNull(files[0].continuousTimeline)
            val after = requireNotNull(files[1].continuousTimeline)
            assertTrue("No stretched last frame across the discarded interval", after.firstPtsUs - before.endExclusivePtsUs > 250_000)
            assertEquals(0, evidence.queueItems)
            File(directory, "format-result.txt").writeText("input=synthetic-EGL\nformat=injected-same-layout-colour\n" +
                "saved=${evidence.frames}\nfiles=2\ngapUs=${after.firstPtsUs - before.endExclusivePtsUs}\n")
        } finally {
            producer.close(); if (!stopped) runCatching { recorder.stop() }
            recorder.release(); first.close(); next.close()
        }
    }

    @Test fun stopWhileChangedFormatWaitsRetainsOnlyTheDecodableOldFile() {
        val errors = AtomicReference<String?>()
        val files = CopyOnWriteArrayList<EncodedSegmentSummary>()
        val codec = requireNotNull(MediaCodecList(MediaCodecList.REGULAR_CODECS)
            .findEncoderForFormat(ContinuousVideoRecorder.format(config)))
        val recorder = ContinuousVideoRecorder(codec, { _, e -> errors.set(e) }, onClosed = { _, s -> files += s })
        val output = FdOutput(File(directory, "format-stop.mp4"))
        val producer = PaintProducer()
        var stopped = false
        try {
            recorder.prepare(config, output); recorder.start(); producer.attach(recorder.surface)
            repeat(20) { producer.frame(it, 640, 480); Thread.sleep(34) }
            await { recorder.evidence().writtenFrames == 20L }
            injectSameLayoutFormat(recorder)
            repeat(10) { producer.frame(20 + it, 640, 480); Thread.sleep(34) }
            recorder.prepareForStop(); producer.close(); recorder.stop(); stopped = true
            assertNull(errors.get()); assertEquals(1, files.size)
            assertEquals(20L, sampleCount(output.localWorkingFile))
            assertEquals(20L, recorder.evidence().writtenFrames)
            assertFalse(recorder.formatBoundaryNeeded)
        } finally {
            producer.close(); if (!stopped) runCatching { recorder.stop() }
            recorder.release(); output.close()
        }
    }

    /** Feed a valid format transition into the real codec-worker boundary, without a release test hook. */
    internal fun injectSameLayoutFormat(recorder: ContinuousVideoRecorder) {
        val handler = ContinuousVideoRecorder::class.java.getDeclaredField("codecHandler").apply { isAccessible = true }
            .get(recorder) as android.os.Handler
        val done = CountDownLatch(1)
        val failed = AtomicReference<Throwable?>()
        handler.post {
            try {
                val active = ContinuousVideoRecorder::class.java.getDeclaredField("codec").apply { isAccessible = true }
                    .get(recorder) as MediaCodec
                val format = ContinuousTrackFormatAndroid.copyForMuxer(active.outputFormat).apply {
                    val current = if (containsKey(MediaFormat.KEY_COLOR_STANDARD)) getInteger(MediaFormat.KEY_COLOR_STANDARD) else 0
                    setInteger(MediaFormat.KEY_COLOR_STANDARD, if (current == MediaFormat.COLOR_STANDARD_BT709)
                        MediaFormat.COLOR_STANDARD_BT601_NTSC else MediaFormat.COLOR_STANDARD_BT709)
                    // A valid extra CSD trailing_zero_8bits also exercises an SPS byte-length change.
                    val csd = requireNotNull(getByteBuffer("csd-0")).duplicate()
                    val bytes = ByteArray(csd.remaining() + 4)
                    csd.get(bytes, 0, csd.remaining())
                    setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(bytes))
                }
                ContinuousVideoRecorder::class.java.getDeclaredMethod("installOrStageFormat", ContinuousTrackFormatAndroid.Snapshot::class.java)
                    .apply { isAccessible = true }.invoke(recorder, ContinuousTrackFormatAndroid.capture(format))
            } catch (t: Throwable) { failed.set(t) } finally { done.countDown() }
        }
        assertTrue(done.await(2, TimeUnit.SECONDS)); failed.get()?.let { throw it }
    }

    @Test fun abandonedTailIsNotPublishedAsSuccessfulRecording() {
        val codec = requireNotNull(MediaCodecList(MediaCodecList.REGULAR_CODECS)
            .findEncoderForFormat(ContinuousVideoRecorder.format(config)))
        val published = CopyOnWriteArrayList<EncodedSegmentSummary>()
        val unused = CopyOnWriteArrayList<RecordingOutputHandle>()
        val recorder = ContinuousVideoRecorder(codec, { _, _ -> }, onClosed = { _, file -> published += file },
            onUnused = { _, file -> unused += file })
        val first = FdOutput(File(directory, "before-loss.mp4"))
        val tail = FdOutput(File(directory, "lost-tail.mp4"))
        val producer = PaintProducer()
        var stopped = false
        try {
            recorder.prepare(config, first); recorder.start(); producer.attach(recorder.surface)
            repeat(20) { producer.frame(it, 640, 480); Thread.sleep(34) }
            await { recorder.evidence().writtenFrames == 20L }
            recorder.requestCut(tail)
            repeat(20) { producer.frame(it + 20, 640, 480); Thread.sleep(34) }
            await { published.size == 1 && recorder.evidence().writtenFrames == 40L }
            // Inject the same loss signal used by the production removal path. The FD/muxer
            // and codec are real; this is deliberately not a physical USB-unplug test.
            recorder.abandonOutput()
            producer.close(); recorder.stop(); stopped = true
            assertEquals("Only the sealed file before storage loss may be published", listOf(first), published.map { it.output })
            assertEquals(1L, recorder.evidence().completedFiles)
            assertTrue("A used but lost file is not an unused destination to delete", tail !in unused)
            assertTrue(first.nativeReleaseConfirmed && tail.nativeReleaseConfirmed)
            assertEquals(published.single().frames, sampleCount(first.localWorkingFile))
        } finally {
            producer.close()
            if (!stopped) runCatching { recorder.stop() }
            recorder.release(); first.close(); tail.close()
        }
    }

    @Test fun stopWhileRotationNotificationIsDelayedSavesEveryAcceptedFrame() {
        val error = AtomicReference<String?>()
        val recorder = newRecorder(error)
        val first = FdOutput(File(directory, "race-first.mp4"))
        val next = FdOutput(File(directory, "race-next.mp4"))
        val producer = PaintProducer()
        val notified = CountDownLatch(1)
        val allowNotification = CountDownLatch(1)
        val owner = Executors.newSingleThreadExecutor()
        var stopped = false
        try {
            recorder.prepare(config, first); recorder.start(); producer.attach(recorder.surface)
            repeat(20) { producer.frame(it, 640, 480); Thread.sleep(34) }
            await { (recorder.evidence().writtenFrames ?: 0) > 10 }
            recorder.requestCut(next) { owner.execute {
                notified.countDown()
                check(allowNotification.await(8, TimeUnit.SECONDS))
                // Publication is delayed, but the next destination already belongs to the writer.
            } }
            repeat(15) { producer.frame(it + 20, 640, 480); Thread.sleep(34) }
            assertTrue(notified.await(4, TimeUnit.SECONDS))
            producer.close()
            recorder.stop(); stopped = true
            val evidence = recorder.evidence()
            assertNull(error.get())
            assertEquals(35L, evidence.frames)
            assertEquals(evidence.frames, evidence.writtenFrames)
            assertEquals(evidence.frames, sampleCount(first.localWorkingFile) + sampleCount(next.localWorkingFile))
        } finally {
            producer.close()
            allowNotification.countDown(); owner.shutdown(); assertTrue(owner.awaitTermination(8, TimeUnit.SECONDS))
            if (!stopped) runCatching { recorder.stop() }
            recorder.release(); first.close(); next.close()
        }
    }

    @Test fun tenRealSixtySecondCutsUseTheProductionDirectCodec() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("stabilityLongRun") == "true")
        runCuts(60_000, 10)
    }

    @Test fun twentyRecorderLifetimesDoNotRetainTheirWorkersOrDescriptors() {
        val samples = mutableListOf<List<Long>>()
        val cycles = InstrumentationRegistry.getArguments().getString("recorderCycles")?.toIntOrNull()?.coerceIn(20, 80) ?: 20
        repeat(cycles) { cycle ->
            val errors = AtomicReference<String?>()
            val recorder = newRecorder(errors)
            val output = FdOutput(File(directory, "lifetime-$cycle.mp4"))
            val producer = PaintProducer()
            var stopped = false
            try {
                recorder.prepare(config, output); recorder.start(); producer.attach(recorder.surface)
                repeat(18) { producer.frame(it, 640, 480); Thread.sleep(34) }
                producer.close(); recorder.stop(); stopped = true
                val evidence = recorder.evidence()
                assertNull(errors.get())
                assertEquals(18L, evidence.frames)
                assertEquals(evidence.frames, evidence.writtenFrames)
                assertEquals(evidence.frames, sampleCount(output.localWorkingFile))
            } finally {
                producer.close()
                if (!stopped) runCatching { recorder.stop() }
                recorder.release(); output.close()
            }
            assertTrue(output.nativeReleaseConfirmed)
            assertTrue("Recorder-specific workers must exit after each lifetime", Thread.getAllStackTraces().keys.none {
                it.isAlive && it.name in setOf("openavm-encoder", "openavm-mp4")
            })
            samples += listOf(cycle.toLong(), File("/proc/self/fd").list()!!.size.toLong(),
                File("/proc/self/task").list()!!.size.toLong(), android.os.Debug.getNativeHeapAllocatedSize(),
                Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
            File(directory, "lifetimes.tsv").writeText(samples.joinToString("\n") { it.joinToString("\t") })
            val descriptors = File("/proc/self/fd").list().orEmpty().mapNotNull { fd ->
                runCatching { fd to android.system.Os.readlink("/proc/self/fd/$fd") }.getOrNull()
            }
            File(directory, "fds-$cycle.tsv").writeText(descriptors.map { "${it.first}\t${it.second}" }.sorted().joinToString("\n"))
            assertTrue("No muxer or output descriptor may retain a stopped recording", descriptors.none {
                it.second.contains("/stability-pipeline/${directory.name}/lifetime-")
            })
        }
        // Global descriptors include platform codec buffer-pool caches. Keep their trend as evidence;
        // a cold-process total is not a valid ownership fence for this recorder's files/workers.
        val idle = InstrumentationRegistry.getArguments().getString("resourceIdleSeconds")?.toIntOrNull()?.coerceIn(2, 15) ?: 2
        repeat(idle) { second ->
            Thread.sleep(1_000)
            File(directory, "idle.tsv").appendText("${second + 1}\t${File("/proc/self/fd").list()?.size}\t" +
                "${File("/proc/self/task").list()?.size}\t${android.os.Debug.getNativeHeapAllocatedSize()}\n")
        }
        File(directory, "fds-after-idle.tsv").writeText(File("/proc/self/fd").list().orEmpty().mapNotNull { fd ->
            runCatching { "$fd\t${android.system.Os.readlink("/proc/self/fd/$fd")}" }.getOrNull()
        }.sorted().joinToString("\n"))
        File(directory, "result.txt").writeText("backend=CONTINUOUS_CODEC\ninput=SYNTHETIC_EGL\n" +
            "output=REAL_FD_NOT_USB_PROVIDER\nlifetimes=$cycles\nframesPerLifetime=18\n" +
            "ownedOutputDescriptorsAndWorkers=RELEASED\nglobalFdTrend=OBSERVATIONAL_NOT_AN_OWNERSHIP_FENCE\n")
    }

    private fun runCuts(intervalMs: Long, cuts: Int) {
        val errors = AtomicReference<String?>()
        val ownerError = AtomicReference<Throwable?>()
        val recorder = newRecorder(errors)
        val owner = Executors.newSingleThreadExecutor()
        val outputs = CopyOnWriteArrayList<FdOutput>()
        val cutDone = CopyOnWriteArrayList<EncodedSegmentSummary>()
        val producer = PaintProducer()
        var stopped = false
        try {
            fun next(): FdOutput = FdOutput(File(directory, "segment-${outputs.size}.mp4")).also { outputs += it }
            recorder.prepare(config, next()); recorder.start(); producer.attach(recorder.surface)
            val start = SystemClock.elapsedRealtime()
            fun snapshot(stage: String) {
                File(directory, "resources.tsv").appendText(listOf(stage, SystemClock.elapsedRealtime() - start,
                    File("/proc/self/fd").list()?.size, File("/proc/self/task").list()?.size,
                    android.os.Debug.getNativeHeapAllocatedSize(),
                    Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(),
                    android.os.Process.getElapsedCpuTime()).joinToString("\t") + "\n")
            }
            snapshot("start")
            var requested = 0; var frame = 0
            val until = intervalMs * cuts + 1_300
            while (SystemClock.elapsedRealtime() - start < until) {
                errors.get()?.let { error(it) }; ownerError.get()?.let { throw it }
                val elapsed = SystemClock.elapsedRealtime() - start
                if (requested < cuts && elapsed >= intervalMs * (requested + 1) && cutDone.size == requested) {
                    requested++
                    snapshot("cut-$requested")
                    recorder.requestCut(next()) { summary -> owner.execute {
                        try { cutDone += summary }
                        catch (failure: Throwable) { ownerError.compareAndSet(null, failure) }
                    } }
                }
                producer.frame(frame++, 640, 480)
                val wait = start + frame * 1_000L / 30 - SystemClock.elapsedRealtime()
                if (wait > 0) Thread.sleep(wait)
            }
            producer.close()
            owner.submit {}.get(8, TimeUnit.SECONDS)
            recorder.stop(); stopped = true
            snapshot("stop")
            assertNull(errors.get()); assertNull(ownerError.get())
            assertEquals(cuts, cutDone.size)
            val evidence = recorder.evidence()
            assertEquals(evidence.frames, evidence.writtenFrames)
            assertEquals(evidence.writtenFrames, outputs.sumOf { sampleCount(it.localWorkingFile) })
            assertEquals(0, evidence.queueItems)
            assertEquals(cuts + 1L, evidence.completedFiles)
            File(directory, "result.txt").writeText("backend=${recorder.name}\ninput=SYNTHETIC_EGL\n" +
                "output=REAL_FD_NOT_USB_PROVIDER\nsize=640x480\nintervalMs=$intervalMs\ncuts=$cuts\n" +
                "encoded=${evidence.frames}\nwritten=${evidence.writtenFrames}\nfiles=${outputs.size}\n")
        } finally {
            producer.close()
            owner.shutdown(); assertTrue(owner.awaitTermination(8, TimeUnit.SECONDS))
            if (!stopped) runCatching { recorder.stop() }
            recorder.release(); outputs.forEach { it.close() }
        }
    }

    private fun newRecorder(error: AtomicReference<String?>, unused: MutableList<RecordingOutputHandle>? = null): ContinuousVideoRecorder {
        val codec = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(ContinuousVideoRecorder.format(config))
        return ContinuousVideoRecorder(requireNotNull(codec), { _, message -> error.compareAndSet(null, message) },
            queueBytes = RecordingChannelPolicy.QUEUE_BYTES, queueItems = RecordingChannelPolicy.QUEUE_ITEMS,
            onUnused = { _, output -> unused?.add(output) })
    }

    private fun await(predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 5_000
        while (!predicate()) { check(SystemClock.elapsedRealtime() < end) { "Pipeline condition timed out" }; Thread.sleep(10) }
    }

    private fun sampleCount(file: File): Long {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath); assertEquals(1, extractor.trackCount)
            extractor.selectTrack(0)
            assertTrue(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
            assertEquals(0L, extractor.sampleTime)
            var count = 0L; var last = -1L
            while (extractor.sampleTime >= 0) {
                assertTrue(extractor.sampleTime > last); last = extractor.sampleTime; count++
                extractor.advance()
            }
            return count
        } finally { extractor.release() }
    }

    private class FdOutput(override val localWorkingFile: File, private val beforeClose: () -> Unit = {}) : RecordingOutputHandle {
        private var descriptor: ParcelFileDescriptor? = ParcelFileDescriptor.open(localWorkingFile,
            ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE)
        override val storage = RecordingStorageIdentity(RecordingStorageKind.INTERNAL)
        override val displayName get() = localWorkingFile.name
        override val nativeReleaseConfirmed get() = descriptor == null
        override fun bind(recorder: MediaRecorder) = error("Expected production continuous codec")
        override fun createMuxer() = MediaMuxer(requireNotNull(descriptor).fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        override fun close() { if (descriptor != null) beforeClose(); descriptor?.close(); descriptor = null }
    }
}

/** Minimal test producer. Camera ownership, session coordination and HAL are explicitly outside its scope. */
internal class PaintProducer(private val alphaBits: Int = 0) {
    private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private var context = EGL14.EGL_NO_CONTEXT
    private var window = EGL14.EGL_NO_SURFACE
    private var active = false
    fun attach(surface: Surface) {
        val version = IntArray(2); check(EGL14.eglInitialize(display, version, 0, version, 1)); active = true
        val configs = arrayOfNulls<EGLConfig>(1); val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, alphaBits, 0x3142, 1, EGL14.EGL_NONE), 0, configs, 0, 1, count, 0) && count[0] > 0)
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT)
        window = EGL14.eglCreateWindowSurface(display, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0)
        check(window != EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(display, window, window, context))
    }
    fun frame(number: Int, width: Int, height: Int, timestampNs: Long = SystemClock.elapsedRealtimeNanos()) {
        GLES20.glViewport(0, 0, width, height)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glClearColor((number % 60) / 60f, 0.25f, 0.5f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        repeat(16) { bit ->
            GLES20.glScissor(bit * width / 16, 0, width / 16, height / 8)
            val level = if (number and (1 shl bit) == 0) 0f else 1f
            GLES20.glClearColor(level, level, level, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        }
        check(GLES20.glGetError() == GLES20.GL_NO_ERROR)
        check(EGLExt.eglPresentationTimeANDROID(display, window, timestampNs))
        check(EGL14.eglSwapBuffers(display, window))
    }
    fun close() {
        if (!active) return
        GLES20.glFinish()
        check(EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT))
        if (window != EGL14.EGL_NO_SURFACE) check(EGL14.eglDestroySurface(display, window))
        if (context != EGL14.EGL_NO_CONTEXT) check(EGL14.eglDestroyContext(display, context))
        check(EGL14.eglTerminate(display)); EGL14.eglReleaseThread()
        window = EGL14.EGL_NO_SURFACE; context = EGL14.EGL_NO_CONTEXT; active = false
    }
}
