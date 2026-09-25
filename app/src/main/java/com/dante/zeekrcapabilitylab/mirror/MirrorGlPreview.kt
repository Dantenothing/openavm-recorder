package com.dante.zeekrcapabilitylab.mirror

import android.graphics.SurfaceTexture
import android.opengl.*
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class MirrorGlEvidence(
    val backend: String = "NONE", val inputNotifications: Long = 0, val inputFrames: Long = 0,
    val inputFrameAgeMs: Long? = null, val inputHeartbeatAgeMs: Long? = null,
    val inputTimestampNs: Long? = null, val droppedDisplayFrames: Long = 0,
    val displaySubmissions: Long = 0, val displaySubmitAgeMs: Long? = null,
    val displayWorkers: Int = 0, val poolSlots: Int = 0, val poolBytes: Long = 0,
    val inputFailure: String? = null, val displayFailure: String? = null,
    val cleanupPending: Boolean = false,
    val publishedSourceSerial: Long = 0,
    val lastDisplaySourceSerial: Long = 0,
    val lastDisplaySourceTimestampNs: Long? = null,
    val inputPollAttempts: Long = 0,
    val inputPolledFrames: Long = 0,
    val lastInputNotificationAgeMs: Long? = null,
    val lastInputPollAgeMs: Long? = null,
)

/**
 * Camera -> owned OES SurfaceTexture -> three shared RGBA textures -> display TextureView.
 * Only the input thread calls updateTexImage. Only display threads call eglSwapBuffers.
 * A blocked window cannot hold the pool monitor or the input thread. Both directions use GPU fences.
 * The camera output's release acknowledgement, not a UI timeout, authorizes input destruction.
 */
internal class MirrorGlPreview private constructor(
    private val width: Int, private val height: Int,
    private val ready: (SurfaceTexture) -> Unit, private val failed: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val inputThread = HandlerThread("mirror-gl-input")
    private lateinit var input: Handler
    private val pool = MirrorGlFramePool()
    private class Slot { var texture = 0; var framebuffer = 0; var written = 0L; var read = 0L; var inputAtMs = 0L }
    private val slots = Array(pool.capacity) { Slot() }
    private val drops = AtomicLong()
    private val submissions = AtomicLong()
    private val displaySourceSerial = AtomicLong()
    private val displaySourceTimestamp = AtomicLong()
    private val workers = AtomicInteger()
    @Volatile private var lastHeartbeatAt = 0L
    @Volatile private var lastSubmitAt = 0L
    @Volatile private var inputFailure: String? = null
    @Volatile private var displayFailure: String? = null
    @Volatile private var stopping = false
    @Volatile private var displaying = false
    @Volatile private var destroyed = false
    private var root: GlContext? = null
    private var blitter: Blitter? = null
    private var externalTexture = 0
    private var cameraTexture: SurfaceTexture? = null
    private val inputPump = MirrorInputPump(SystemClock::elapsedRealtime, {
        root?.makeCurrent()
        val texture = checkNotNull(cameraTexture)
        // A queued buffer is independently observable even if its delivery notification was lost.
        // The owning GL thread alone reads it; unchanged timestamps never become fresh frames.
        texture.updateTexImage()
        texture.timestamp
    }, ::publishInputFrame)
    private val heartbeatTask = Runnable { heartbeat() }
    private var unfinishedWrite: MirrorGlFramePool.Write? = null
    private val transform = FloatArray(16)
    // Main thread owns display targets. At most two workers plus one unstarted latest target.
    private class Target(val texture: SurfaceTexture, val afterSerial: Long) {
        @Volatile var stop = false
        var destroyedByView = false
        var worker: DisplayWorker? = null
        var finished = false
    }
    private val targets = linkedMapOf<SurfaceTexture, Target>()
    private var activeTarget: Target? = null

    private fun start() {
        inputThread.start(); input = Handler(inputThread.looper)
        input.post {
            try {
                check(width > 0 && height > 0 && width.toLong() * height * 4 * pool.capacity <= 96L * 1024 * 1024) { "GL_SIZE_BUDGET" }
                root = GlContext.createRoot()
                root!!.makeCurrent()
                val limit = IntArray(2)
                GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, limit, 0)
                check(width <= limit[0] && height <= limit[0]) { "GL_TEXTURE_SIZE_UNSUPPORTED" }
                GLES20.glGetIntegerv(GLES20.GL_MAX_VIEWPORT_DIMS, limit, 0)
                check(width <= limit[0] && height <= limit[1]) { "GL_VIEWPORT_SIZE_UNSUPPORTED" }
                blitter = Blitter(external = true)
                val ids = IntArray(1)
                slots.forEach { slot ->
                    GLES20.glGenTextures(1, ids, 0); slot.texture = ids[0]
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, slot.texture); textureParameters(GLES20.GL_TEXTURE_2D)
                    GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
                    GLES20.glGenFramebuffers(1, ids, 0); slot.framebuffer = ids[0]
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, slot.framebuffer)
                    GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, slot.texture, 0)
                    check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) { "GL_FRAMEBUFFER_UNSUPPORTED" }
                }
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                GLES20.glGenTextures(1, ids, 0); externalTexture = ids[0]
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTexture)
                textureParameters(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
                checkGl()
                val texture = SurfaceTexture(externalTexture)
                cameraTexture = texture
                texture.setDefaultBufferSize(width, height)
                texture.setOnFrameAvailableListener({
                    if (!destroyed && inputFailure == null) {
                        try { inputPump.notification() } catch (error: Exception) { inputError(error) }
                    }
                }, input)
                main.post { if (!stopping) { startLatestDisplay(); ready(texture) } }
                heartbeat()
            } catch (error: Exception) { inputError(error) }
        }
    }

    private fun heartbeat() {
        input.removeCallbacks(heartbeatTask)
        if (destroyed) return
        lastHeartbeatAt = SystemClock.elapsedRealtime()
        if (stopping) finishWhenSafe()
        else if (inputFailure == null) {
            try { inputPump.heartbeat() } catch (error: Exception) { inputError(error) }
        }
        if (!destroyed) input.postDelayed(heartbeatTask,
            if (stopping || inputFailure != null) 250L else inputPump.heartbeatDelayMs())
    }

    fun setInputPollingEnabled(enabled: Boolean) { inputPump.pollingEnabled = enabled }

    private fun publishInputFrame() {
        val texture = cameraTexture ?: return
        if (!displaying || stopping) return
        texture.getTransformMatrix(transform)
        val excluded = mutableSetOf<Int>()
        repeat(pool.capacity) {
            val write = pool.reserveWrite(excluded) ?: run { drops.incrementAndGet(); return }
            unfinishedWrite = write
            val slot = slots[write.index]
            if (!fenceReady(slot.written) || !fenceReady(slot.read)) {
                pool.cancelWrite(write); unfinishedWrite = null; excluded += write.index
            } else {
                deleteFences(slot)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, slot.framebuffer)
                GLES20.glViewport(0, 0, width, height)
                blitter!!.draw(externalTexture, transform)
                slot.written = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
                check(slot.written != 0L) { "GL_WRITE_FENCE_FAILED" }
                GLES20.glFlush(); checkGl()
                slot.inputAtMs = checkNotNull(inputPump.lastFrameAt)
                pool.publish(write, inputPump.timestamp)
                unfinishedWrite = null
                return
            }
        }
        drops.incrementAndGet()
    }

    /** Main thread. This is a DISPLAY texture, never the camera-facing SurfaceTexture. */
    fun attachDisplay(texture: SurfaceTexture) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (stopping) return
        activeTarget?.stop = true
        val target = Target(texture, pool.latestSerial())
        displayFailure = null
        targets[texture] = target; activeTarget = target; displaying = true
        startLatestDisplay()
    }

    /** Returns false when we retain the texture until its GL producer has actually returned. */
    fun destroyDisplay(texture: SurfaceTexture): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        val target = targets[texture] ?: return true
        target.destroyedByView = true; target.stop = true
        if (activeTarget === target) { activeTarget = null; displaying = false }
        if (target.worker == null || target.finished) releaseTarget(target)
        return false
    }

    private fun startLatestDisplay() {
        val target = activeTarget ?: return
        if (stopping || target.stop || target.worker != null || workers.get() >= 2) return
        val shared = root ?: return
        workers.incrementAndGet()
        target.worker = DisplayWorker(target, shared).also { it.start() }
    }

    private fun releaseTarget(target: Target) {
        check(target.destroyedByView && (target.worker == null || target.finished))
        targets.remove(target.texture)
        target.texture.release()
    }

    private inner class DisplayWorker(private val target: Target, private val shared: GlContext) : Thread("mirror-gl-display") {
        // Preserve wrappers too if native cleanup is unconfirmed; never leave their release to GC.
        private var retainedEgl: GlContext? = null
        private var retainedSurface: Surface? = null
        private var retainedShader: Blitter? = null
        override fun run() {
            var egl: GlContext? = null
            var shader: Blitter? = null
            var surface: Surface? = null
            var safeToRelease = false
            try {
                surface = Surface(target.texture).also { retainedSurface = it }
                egl = GlContext.createShared(shared, surface).also { retainedEgl = it }
                egl.makeCurrent()
                // Android maps interval zero to asynchronous BufferQueue mode. Request dropping obsolete
                // queued frames instead of waiting for an invisible/stalled HWUI consumer. Isolation and
                // bounded worker ownership still apply if a driver call blocks despite this request.
                check(EGL14.eglSwapInterval(egl.display, 0)) { "GL_SWAP_INTERVAL_FAILED" }
                shader = Blitter(external = false).also { retainedShader = it }
                val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
                var serial = target.afterSerial
                while (!target.stop && !stopping) {
                    val read = pool.acquireLatest(serial)
                    if (read == null) { sleep(12); continue }
                    val slot = slots[read.index]
                    if (SystemClock.elapsedRealtime() - slot.inputAtMs > 2_000) {
                        serial = read.serial; pool.releaseRead(read, false); continue
                    }
                    if (!fenceReady(slot.written)) { pool.releaseRead(read, false); sleep(4); continue }
                    var commandsIssued = false
                    var fenced = false
                    try {
                        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                        GLES20.glViewport(0, 0, width, height)
                        commandsIssued = true
                        shader.draw(slot.texture, identity)
                        slot.read = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
                        check(slot.read != 0L) { "GL_READ_FENCE_FAILED" }
                        GLES20.glFlush(); fenced = true; checkGl()
                        // EGL swap may block indefinitely. The input thread never waits here or for this worker.
                        EGLExt.eglPresentationTimeANDROID(egl.display, egl.output, read.timestamp)
                        check(EGL14.eglSwapBuffers(egl.display, egl.output)) { "GL_DISPLAY_SWAP_FAILED" }
                        serial = read.serial
                        if (!target.stop && !stopping) {
                            displaySourceSerial.set(read.serial); displaySourceTimestamp.set(read.timestamp)
                            submissions.incrementAndGet(); lastSubmitAt = SystemClock.elapsedRealtime()
                        }
                    } finally {
                        // On a fence-creation failure, only this display worker waits for its GPU work.
                        // If that wait never returns, this slot remains leased and input uses the other slots.
                        if (commandsIssued && !fenced) { GLES20.glFinish(); checkGl() }
                        pool.releaseRead(read, consumed = commandsIssued)
                    }
                }
                safeToRelease = true
            } catch (_: Exception) {
                if (!target.stop && !stopping) displayFailure = "GL_DISPLAY_FAILED"
                // Context destruction is part of the producer fence; failure retains the target.
                safeToRelease = true
            } finally {
                try {
                    shader?.close()
                    egl?.close()
                    surface?.release()
                    retainedEgl = null; retainedSurface = null; retainedShader = null
                } catch (_: Exception) { safeToRelease = false; displayFailure = "GL_DISPLAY_CLOSE_UNCONFIRMED" }
                if (safeToRelease) main.post {
                    target.finished = true; workers.decrementAndGet()
                    if (target.destroyedByView) releaseTarget(target)
                    startLatestDisplay()
                }
            }
        }
    }

    /** Call only after the existing recorder callback confirms the camera output has been released. */
    fun closeAfterProducer() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (stopping) return
        stopping = true; displaying = false
        inputPump.stop()
        targets.values.toList().forEach { target ->
            target.stop = true
            if (target.destroyedByView && (target.worker == null || target.finished)) releaseTarget(target)
        }
        input.post { finishWhenSafe(); if (!destroyed && inputFailure != null) heartbeat() }
    }

    private fun finishWhenSafe() {
        if (destroyed || !stopping || workers.get() != 0) return
        try {
            val hasGlObjects = blitter != null || externalTexture != 0 || slots.any { it.texture != 0 || it.framebuffer != 0 }
            if (hasGlObjects) root?.makeCurrent()
            unfinishedWrite?.let { write ->
                // Only after Camera2 is released and all display workers have acknowledged shutdown.
                GLES20.glFinish(); checkGl(); pool.cancelWrite(write); unfinishedWrite = null
            }
            if (pool.inUse() != 0) return
            if (root != null && slots.any { !fenceReady(it.written) || !fenceReady(it.read) }) return
            cameraTexture?.setOnFrameAvailableListener(null)
            cameraTexture?.release(); cameraTexture = null
            slots.forEach { slot ->
                deleteFences(slot)
                if (slot.framebuffer != 0) { GLES20.glDeleteFramebuffers(1, intArrayOf(slot.framebuffer), 0); slot.framebuffer = 0 }
                if (slot.texture != 0) { GLES20.glDeleteTextures(1, intArrayOf(slot.texture), 0); slot.texture = 0 }
            }
            if (externalTexture != 0) { GLES20.glDeleteTextures(1, intArrayOf(externalTexture), 0); externalTexture = 0 }
            blitter?.close(); blitter = null; root?.close()
            destroyed = true; clearOwner(this); inputThread.quitSafely()
        } catch (_: Exception) { inputFailure = "GL_INPUT_CLOSE_UNCONFIRMED" }
    }

    private fun inputError(error: Exception) {
        if (inputFailure != null) return
        val reason = error.message?.takeIf { it.matches(Regex("GL_[A-Z_]+")) } ?: "GL_INPUT_FAILED"
        inputFailure = reason
        main.post { failed(reason) }
    }

    fun evidence(now: Long = SystemClock.elapsedRealtime()): MirrorGlEvidence {
        fun age(at: Long) = at.takeIf { it > 0 && it <= now }?.let { now - it }
        return MirrorGlEvidence("OWNED_GL_INPUT", inputPump.notifications, inputPump.frames,
            inputPump.lastFrameAt?.let(::age), age(lastHeartbeatAt),
            inputPump.timestamp.takeIf { it > 0 }, drops.get(), submissions.get(), age(lastSubmitAt), workers.get(), pool.capacity,
            width.toLong() * height * 4 * pool.capacity, inputFailure, displayFailure, stopping && !destroyed,
            pool.latestSerial(), displaySourceSerial.get(), displaySourceTimestamp.get().takeIf { it > 0 },
            inputPump.pollAttempts, inputPump.polledFrames, inputPump.lastNotificationAt?.let(::age),
            inputPump.lastPollAt?.let(::age))
    }

    fun cleanupConfirmed(): Boolean = destroyed && workers.get() == 0

    companion object {
        // A stuck retired GL producer is retained. A later recording may continue without allocating another GL engine.
        private var owner: MirrorGlPreview? = null
        @Synchronized fun isIdle(): Boolean = owner == null
        @Synchronized fun create(width: Int, height: Int, ready: (SurfaceTexture) -> Unit, failed: (String) -> Unit): MirrorGlPreview? {
            if (owner != null) return null
            return MirrorGlPreview(width, height, ready, failed).also { owner = it; it.start() }
        }
        @Synchronized private fun clearOwner(value: MirrorGlPreview) { if (owner === value) owner = null }
        private fun fenceReady(sync: Long): Boolean = if (sync == 0L) true else when (GLES30.glClientWaitSync(sync, 0, 0)) {
            GLES30.GL_ALREADY_SIGNALED, GLES30.GL_CONDITION_SATISFIED -> true
            GLES30.GL_TIMEOUT_EXPIRED -> false
            else -> error("GL_FENCE_WAIT_FAILED")
        }
        private fun deleteFences(slot: Slot) {
            if (slot.written != 0L) { GLES30.glDeleteSync(slot.written); slot.written = 0 }
            if (slot.read != 0L) { GLES30.glDeleteSync(slot.read); slot.read = 0 }
        }
        private fun textureParameters(target: Int) {
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }
        private fun checkGl() { check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "GL_OPERATION_FAILED" } }
    }

    /** One process-lifetime EGL display connection; never eglTerminate a display also used by HWUI. */
    private class GlContext(val display: EGLDisplay, val config: EGLConfig, val context: EGLContext, val output: EGLSurface) {
        private var outputClosed = false
        private var contextClosed = false
        fun makeCurrent() { check(EGL14.eglMakeCurrent(display, output, output, context)) { "GL_MAKE_CURRENT_FAILED" } }
        fun close() {
            check(EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)) { "GL_UNBIND_FAILED" }
            if (!outputClosed) { check(EGL14.eglDestroySurface(display, output)) { "GL_SURFACE_CLOSE_FAILED" }; outputClosed = true }
            if (!contextClosed) { check(EGL14.eglDestroyContext(display, context)) { "GL_CONTEXT_CLOSE_FAILED" }; contextClosed = true }
            EGL14.eglReleaseThread()
        }
        companion object {
            private var connection: EGLDisplay? = null
            @Synchronized private fun display(): EGLDisplay {
                connection?.let { return it }
                val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                check(display != EGL14.EGL_NO_DISPLAY && EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0)) { "GL_DISPLAY_UNAVAILABLE" }
                connection = display; return display
            }
            fun createRoot(): GlContext {
                val display = display()
                val configs = arrayOfNulls<EGLConfig>(1); val count = IntArray(1)
                val attributes = intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, 0x40, // EGL_OPENGL_ES3_BIT
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE)
                check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) { "GL_ES3_UNAVAILABLE" }
                return create(display, checkNotNull(configs[0]), EGL14.EGL_NO_CONTEXT, null)
            }
            fun createShared(root: GlContext, surface: Surface) = create(root.display, root.config, root.context, surface)
            private fun create(display: EGLDisplay, config: EGLConfig, share: EGLContext, surface: Surface?): GlContext {
                val context = EGL14.eglCreateContext(display, config, share, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
                check(context != EGL14.EGL_NO_CONTEXT) { "GL_CONTEXT_UNAVAILABLE" }
                val output = if (surface == null) EGL14.eglCreatePbufferSurface(display, config,
                    intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
                else EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
                if (output == EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroyContext(display, context); error("GL_OUTPUT_UNAVAILABLE")
                }
                return GlContext(display, config, context, output)
            }
        }
    }

    private class Blitter(private val external: Boolean) {
        private val vertices = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f)); position(0)
        }
        private val program: Int
        private val position: Int
        private val uv: Int
        private val matrix: Int
        init {
            val vertex = shader(GLES20.GL_VERTEX_SHADER, "attribute vec2 aPosition; attribute vec2 aUv; uniform mat4 uMatrix; varying vec2 vUv; void main(){gl_Position=vec4(aPosition,0.0,1.0);vUv=(uMatrix*vec4(aUv,0.0,1.0)).xy;}")
            val prefix = if (external) "#extension GL_OES_EGL_image_external : require\n" else ""
            val sampler = if (external) "samplerExternalOES" else "sampler2D"
            var fragment = 0
            program = GLES20.glCreateProgram()
            try {
                fragment = shader(GLES20.GL_FRAGMENT_SHADER, prefix + "precision highp float; varying vec2 vUv; uniform $sampler uTexture; void main(){gl_FragColor=vec4(texture2D(uTexture,vUv).rgb,1.0);}")
                GLES20.glAttachShader(program, vertex); GLES20.glAttachShader(program, fragment); GLES20.glLinkProgram(program)
                val status = IntArray(1); GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
                check(status[0] != 0) { "GL_PROGRAM_FAILED" }
            } catch (error: Exception) { GLES20.glDeleteProgram(program); throw error }
            finally { GLES20.glDeleteShader(vertex); if (fragment != 0) GLES20.glDeleteShader(fragment) }
            position = GLES20.glGetAttribLocation(program, "aPosition"); uv = GLES20.glGetAttribLocation(program, "aUv")
            matrix = GLES20.glGetUniformLocation(program, "uMatrix")
        }
        fun draw(texture: Int, transform: FloatArray) {
            GLES20.glUseProgram(program); GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(if (external) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D, texture)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0)
            GLES20.glUniformMatrix4fv(matrix, 1, false, transform, 0)
            vertices.position(0); GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, vertices); GLES20.glEnableVertexAttribArray(position)
            vertices.position(2); GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 16, vertices); GLES20.glEnableVertexAttribArray(uv)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
        fun close() = GLES20.glDeleteProgram(program)
        private fun shader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type); GLES20.glShaderSource(shader, source); GLES20.glCompileShader(shader)
            val status = IntArray(1); GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) { GLES20.glDeleteShader(shader); error("GL_SHADER_FAILED") }
            return shader
        }
    }
}
