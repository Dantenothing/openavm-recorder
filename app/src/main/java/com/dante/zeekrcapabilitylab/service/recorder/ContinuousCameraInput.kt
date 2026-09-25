package com.dante.zeekrcapabilitylab.service.recorder

import android.opengl.GLES20
import android.opengl.GLES30
import android.os.SystemClock
import android.view.Surface
import com.dante.zeekrcapabilitylab.preflight.continuous.*
import com.dante.zeekrcapabilitylab.preflight.continuous.SharedInputFramePool.Reader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** A single, unchanging Camera2 input. Encoder reads every admitted frame; display reads latest.
 * Display produces the original tall raster, preserving the product's existing lens/zoom UI.
 * The four-slot, fence-protected pool is shared with P2, but no diagnostic pixels or arrays exist. */
internal class ContinuousCameraInput(
    private val codecSurface: Surface,
    private val onFailure: (Throwable, ContinuousFailureStage) -> Unit,
    private val previewUnavailable: (String) -> Unit,
    private val timing: ContinuousVideoTiming = ContinuousVideoTiming(30, 1),
    private val layout: StripRepackLayout = StripRepackLayout(RepackRaster(1280, 5140), 1728),
) {
    private val root = ProbeEglNode()
    private val receiver = SharedOesFrameInput(layout.input.width, layout.input.height)
    private val pool = SharedInputFramePool()
    private val slots = Array(pool.capacity) { ProbeGlTarget(layout.input.width, layout.input.height) }
    private val fences = LongArray(pool.capacity)
    private val failure = AtomicReference<Throwable?>()
    private val ready = CountDownLatch(1)
    private val ended = CountDownLatch(1)
    private val clock = ContinuousSourceClock()
    private val readers = mutableListOf<Consumer>()
    private var writing: SharedInputFramePool.Write? = null
    @Volatile private var producerEnded = false
    @Volatile private var closed = false
    @Volatile private var preview: ContinuousPreviewLease<Surface>? = null
    @Volatile private var previewEnabled = false
    @Volatile private var captureStartedAt: Long? = null
    @Volatile private var heartbeatAt = SystemClock.elapsedRealtime()
    @Volatile private var lastFreshAt: Long? = null
    @Volatile var lastSourcePtsUs = -1L; private set
    @Volatile var lastSubmittedPtsUs = -1L; private set
    @Volatile var submittedFrames = 0L; private set
    @Volatile var firstFrameEpochMs: Long? = null; private set
    @Volatile var firstFrameElapsedMs: Long? = null; private set
    private val inputThread = Thread(::runInput, "recording-shared-input")
    val surface get() = receiver.surface

    fun initialize() {
        inputThread.start()
        check(ready.await(4, TimeUnit.SECONDS)) { "PRODUCT_INPUT_START_TIMEOUT" }
        failure.get()?.let { throw it }
    }
    fun setPreview(surface: Surface?, released: (Surface) -> Unit) {
        val next = surface?.let { ContinuousPreviewLease(it, released) }
        val old = synchronized(this) {
            if (preview?.value === surface) return
            preview.also { preview = next }
        }
        old?.retire()
    }
    fun enablePreview(enabled: Boolean) { previewEnabled = enabled }
    fun startCaptureWatchdog() { captureStartedAt = SystemClock.elapsedRealtime() }
    fun stopCaptureWatchdog() { captureStartedAt = null }
    fun sourceAgeMs(now: Long): Long = captureStartedAt?.let { now - maxOf(lastFreshAt ?: it, it) } ?: 0
    fun watchdogProblem(now: Long): String? = if (captureStartedAt == null) null else
        ContinuousSourceWaitPolicy.problem(now - heartbeatAt, sourceAgeMs(now))
    private fun fail(error: Throwable, stage: ContinuousFailureStage) {
        if (failure.compareAndSet(null, error)) onFailure(error, stage)
    }
    private fun runInput() {
        try {
            root.initialize()
            val limit = IntArray(1); GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, limit, 0)
            check(limit[0] >= maxOf(layout.input.width, layout.input.height)) { "PRODUCT_GL_RASTER_LIMIT" }
            slots.forEach { it.allocate() }; receiver.initialize()
            listOf(Reader.ENCODER, Reader.DISPLAY_A).forEach { kind ->
                Consumer(kind).also { readers += it; it.start()
                    check(it.ready.await(3, TimeUnit.SECONDS)) { "PRODUCT_READER_START_TIMEOUT" }
                    failure.get()?.let { error -> throw error }
                }
            }
            ready.countDown()
            while (!producerEnded && failure.get() == null) {
                heartbeatAt = SystemClock.elapsedRealtime()
                val stamp = receiver.acquireLatest()
                val pts = clock.accept(stamp)
                if (pts == null) {
                    Thread.sleep(if (sourceAgeMs(heartbeatAt) >= ContinuousSourceWaitPolicy.QUIET_AFTER_MS) 20 else 2); continue
                }
                lastFreshAt = heartbeatAt; lastSourcePtsUs = pts
                if (firstFrameEpochMs == null) {
                    firstFrameEpochMs = System.currentTimeMillis(); firstFrameElapsedMs = SystemClock.elapsedRealtime()
                }
                val start = SystemClock.elapsedRealtime()
                var write = pool.reserve()
                while (write == null) {
                    failure.get()?.let { throw it }
                    check(SystemClock.elapsedRealtime() - start < 500) { "PRODUCT_ENCODER_BACKPRESSURE" }
                    Thread.sleep(1); write = pool.reserve()
                }
                writing = write
                if (fences[write.slot] != 0L) {
                    ProbeGlDraw.waitFence(fences[write.slot]); GLES30.glDeleteSync(fences[write.slot]); fences[write.slot] = 0
                }
                receiver.copyTo(slots[write.slot])
                fences[write.slot] = ProbeGlDraw.fence()
                pool.publish(write, clock.frames - 1, pts * 1000 + 1_000_000_000L); writing = null
            }
        } catch (error: Throwable) { fail(error, ContinuousFailureStage.INPUT) }
        finally {
            ready.countDown()
            // An input failure cannot destroy a Surface that Camera2 may still be writing.
            while (!producerEnded) Thread.sleep(2)
            try {
                if (root.usable) root.finish()
                writing?.let { pool.cancelWrite(it, true); writing = null }
                pool.stopPublishing(); readers.forEach { it.stopRequested = true }
                readers.forEach { it.join(4_000) }
                check(readers.all { !it.isAlive && it.closed } && pool.outstanding() == 0) { "PRODUCT_GL_READERS_UNCONFIRMED" }
                if (root.usable) {
                    root.current(); receiver.close(true)
                    slots.indices.forEach { i ->
                        if (fences[i] != 0L) { ProbeGlDraw.waitFence(fences[i]); GLES30.glDeleteSync(fences[i]); fences[i] = 0 }
                        slots[i].close()
                    }
                }
                root.close(); closed = true
                synchronized(this) { preview.also { preview = null } }?.retire()
            } catch (error: Throwable) { fail(error, ContinuousFailureStage.INPUT_CLEANUP) }
            ended.countDown()
        }
    }
    /** Only RecorderSession's acknowledged Camera2 producer fence may call this. */
    fun stopAfterCameraFence() {
        producerEnded = true
        check(ended.await(5, TimeUnit.SECONDS) && closed) { "PRODUCT_GL_RELEASE_UNCONFIRMED" }
        inputThread.join(500)
        check(!inputThread.isAlive) { "PRODUCT_INPUT_THREAD_UNCONFIRMED" }
    }
    fun released() = closed && !inputThread.isAlive

    private inner class Consumer(private val kind: Reader) : Thread("recording-${kind.name.lowercase()}") {
        val ready = CountDownLatch(1)
        private val node = ProbeEglNode(root)
        private val painter = ProbeGlDraw()
        private var read: SharedInputFramePool.Read? = null
        private var fence = 0L
        private var window: ContinuousPreviewLease<Surface>? = null
        @Volatile var stopRequested = false
        @Volatile var closed = false
        override fun run() {
            try {
                node.initialize(if (kind == Reader.ENCODER) codecSurface else null)
                val shader = painter.program(if (kind == Reader.ENCODER) ProbeSyntheticGl.REPACK else COPY)
                ready.countDown(); var last = -1
                while (!stopRequested || kind == Reader.ENCODER && pool.pendingEncoder() > 0) {
                    if (failure.get() != null) break
                    try {
                    if (kind != Reader.ENCODER) {
                        val candidate = preview.takeIf { previewEnabled }
                        if (candidate !== window) {
                            node.finish(); node.detachWindow(); window?.detachAcknowledged(); window = null
                            if (candidate != null && candidate.take()) {
                                window = candidate; node.attachWindow(candidate.value)
                            }
                        }
                        if (window == null) { Thread.sleep(5); continue }
                    }
                    val fresh = pool.acquire(kind, last)
                    if (fresh == null) { if (stopRequested) break; Thread.sleep(1); continue }
                    read = fresh; ProbeGlDraw.waitFence(fences[fresh.slot])
                    if (kind == Reader.ENCODER) {
                        val pts = timing.select((fresh.timestampNs - 1_000_000_000L) / 1000)
                        if (pts != null) {
                            painter.repack(shader, layout, slots[fresh.slot].texture, 0)
                            node.swap(pts * 1000); lastSubmittedPtsUs = pts; submittedFrames++
                        }
                    } else {
                        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0); GLES20.glViewport(0, 0, layout.input.width, layout.input.height)
                        GLES20.glUseProgram(shader); GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, slots[fresh.slot].texture)
                        GLES20.glUniform1i(GLES20.glGetUniformLocation(shader, "source"), 0)
                        GLES20.glUniform2f(GLES20.glGetUniformLocation(shader, "sourceSize"), layout.input.width.toFloat(), layout.input.height.toFloat())
                        painter.quad(shader); ProbeGlDraw.checkGl(); node.swap(fresh.timestampNs)
                    }
                    fence = ProbeGlDraw.fence(); ProbeGlDraw.waitFence(fence); GLES30.glDeleteSync(fence); fence = 0
                    pool.release(fresh, true); read = null; last = fresh.frame
                    } catch (error: Throwable) {
                        if (kind == Reader.ENCODER) throw error
                        // A dead UI window must not stop the writer. GPU completion and EGL detach
                        // are still required before dropping its lease or notifying the UI owner.
                        node.finish(); read?.let { pool.release(it, true); read = null }
                        if (fence != 0L) { GLES30.glDeleteSync(fence); fence = 0 }
                        val failedWindow = window
                        node.detachWindow(); failedWindow?.retire(); failedWindow?.detachAcknowledged(); window = null
                        synchronized(this@ContinuousCameraInput) { if (preview === failedWindow) preview = null }
                        previewUnavailable(error.message ?: "PRODUCT_PREVIEW_WINDOW_FAILED")
                    }
                }
            } catch (error: Throwable) { fail(error, if (kind == Reader.ENCODER)
                ContinuousFailureStage.ENCODER_RENDER else ContinuousFailureStage.DISPLAY_RENDER) }
            finally {
                ready.countDown()
                try {
                    node.finish(); read?.let { pool.release(it, true); read = null }
                    if (fence != 0L) { GLES30.glDeleteSync(fence); fence = 0 }
                    if (node.usable) painter.close()
                    node.close(); window?.detachAcknowledged(); window = null; closed = true
                } catch (error: Throwable) { fail(error, if (kind == Reader.ENCODER)
                    ContinuousFailureStage.ENCODER_RENDER_CLEANUP else ContinuousFailureStage.DISPLAY_RENDER_CLEANUP) }
            }
        }
    }
    companion object {
        private const val COPY = """
            precision highp float; uniform sampler2D source; uniform vec2 sourceSize;
            void main(){gl_FragColor=texture2D(source,gl_FragCoord.xy/sourceSize);}
        """
    }
}
