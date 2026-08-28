package com.dante.zeekrbridge.player

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

internal data class FourLaneGlDiagnostic(
    val programId: Int,
    val textureId: Int,
    val sourceAttached: Boolean,
    val vertexShaderStatus: String,
    val fragmentShaderStatus: String,
    val programLinkStatus: String,
    val shaderLog: String,
    val attributeLocation: Int,
    val uniformLocations: String,
    val drawCalls: Long,
    val updateTexImageSuccesses: Long,
    val lastExitReason: String,
    val glVendor: String,
    val glRenderer: String,
    val glVersion: String,
)

/**
 * Single-decoder four-lane viewer. One SurfaceTexture (from MediaPlayer) is
 * sampled by a GLES2 shader that crops the four lanes and renders a 2x2 grid
 * or one lane fullscreen.
 */
class FourLaneGlView(context: Context) : GLSurfaceView(context) {

    companion object {
        const val MODE_GRID = 0
        const val MODE_LANE_1 = 1
        const val MODE_LANE_2 = 2
        const val MODE_LANE_3 = 3
        const val MODE_LANE_4 = 4
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val glRenderer = FourLaneRenderer { requestRender() }
    private var sourceCallback: ((SurfaceTexture) -> Unit)? = null
    private var firstFrameCallback: (() -> Unit)? = null
    private var surfaceFrameCallback: ((Long) -> Unit)? = null
    private var glMaxTextureCallback: ((Int) -> Unit)? = null
    private var glDiagnosticCallback: ((FourLaneGlDiagnostic) -> Unit)? = null
    private var renderErrorCallback: ((String) -> Unit)? = null

    init {
        setEGLContextClientVersion(2)
        setRenderer(glRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun setSource(source: SurfaceTexture?) {
        glRenderer.setSource(source)
        requestRender()
    }

    fun setMode(mode: Int, order: List<Int> = listOf(1, 2, 3, 4)) {
        glRenderer.setMode(mode, order)
        requestRender()
    }

    fun setVideoSize(width: Int, height: Int) {
        glRenderer.setVideoSize(width, height)
        requestRender()
    }

    internal fun setPlaybackCallbacks(
        onFirstFrame: (() -> Unit)?,
        onSurfaceFrame: ((Long) -> Unit)?,
        onGlMaxTextureSize: ((Int) -> Unit)?,
        onGlDiagnostic: ((FourLaneGlDiagnostic) -> Unit)?,
        onRenderError: ((String) -> Unit)?,
    ) {
        firstFrameCallback = onFirstFrame
        surfaceFrameCallback = onSurfaceFrame
        glMaxTextureCallback = onGlMaxTextureSize
        glDiagnosticCallback = onGlDiagnostic
        renderErrorCallback = onRenderError
        glRenderer.setCallbacks(
            onFirstFrame = { mainHandler.post { firstFrameCallback?.invoke() } },
            onSurfaceFrame = { count -> mainHandler.post { surfaceFrameCallback?.invoke(count) } },
            onGlMaxTextureSize = { size -> mainHandler.post { glMaxTextureCallback?.invoke(size) } },
            onGlDiagnostic = { snapshot -> mainHandler.post { glDiagnosticCallback?.invoke(snapshot) } },
            onRenderError = { message -> mainHandler.post { renderErrorCallback?.invoke(message) } },
        )
    }

    fun createSurfaceTexture(callback: (SurfaceTexture) -> Unit) {
        sourceCallback = callback
        queueEvent {
            glRenderer.provideSurfaceTexture { st ->
                mainHandler.post { sourceCallback?.invoke(st) }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        glRenderer.setSource(null)
    }
}

private class FourLaneRenderer(
    private val onFrameReady: () -> Unit,
) : GLSurfaceView.Renderer {

    private var source: SurfaceTexture? = null
    private var mode = FourLaneGlView.MODE_GRID
    private var order = intArrayOf(1, 2, 3, 4)
    private var videoWidth = 1280
    private var videoHeight = 5140
    private var viewWidth = 1
    private var viewHeight = 1

    private var program = 0
    private var aPosition = 0
    private var uCellRect = 0
    private var uWindow = 0
    private var uTexMatrix = 0
    private var uTexture = 0

    // GLES requires direct, native-order client buffers. A heap FloatBuffer
    // can accept playback while leaving this view permanently black.
    private val vertexData = createFourLaneVertexBuffer()
    private val texMatrix = FloatArray(16)
    private val glContextGate = FourLaneGlContextGate()
    private var texId = 0
    private var created: SurfaceTexture? = null
    private var pendingSurfaceTextureEmit: ((SurfaceTexture) -> Unit)? = null
    private var firstFrameDrawn = false
    private var textureReady = false
    @Volatile
    private var frameAvailable = false
    @Volatile
    private var surfaceFrameCount = 0L
    @Volatile
    private var glMaxTextureSize = 0
    private var onFirstFrame: (() -> Unit)? = null
    private var onSurfaceFrame: ((Long) -> Unit)? = null
    private var onGlMaxTextureSize: ((Int) -> Unit)? = null
    private var onGlDiagnostic: ((FourLaneGlDiagnostic) -> Unit)? = null
    private var onRenderError: ((String) -> Unit)? = null
    private var lastRenderError: String? = null
    private var vertexShaderStatus = "NOT_RUN"
    private var fragmentShaderStatus = "NOT_RUN"
    private var programLinkStatus = "NOT_RUN"
    private var vertexShaderLog = ""
    private var fragmentShaderLog = ""
    private var programLinkLog = ""
    private var drawCallCount = 0L
    private var updateTexImageSuccessCount = 0L
    private var lastExitReason = "CREATED"
    private var lastEmittedExitReason = ""
    private var glVendor = "unknown"
    private var glDeviceRenderer = "unknown"
    private var glVersion = "unknown"

    fun setCallbacks(
        onFirstFrame: (() -> Unit)?,
        onSurfaceFrame: ((Long) -> Unit)?,
        onGlMaxTextureSize: ((Int) -> Unit)?,
        onGlDiagnostic: ((FourLaneGlDiagnostic) -> Unit)?,
        onRenderError: ((String) -> Unit)?,
    ) {
        this.onFirstFrame = onFirstFrame
        this.onSurfaceFrame = onSurfaceFrame
        this.onGlMaxTextureSize = onGlMaxTextureSize
        this.onGlDiagnostic = onGlDiagnostic
        this.onRenderError = onRenderError
        if (surfaceFrameCount > 0L) onSurfaceFrame?.invoke(surfaceFrameCount)
        if (glMaxTextureSize > 0) onGlMaxTextureSize?.invoke(glMaxTextureSize)
        onGlDiagnostic?.invoke(diagnosticSnapshot())
        lastRenderError?.let { onRenderError?.invoke(it) }
    }

    fun setSource(source: SurfaceTexture?) {
        this.source = source
        firstFrameDrawn = false
        textureReady = false
        frameAvailable = false
        surfaceFrameCount = 0L
        lastExitReason = if (source == null) "SOURCE_CLEARED" else "SOURCE_SET"
        emitDiagnostic(force = true)
    }

    fun setMode(mode: Int, order: List<Int>) {
        this.mode = mode.coerceIn(FourLaneGlView.MODE_GRID, FourLaneGlView.MODE_LANE_4)
        if (order.size == 4 && order.toSet() == setOf(1, 2, 3, 4)) {
            this.order = order.toIntArray()
        }
    }

    fun setVideoSize(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            videoWidth = width
            videoHeight = height
        }
    }

    fun provideSurfaceTexture(emit: (SurfaceTexture) -> Unit) {
        created?.let {
            emit(it)
            return
        }
        pendingSurfaceTextureEmit = emit
        if (!glContextGate.requestTextureCreation()) {
            lastExitReason = "TEXTURE_WAITING_FOR_GL_CONTEXT"
            emitDiagnostic(force = true)
            return
        }
        createAndDeliverSurfaceTexture()
    }

    private fun createAndDeliverSurfaceTexture() {
        val emit = pendingSurfaceTextureEmit ?: return
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        texId = ids[0]
        if (texId == 0) {
            reportRenderError("External GL texture creation returned id 0")
            lastExitReason = "TEXTURE_CREATION_FAILED"
            emitDiagnostic(force = true)
            return
        }
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        val st = SurfaceTexture(texId)
        st.setOnFrameAvailableListener({
            surfaceFrameCount += 1L
            frameAvailable = true
            onSurfaceFrame?.invoke(surfaceFrameCount)
            onFrameReady()
        }, null)
        created = st
        pendingSurfaceTextureEmit = null
        glContextGate.onTextureCreated()
        lastExitReason = "TEXTURE_CREATED_$texId"
        emitDiagnostic(force = true)
        emit(st)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        glVendor = GLES20.glGetString(GLES20.GL_VENDOR).orEmpty().ifBlank { "unknown" }
        glDeviceRenderer = GLES20.glGetString(GLES20.GL_RENDERER).orEmpty().ifBlank { "unknown" }
        glVersion = GLES20.glGetString(GLES20.GL_VERSION).orEmpty().ifBlank { "unknown" }
        val maxTexture = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxTexture, 0)
        glMaxTextureSize = maxTexture[0]
        onGlMaxTextureSize?.invoke(glMaxTextureSize)
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        if (program != 0) {
            aPosition = GLES20.glGetAttribLocation(program, "aPosition")
            uCellRect = GLES20.glGetUniformLocation(program, "uCellRect")
            uWindow = GLES20.glGetUniformLocation(program, "uWindow")
            uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
            uTexture = GLES20.glGetUniformLocation(program, "uTexture")
        }
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        if (program == 0) reportRenderError("GL shader initialization failed")
        lastExitReason = if (program == 0) "PROGRAM_ZERO" else "PROGRAM_READY"
        emitDiagnostic(force = true)
        if (glContextGate.onGlContextCreated()) {
            createAndDeliverSurfaceTexture()
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewWidth = width.coerceAtLeast(1)
        viewHeight = height.coerceAtLeast(1)
        GLES20.glViewport(0, 0, viewWidth, viewHeight)
        lastExitReason = "SURFACE_CHANGED_${viewWidth}x${viewHeight}"
        emitDiagnostic(force = true)
    }

    override fun onDrawFrame(gl: GL10?) {
        drawCallCount += 1L
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val tex = source
        if (tex == null) {
            finishDraw("NO_SOURCE")
            return
        }
        if (texId == 0) {
            finishDraw("NO_TEXTURE")
            return
        }
        if (program == 0) {
            finishDraw("PROGRAM_ZERO")
            return
        }
        if (frameAvailable) {
            try {
                tex.updateTexImage()
                tex.getTransformMatrix(texMatrix)
                frameAvailable = false
                textureReady = true
                updateTexImageSuccessCount += 1L
            } catch (t: Throwable) {
                reportRenderError("updateTexImage failed: ${t.javaClass.simpleName}: ${t.message.orEmpty()}")
                finishDraw("UPDATE_TEX_IMAGE_FAILED", force = true)
                return
            }
        }
        if (!textureReady) {
            finishDraw("NO_FRAME")
            return
        }
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glUniform1i(uTexture, 0)
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
        vertexData.position(0)
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 8, vertexData)

        if (mode == FourLaneGlView.MODE_GRID) {
            for (row in 0..1) {
                for (col in 0..1) {
                    val slot = row * 2 + col
                    drawCell(col, row, order[slot])
                }
            }
        } else {
            drawCell(-1, -1, mode)
        }
        GLES20.glDisableVertexAttribArray(aPosition)
        val glError = GLES20.glGetError()
        if (glError != GLES20.GL_NO_ERROR) {
            reportRenderError("GL error 0x${glError.toString(16)}")
            finishDraw("DRAW_GL_ERROR_0x${glError.toString(16)}", force = true)
        } else if (!firstFrameDrawn) {
            firstFrameDrawn = true
            onFirstFrame?.invoke()
            finishDraw("DRAW_OK", force = true)
        } else {
            finishDraw("DRAW_OK")
        }
    }

    private fun drawCell(col: Int, row: Int, sourceLane: Int) {
        val full = col == -1 && row == -1
        val window = FourLaneTextureLayout.windowForLane(
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            lane = sourceLane.coerceIn(1, 4),
        )
        GLES20.glUniform4f(uWindow, window.u, window.v, window.width, window.height)

        val baseWpx = if (full) viewWidth.toFloat() else viewWidth / 2f
        val baseHpx = if (full) viewHeight.toFloat() else viewHeight / 2f
        val targetAspect = baseWpx / baseHpx
        val fittedWpx: Float
        val fittedHpx: Float
        if (targetAspect > window.laneAspect) {
            fittedHpx = baseHpx
            fittedWpx = fittedHpx * window.laneAspect
        } else {
            fittedWpx = baseWpx
            fittedHpx = fittedWpx / window.laneAspect
        }
        val baseLeftPx = if (full) 0f else col * baseWpx
        val baseTopPx = if (full) 0f else row * baseHpx
        val leftPx = baseLeftPx + (baseWpx - fittedWpx) / 2f
        val topPx = baseTopPx + (baseHpx - fittedHpx) / 2f
        val cellX = -1f + 2f * leftPx / viewWidth
        val cellY = 1f - 2f * (topPx + fittedHpx) / viewHeight
        val cellW = 2f * fittedWpx / viewWidth
        val cellH = 2f * fittedHpx / viewHeight
        GLES20.glUniform4f(uCellRect, cellX, cellY, cellW, cellH)

        vertexData.position(0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun reportRenderError(message: String) {
        if (lastRenderError == message) return
        lastRenderError = message
        onRenderError?.invoke(message)
    }

    private fun finishDraw(reason: String, force: Boolean = false) {
        lastExitReason = reason
        emitDiagnostic(force)
    }

    private fun emitDiagnostic(force: Boolean = false) {
        val reasonChanged = lastExitReason != lastEmittedExitReason
        if (!force && !reasonChanged && drawCallCount > 3L && drawCallCount % 30L != 0L) return
        lastEmittedExitReason = lastExitReason
        onGlDiagnostic?.invoke(diagnosticSnapshot())
    }

    private fun diagnosticSnapshot(): FourLaneGlDiagnostic = FourLaneGlDiagnostic(
        programId = program,
        textureId = texId,
        sourceAttached = source != null,
        vertexShaderStatus = vertexShaderStatus,
        fragmentShaderStatus = fragmentShaderStatus,
        programLinkStatus = programLinkStatus,
        shaderLog = listOf(vertexShaderLog, fragmentShaderLog, programLinkLog)
            .filter { it.isNotBlank() }
            .joinToString(" | ")
            .ifBlank { "none" },
        attributeLocation = aPosition,
        uniformLocations = "$uCellRect/$uWindow/$uTexMatrix/$uTexture",
        drawCalls = drawCallCount,
        updateTexImageSuccesses = updateTexImageSuccessCount,
        lastExitReason = lastExitReason,
        glVendor = glVendor,
        glRenderer = glDeviceRenderer,
        glVersion = glVersion,
    )

    private fun createProgram(vertex: String, fragment: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertex, "VERTEX") ?: return 0
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragment, "FRAGMENT")
        if (fs == null) {
            GLES20.glDeleteShader(vs)
            return 0
        }
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val link = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, link, 0)
        programLinkLog = GLES20.glGetProgramInfoLog(program).orEmpty()
        if (link[0] == 0) {
            programLinkStatus = "FAILED"
            GLES20.glDeleteProgram(program)
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
            return 0
        }
        programLinkStatus = "OK"
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return program
    }

    private fun compileShader(type: Int, source: String, stage: String): Int? {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compile = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compile, 0)
        val log = GLES20.glGetShaderInfoLog(shader).orEmpty()
        if (stage == "VERTEX") vertexShaderLog = log else fragmentShaderLog = log
        if (compile[0] == 0) {
            if (stage == "VERTEX") vertexShaderStatus = "FAILED" else fragmentShaderStatus = "FAILED"
            GLES20.glDeleteShader(shader)
            return null
        }
        if (stage == "VERTEX") vertexShaderStatus = "OK" else fragmentShaderStatus = "OK"
        return shader
    }

    companion object {
        private const val VERTEX_SHADER = """
            attribute vec2 aPosition;
            varying vec2 vLocalUv;
            uniform vec4 uCellRect;
            uniform vec4 uWindow;
            void main() {
              vec2 p = uCellRect.xy + uCellRect.zw * (aPosition * 0.5 + 0.5);
              gl_Position = vec4(p, 0.0, 1.0);
              vLocalUv = uWindow.xy + uWindow.zw * (aPosition * 0.5 + 0.5);
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vLocalUv;
            uniform samplerExternalOES uTexture;
            uniform mat4 uTexMatrix;
            void main() {
              vec2 uv = (uTexMatrix * vec4(vLocalUv, 0.0, 1.0)).xy;
              gl_FragColor = texture2D(uTexture, uv);
            }
        """
    }
}
