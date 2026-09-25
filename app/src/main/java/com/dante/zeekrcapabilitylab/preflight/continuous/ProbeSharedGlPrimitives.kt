package com.dante.zeekrcapabilitylab.preflight.continuous

import android.opengl.*
import android.os.SystemClock
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Each wrapper is retained by its owner before initialize(), including partial native startup. */
internal class ProbeEglNode(private val shared: ProbeEglNode? = null) {
    val display: EGLDisplay = shared?.display ?: EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    var context: EGLContext = EGL14.EGL_NO_CONTEXT; private set
    private var config: EGLConfig? = shared?.config
    private var initialized = false
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var window: EGLSurface = EGL14.EGL_NO_SURFACE
    var closed = false; private set
    val usable get() = context != EGL14.EGL_NO_CONTEXT && pbuffer != EGL14.EGL_NO_SURFACE
    fun initialize(surface: Surface? = null) {
        if (shared == null) {
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "SHARED_EGL_INITIALIZE_FAILED" }
            initialized = true
            val configs = arrayOfNulls<EGLConfig>(1); val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_SURFACE_TYPE,
                EGL14.EGL_PBUFFER_BIT or EGL14.EGL_WINDOW_BIT, EGL14.EGL_RENDERABLE_TYPE, 0x40,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8, 0x3142, 1, EGL14.EGL_NONE), 0, configs, 0, 1, count, 0) && count[0] > 0) {
                "SHARED_ES3_RECORDABLE_CONFIG_UNAVAILABLE"
            }
            config = configs[0]
        } else config = shared.config
        context = EGL14.eglCreateContext(display, config, shared?.context ?: EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "SHARED_ES3_CONTEXT_FAILED" }
        pbuffer = EGL14.eglCreatePbufferSurface(display, config,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        check(pbuffer != EGL14.EGL_NO_SURFACE) { "SHARED_PBUFFER_FAILED" }
        if (surface != null) {
            window = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
            check(window != EGL14.EGL_NO_SURFACE) { "SHARED_WINDOW_FAILED" }
        }
        current()
    }
    fun current() {
        val surface = if (window != EGL14.EGL_NO_SURFACE) window else pbuffer
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "SHARED_MAKE_CURRENT_FAILED" }
    }
    fun attachWindow(surface: Surface) {
        check(window == EGL14.EGL_NO_SURFACE)
        window = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        check(window != EGL14.EGL_NO_SURFACE) { "SHARED_WINDOW_FAILED" }
        current()
    }
    fun detachWindow() {
        if(window == EGL14.EGL_NO_SURFACE)return
        check(EGL14.eglMakeCurrent(display,pbuffer,pbuffer,context)) { "SHARED_PBUFFER_CURRENT_FAILED" }
        check(EGL14.eglDestroySurface(display,window)) { "SHARED_WINDOW_CLOSE_FAILED" }
        window=EGL14.EGL_NO_SURFACE
    }
    fun swap(timestampNs: Long) {
        check(EGLExt.eglPresentationTimeANDROID(display, window, timestampNs)) { "SHARED_PRESENTATION_FAILED" }
        check(EGL14.eglSwapBuffers(display, window)) { "SHARED_SWAP_FAILED" }
    }
    fun finish() {
        if (context != EGL14.EGL_NO_CONTEXT && pbuffer != EGL14.EGL_NO_SURFACE) {
            current(); GLES20.glFinish(); ProbeGlDraw.checkGl()
        }
    }
    fun close() {
        if (closed) return
        if (!initialized && context == EGL14.EGL_NO_CONTEXT && pbuffer == EGL14.EGL_NO_SURFACE && window == EGL14.EGL_NO_SURFACE) {
            closed = true; return
        }
        check(EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT))
        if (window != EGL14.EGL_NO_SURFACE) { check(EGL14.eglDestroySurface(display, window)); window = EGL14.EGL_NO_SURFACE }
        if (pbuffer != EGL14.EGL_NO_SURFACE) { check(EGL14.eglDestroySurface(display, pbuffer)); pbuffer = EGL14.EGL_NO_SURFACE }
        if (context != EGL14.EGL_NO_CONTEXT) { check(EGL14.eglDestroyContext(display, context)); context = EGL14.EGL_NO_CONTEXT }
        if (initialized) { check(EGL14.eglTerminate(display)); initialized = false }
        check(EGL14.eglReleaseThread()); closed = true
    }
}

/** One instance per context/thread; no shared mutable GL program state between readers. */
internal class ProbeGlDraw {
    private val programs = ArrayList<Int>()
    private val vertices = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0)
    }
    fun program(fragment: String): Int {
        fun shader(kind: Int, text: String): Int {
            val id = GLES20.glCreateShader(kind)
            GLES20.glShaderSource(id, text); GLES20.glCompileShader(id)
            val ok = IntArray(1); GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) { GLES20.glDeleteShader(id); error("SHARED_SHADER_COMPILE_FAILED") }
            return id
        }
        val vertex = shader(GLES20.GL_VERTEX_SHADER, "attribute vec2 position; void main(){gl_Position=vec4(position,0.0,1.0);}")
        var frag = 0; var p = 0
        try {
            frag = shader(GLES20.GL_FRAGMENT_SHADER, fragment); p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, vertex); GLES20.glAttachShader(p, frag); GLES20.glLinkProgram(p)
            val ok = IntArray(1); GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
            check(ok[0] != 0) { "SHARED_PROGRAM_LINK_FAILED" }; programs += p; return p
        } catch (t: Throwable) { if (p != 0) GLES20.glDeleteProgram(p); throw t }
        finally { GLES20.glDeleteShader(vertex); if (frag != 0) GLES20.glDeleteShader(frag) }
    }
    fun quad(program: Int) {
        val attr = GLES20.glGetAttribLocation(program, "position")
        vertices.position(0); GLES20.glEnableVertexAttribArray(attr)
        GLES20.glVertexAttribPointer(attr, 2, GLES20.GL_FLOAT, false, 0, vertices)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4); GLES20.glDisableVertexAttribArray(attr)
    }
    fun source(program: Int, layout: StripRepackLayout, nonce: Int, frame: Int, pressure: Boolean) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, layout.input.width, layout.input.height); GLES20.glUseProgram(program)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "size"), layout.input.width.toFloat(), layout.input.height.toFloat())
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "strip"), layout.stripHeight.toFloat())
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "cell"), ProbeFramePattern.cellWidth(layout.input.width).toFloat())
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "dynamicFrame"), frame.toFloat())
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "pressure"), if (pressure) 1f else 0f)
        val bytes = (0..2).flatMap { region -> ProbeFramePattern.marker(nonce, frame, region).map { (it.toInt() and 255).toFloat() } }.toFloatArray()
        GLES20.glUniform1fv(GLES20.glGetUniformLocation(program, "code[0]"), 21, bytes, 0); quad(program)
    }
    fun repack(program: Int, layout: StripRepackLayout, texture: Int, framebuffer: Int) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        GLES20.glViewport(0, 0, layout.encoded.width, layout.encoded.height); GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "source"), 0)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "inputSize"), layout.input.width.toFloat(), layout.input.height.toFloat())
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "strip"), layout.stripHeight.toFloat()); quad(program)
    }
    fun close() { programs.forEach(GLES20::glDeleteProgram); programs.clear() }
    companion object {
        fun checkGl() { check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "SHARED_GL_OPERATION_FAILED" } }
        fun textureParameters(target: Int) {
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }
        fun fence(): Long {
            val sync = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
            check(sync != 0L) { "SHARED_FENCE_CREATE_FAILED" }; GLES20.glFlush(); checkGl(); return sync
        }
        fun waitFence(sync: Long, timeoutMs: Long = 500) {
            check(sync != 0L) { "SHARED_FENCE_MISSING" }
            val end = SystemClock.elapsedRealtime() + timeoutMs
            while (true) {
                when (GLES30.glClientWaitSync(sync, 0, 0)) {
                    GLES30.GL_ALREADY_SIGNALED, GLES30.GL_CONDITION_SATISFIED -> return
                    GLES30.GL_TIMEOUT_EXPIRED -> { check(SystemClock.elapsedRealtime() < end) { "SHARED_FENCE_TIMEOUT" }; Thread.sleep(1) }
                    else -> error("SHARED_FENCE_WAIT_FAILED")
                }
            }
        }
    }
}

/** Owner assigns the wrapper before allocate; names survive partial allocation failure. */
internal class ProbeGlTarget(val width: Int, val height: Int) {
    private val textureId = IntArray(1); private val framebufferId = IntArray(1)
    val texture get() = textureId[0]
    val framebuffer get() = framebufferId[0]
    fun allocate() {
        GLES20.glGenTextures(1, textureId, 0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        ProbeGlDraw.textureParameters(GLES20.GL_TEXTURE_2D)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glGenFramebuffers(1, framebufferId, 0); GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texture, 0)
        check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) { "SHARED_FBO_INCOMPLETE" }
        ProbeGlDraw.checkGl()
    }
    fun close() {
        GLES20.glDeleteFramebuffers(1, framebufferId, 0); GLES20.glDeleteTextures(1, textureId, 0)
        framebufferId[0] = 0; textureId[0] = 0
    }
}
