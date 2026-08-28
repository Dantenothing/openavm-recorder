package com.dante.zeekrcapabilitylab.service.recorder

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Bundle
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class PipelineProgress(
    val encodedFrameCount: Long,
    val encodedBytes: Long,
    val firstPresentationTimeUs: Long?,
    val lastPresentationTimeUs: Long?,
)

data class PipelineStopEvidence(
    val progress: PipelineProgress,
    val codecName: String,
    val actualFormat: String? = null,
)

/**
 * Bounded, allocation-light snapshot used only at lifecycle boundaries and on
 * failures. Frame counters are aggregated; no per-frame data is retained.
 */
data class PipelineRuntimeDiagnostics(
    val kind: String,
    val started: Boolean,
    val stopping: Boolean,
    val released: Boolean,
    val inputFramesReceived: Long? = null,
    val inputFramesRendered: Long? = null,
    val inputFramePending: Boolean? = null,
    val renderQueued: Boolean? = null,
    val muxerStarted: Boolean? = null,
    val drainThreadAlive: Boolean? = null,
    val runtimeFailure: String? = null,
) {
    fun eventPayload(prefix: String = "pipeline"): Map<String, String> = buildMap {
        put("${prefix}Kind", kind)
        put("${prefix}Started", started.toString())
        put("${prefix}Stopping", stopping.toString())
        put("${prefix}Released", released.toString())
        inputFramesReceived?.let { put("${prefix}InputFrames", it.toString()) }
        inputFramesRendered?.let { put("${prefix}RenderedFrames", it.toString()) }
        inputFramePending?.let { put("${prefix}InputFramePending", it.toString()) }
        renderQueued?.let { put("${prefix}RenderQueued", it.toString()) }
        muxerStarted?.let { put("${prefix}MuxerStarted", it.toString()) }
        drainThreadAlive?.let { put("${prefix}DrainAlive", it.toString()) }
        runtimeFailure?.let { put("${prefix}Failure", it) }
    }
}

interface RecordingPipeline {
    val cameraSurface: Surface
    val progress: PipelineProgress
    val diagnostics: PipelineRuntimeDiagnostics
    fun start()
    fun requestKeyFrame(): Boolean
    @Throws(Exception::class)
    fun stop(): PipelineStopEvidence
    fun release()
}

object RecordingPipelineFactory {
    fun create(
        outputFile: File,
        config: RecorderConfig,
        onRuntimeError: (String) -> Unit,
    ): RecordingPipeline = when {
        // Debug AVD cameras provide one ordinary stream. MediaRecorder is the
        // stable lifecycle test path for either user-selected product mode.
        config.emulatorTestSource -> MediaRecorderPipeline(
            outputFile = outputFile,
            config = config,
            onRuntimeError = onRuntimeError,
        )

        config.recordingMode == RecordingMode.SURROUND_360 -> MediaRecorderPipeline(
            outputFile = outputFile,
            config = config,
            onRuntimeError = onRuntimeError,
        )

        config.sourceKind == RecordingSourceKind.DIRECT_FRONT -> DirectFrontCodecPipeline(
            outputFile = outputFile,
            config = config,
            onRuntimeError = onRuntimeError,
        )

        config.sourceKind == RecordingSourceKind.COMPOSITE_CROP -> FrontCropCodecPipeline(
            outputFile = outputFile,
            config = config,
            calibration = requireNotNull(config.frontCalibration),
            onRuntimeError = onRuntimeError,
        )

        else -> error("Unsupported fail-closed pipeline configuration")
    }
}

private class MediaRecorderPipeline(
    outputFile: File,
    config: RecorderConfig,
    onRuntimeError: (String) -> Unit,
) : RecordingPipeline {
    private val recorder = MediaRecorder().apply {
        setVideoSource(MediaRecorder.VideoSource.SURFACE)
        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        setVideoSize(config.profile.size.width, config.profile.size.height)
        runCatching { setVideoFrameRate(30) }
        setVideoEncodingBitRate(config.profile.bitrateBps)
        setOutputFile(outputFile.absolutePath)
        setOnErrorListener { _, what, extra -> onRuntimeError("MEDIA_RECORDER_ERROR what=$what extra=$extra") }
        prepare()
    }
    private val started = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    override val cameraSurface: Surface = recorder.surface
    override val progress: PipelineProgress
        get() = PipelineProgress(0, 0, null, null)
    override val diagnostics: PipelineRuntimeDiagnostics
        get() = PipelineRuntimeDiagnostics(
            kind = "MEDIA_RECORDER",
            started = started.get(),
            stopping = false,
            released = released.get(),
        )

    override fun start() {
        recorder.start()
        started.set(true)
    }

    override fun stop(): PipelineStopEvidence {
        if (started.compareAndSet(true, false)) recorder.stop()
        return PipelineStopEvidence(progress, "MediaRecorder/H264")
    }

    override fun requestKeyFrame(): Boolean = false

    override fun release() {
        if (!released.compareAndSet(false, true)) return
        runCatching { recorder.reset() }
        runCatching { recorder.release() }
    }
}

private class DirectFrontCodecPipeline(
    outputFile: File,
    config: RecorderConfig,
    onRuntimeError: (String) -> Unit,
) : RecordingPipeline {
    private val encoder = SurfaceVideoEncoder(outputFile, requireNotNull(config.encoderProfile), onRuntimeError)
    override val cameraSurface: Surface get() = encoder.inputSurface
    override val progress: PipelineProgress get() = encoder.progress
    override val diagnostics: PipelineRuntimeDiagnostics
        get() = encoder.diagnostics("DIRECT_FRONT_CODEC")
    override fun start() = encoder.start()
    override fun requestKeyFrame(): Boolean = encoder.requestKeyFrame()
    override fun stop(): PipelineStopEvidence = encoder.stop()
    override fun release() = encoder.release()
}

private class FrontCropCodecPipeline(
    outputFile: File,
    config: RecorderConfig,
    calibration: FrontCalibration,
    onRuntimeError: (String) -> Unit,
) : RecordingPipeline {
    private val encoder = SurfaceVideoEncoder(outputFile, requireNotNull(config.encoderProfile), onRuntimeError)
    private val bridge: GlCropBridge

    init {
        bridge = try {
            GlCropBridge(
                sourceWidth = config.effectiveSourceProfile.size.width,
                sourceHeight = config.effectiveSourceProfile.size.height,
                encoderSurface = encoder.inputSurface,
                crop = calibration.crop,
                rotationDegrees = calibration.rotationDegrees,
                onFrameRendered = encoder::noteSubmittedFrame,
                onRuntimeError = onRuntimeError,
            )
        } catch (t: Throwable) {
            encoder.release()
            throw t
        }
    }
    override val cameraSurface: Surface get() = bridge.cameraSurface
    override val progress: PipelineProgress get() = encoder.progress
    override val diagnostics: PipelineRuntimeDiagnostics
        get() {
            val encoderDiagnostics = encoder.diagnostics("FRONT_CROP_CODEC")
            val bridgeDiagnostics = bridge.diagnostics
            return encoderDiagnostics.copy(
                inputFramesReceived = bridgeDiagnostics.receivedFrames,
                inputFramesRendered = bridgeDiagnostics.renderedFrames,
                inputFramePending = bridgeDiagnostics.framePending,
                renderQueued = bridgeDiagnostics.renderQueued,
                runtimeFailure = bridgeDiagnostics.runtimeFailure ?: encoderDiagnostics.runtimeFailure,
            )
        }

    override fun start() {
        encoder.start()
        bridge.start()
    }

    override fun requestKeyFrame(): Boolean = encoder.requestKeyFrame()

    override fun stop(): PipelineStopEvidence {
        bridge.stop()
        return encoder.stop()
    }

    override fun release() {
        bridge.release()
        encoder.release()
    }
}

private class SurfaceVideoEncoder(
    private val outputFile: File,
    private val profile: EncoderProfile,
    private val onRuntimeError: (String) -> Unit,
) {
    private val codec = MediaCodec.createByCodecName(profile.codecName)
    val inputSurface: Surface
    private val muxer: MediaMuxer
    private val started = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    private val failure = AtomicReference<Throwable?>(null)
    private val encodedFrames = AtomicLong(0)
    private val encodedBytes = AtomicLong(0)
    private val firstPtsUs = AtomicLong(NO_TIMESTAMP)
    private val lastPtsUs = AtomicLong(NO_TIMESTAMP)
    private val submittedTimestampNs = AtomicLong(NO_TIMESTAMP)
    private val muxerHasStarted = AtomicBoolean(false)
    private var drainThread: Thread? = null
    @Volatile private var actualFormat: String? = null

    init {
        val format = MediaFormat.createVideoFormat(profile.codecMime, profile.width, profile.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, profile.requestedBitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, profile.frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            profile.profile?.let { setInteger(MediaFormat.KEY_PROFILE, it) }
            profile.level?.let { setInteger(MediaFormat.KEY_LEVEL, it) }
        }
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec.createInputSurface()
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (t: Throwable) {
            runCatching { codec.release() }
            throw t
        }
    }

    val progress: PipelineProgress
        get() = PipelineProgress(
            encodedFrameCount = encodedFrames.get(),
            encodedBytes = encodedBytes.get(),
            firstPresentationTimeUs = firstPtsUs.get().takeUnless { it == NO_TIMESTAMP },
            lastPresentationTimeUs = lastPtsUs.get().takeUnless { it == NO_TIMESTAMP },
        )

    fun diagnostics(kind: String): PipelineRuntimeDiagnostics = PipelineRuntimeDiagnostics(
        kind = kind,
        started = started.get(),
        stopping = stopping.get(),
        released = released.get(),
        muxerStarted = muxerHasStarted.get(),
        drainThreadAlive = drainThread?.isAlive == true,
        runtimeFailure = failure.get()?.let { it.message ?: it.javaClass.simpleName },
    )

    fun noteSubmittedFrame(timestampNs: Long) {
        submittedTimestampNs.set(timestampNs)
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        codec.start()
        drainThread = Thread(::drain, "front-encoder-drain").also { it.start() }
    }

    fun stop(): PipelineStopEvidence {
        if (started.get() && stopping.compareAndSet(false, true)) {
            codec.signalEndOfInputStream()
            val thread = drainThread
            if (thread != null) {
                thread.join(DRAIN_TIMEOUT_MS)
                if (thread.isAlive) throw IllegalStateException("ENCODER_DRAIN_TIMEOUT")
            }
        }
        failure.get()?.let { throw IllegalStateException("ENCODER_RUNTIME_ERROR", it) }
        return PipelineStopEvidence(progress, profile.codecName, actualFormat)
    }

    fun requestKeyFrame(): Boolean {
        if (!started.get() || stopping.get()) return false
        return runCatching {
            codec.setParameters(
                Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                },
            )
            true
        }.getOrDefault(false)
    }

    private fun drain() {
        var muxerStarted = false
        var trackIndex = -1
        val info = MediaCodec.BufferInfo()
        try {
            while (true) {
                when (val index = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> if (stopping.get()) continue
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(!muxerStarted) { "ENCODER_FORMAT_CHANGED_TWICE" }
                        val format = codec.outputFormat
                        actualFormat = format.toString()
                        trackIndex = muxer.addTrack(format)
                        muxer.start()
                        muxerStarted = true
                        muxerHasStarted.set(true)
                    }
                    else -> if (index >= 0) {
                        val buffer = codec.getOutputBuffer(index)
                            ?: throw IllegalStateException("ENCODER_OUTPUT_BUFFER_MISSING")
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0) {
                            check(muxerStarted) { "ENCODER_DATA_BEFORE_FORMAT" }
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            muxer.writeSampleData(trackIndex, buffer, info)
                            encodedFrames.incrementAndGet()
                            encodedBytes.addAndGet(info.size.toLong())
                            firstPtsUs.compareAndSet(NO_TIMESTAMP, info.presentationTimeUs)
                            lastPtsUs.set(info.presentationTimeUs)
                        }
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(index, false)
                        if (eos) break
                    }
                }
            }
        } catch (t: Throwable) {
            failure.compareAndSet(null, t)
            onRuntimeError("ENCODER_RUNTIME_ERROR: ${t.message ?: t.javaClass.simpleName}")
        } finally {
            if (muxerStarted) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        if (started.get() && !stopping.get()) runCatching { stop() }
        runCatching { inputSurface.release() }
        runCatching { codec.stop() }
        runCatching { codec.release() }
        if (!started.get()) runCatching { muxer.release() }
    }

    private companion object {
        const val NO_TIMESTAMP = -1L
        const val DEQUEUE_TIMEOUT_US = 10_000L
        const val DRAIN_TIMEOUT_MS = 5_000L
    }
}

private class GlCropBridge(
    private val sourceWidth: Int,
    private val sourceHeight: Int,
    private val encoderSurface: Surface,
    private val crop: NormalizedCropRect,
    private val rotationDegrees: Int,
    private val onFrameRendered: (Long) -> Unit,
    private val onRuntimeError: (String) -> Unit,
) {
    private val thread = HandlerThread("front-crop-gl").also { it.start() }
    private val handler = Handler(thread.looper)
    private val initialized = CountDownLatch(1)
    private val released = AtomicBoolean(false)
    private val renderQueued = AtomicBoolean(false)
    private val receivedFrames = AtomicLong(0)
    private val renderedFrames = AtomicLong(0)
    private val framePending = AtomicBoolean(false)
    private val runtimeFailure = AtomicReference<String?>(null)
    @Volatile private var active = false
    private var eglDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface = EGL14.EGL_NO_SURFACE
    private var textureId = 0
    private var program = 0
    private var surfaceTexture: SurfaceTexture? = null
    lateinit var cameraSurface: Surface
        private set

    val diagnostics: GlBridgeDiagnostics
        get() = GlBridgeDiagnostics(
            receivedFrames = receivedFrames.get(),
            renderedFrames = renderedFrames.get(),
            framePending = framePending.get(),
            renderQueued = renderQueued.get(),
            runtimeFailure = runtimeFailure.get(),
        )

    init {
        handler.post {
            try {
                initializeGl()
            } catch (t: Throwable) {
                val message = "FRONT_CROP_GL_INIT_FAILED: ${t.message ?: t.javaClass.simpleName}"
                runtimeFailure.compareAndSet(null, message)
                onRuntimeError(message)
            } finally {
                initialized.countDown()
            }
        }
        val completed = initialized.await(5, TimeUnit.SECONDS)
        if (!completed || !::cameraSurface.isInitialized) {
            release()
            error(if (completed) "FRONT_CROP_GL_INIT_FAILED" else "FRONT_CROP_GL_INIT_TIMEOUT")
        }
    }

    fun start() {
        val activated = CountDownLatch(1)
        val activation = Runnable {
            try {
                active = true
                schedulePendingFrame()
            } finally {
                activated.countDown()
            }
        }
        if (thread.looper.thread === Thread.currentThread()) {
            activation.run()
            return
        }
        check(handler.post(activation)) { "FRONT_CROP_GL_START_REJECTED" }
        check(activated.await(START_TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "FRONT_CROP_GL_START_TIMEOUT" }
    }

    fun stop() {
        val latch = CountDownLatch(1)
        handler.post {
            active = false
            latch.countDown()
        }
        check(latch.await(2, TimeUnit.SECONDS)) { "FRONT_CROP_GL_STOP_TIMEOUT" }
    }

    private fun initializeGl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "EGL display unavailable" }
        val versions = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, versions, 0, versions, 1)) { "EGL initialize failed" }
        val configAttributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(eglDisplay, configAttributes, 0, configs, 0, 1, count, 0)) {
            "EGL config selection failed"
        }
        val eglConfig = requireNotNull(configs[0])
        eglContext = EGL14.eglCreateContext(
            eglDisplay,
            eglConfig,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0,
        )
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "EGL context creation failed" }
        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay,
            eglConfig,
            encoderSurface,
            intArrayOf(EGL14.EGL_NONE),
            0,
        )
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "EGL encoder surface creation failed" }
        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            "EGL makeCurrent failed"
        }

        textureId = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        val texture = SurfaceTexture(textureId).apply {
            setDefaultBufferSize(sourceWidth, sourceHeight)
            setOnFrameAvailableListener({ queueLatestFrame() }, handler)
        }
        surfaceTexture = texture
        cameraSurface = Surface(texture)
    }

    private fun queueLatestFrame() {
        receivedFrames.incrementAndGet()
        framePending.set(true)
        schedulePendingFrame()
    }

    /**
     * Keeps one latest-frame token even when the first callback beats [start].
     * SurfaceTexture may stop notifying until that pending buffer is consumed,
     * so dropping the inactive callback can otherwise wedge the producer forever.
     */
    private fun schedulePendingFrame() {
        if (!GlFrameQueuePolicy.shouldSchedule(
                active = active,
                released = released.get(),
                framePending = framePending.get(),
                renderQueued = renderQueued.get(),
            ) || !renderQueued.compareAndSet(false, true)
        ) {
            return
        }
        val posted = handler.post {
            try {
                if (active && !released.get() && framePending.compareAndSet(true, false)) {
                    renderFrame()
                }
            } catch (t: Throwable) {
                active = false
                val message = "FRONT_CROP_GL_RUNTIME_ERROR: ${t.message ?: t.javaClass.simpleName}"
                runtimeFailure.compareAndSet(null, message)
                onRuntimeError(message)
            } finally {
                renderQueued.set(false)
                if (framePending.get()) schedulePendingFrame()
            }
        }
        if (!posted) {
            renderQueued.set(false)
            if (!released.get()) {
                val message = "FRONT_CROP_GL_RENDER_REJECTED"
                runtimeFailure.compareAndSet(null, message)
                onRuntimeError(message)
            }
        }
    }

    private fun renderFrame() {
        val texture = surfaceTexture ?: return
        texture.updateTexImage()
        val textureMatrix = FloatArray(16)
        texture.getTransformMatrix(textureMatrix)
        val vertices = FrontTextureCoordinates.interleaved(crop, rotationDegrees)
        val buffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertices)
            .apply { position(0) }
        GLES20.glViewport(0, 0, FrontEncoderProfilePolicy.TARGET_SIZE, FrontEncoderProfilePolicy.TARGET_SIZE)
        GLES20.glUseProgram(program)
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        val textureCoordinate = GLES20.glGetAttribLocation(program, "aTextureCoord")
        val matrix = GLES20.glGetUniformLocation(program, "uTextureMatrix")
        buffer.position(0)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, buffer)
        buffer.position(2)
        GLES20.glEnableVertexAttribArray(textureCoordinate)
        GLES20.glVertexAttribPointer(textureCoordinate, 2, GLES20.GL_FLOAT, false, 16, buffer)
        GLES20.glUniformMatrix4fv(matrix, 1, false, textureMatrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        val timestampNs = texture.timestamp
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, timestampNs)
        check(EGL14.eglSwapBuffers(eglDisplay, eglSurface)) { "EGL swap failed" }
        renderedFrames.incrementAndGet()
        onFrameRendered(timestampNs)
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        val latch = CountDownLatch(1)
        handler.post {
            active = false
            if (::cameraSurface.isInitialized) runCatching { cameraSurface.release() }
            runCatching { surfaceTexture?.release() }
            surfaceTexture = null
            if (program != 0) GLES20.glDeleteProgram(program)
            if (textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            )
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) EGL14.eglTerminate(eglDisplay)
            latch.countDown()
        }
        latch.await(2, TimeUnit.SECONDS)
        thread.quitSafely()
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        fun compile(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            check(status[0] != 0) { GLES20.glGetShaderInfoLog(shader) }
            return shader
        }
        val vertex = compile(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        return GLES20.glCreateProgram().also { result ->
            GLES20.glAttachShader(result, vertex)
            GLES20.glAttachShader(result, fragment)
            GLES20.glLinkProgram(result)
            val status = IntArray(1)
            GLES20.glGetProgramiv(result, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] != 0) { GLES20.glGetProgramInfoLog(result) }
            GLES20.glDeleteShader(vertex)
            GLES20.glDeleteShader(fragment)
        }
    }

    private companion object {
        const val EGL_RECORDABLE_ANDROID = 0x3142
        const val START_TIMEOUT_MS = 2_000L
        const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTextureCoord;
            uniform mat4 uTextureMatrix;
            varying vec2 vTextureCoord;
            void main() {
                gl_Position = aPosition;
                vTextureCoord = (uTextureMatrix * vec4(aTextureCoord, 0.0, 1.0)).xy;
            }
        """
        const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES sTexture;
            varying vec2 vTextureCoord;
            void main() {
                gl_FragColor = texture2D(sTexture, vTextureCoord);
            }
        """
    }
}

private data class GlBridgeDiagnostics(
    val receivedFrames: Long,
    val renderedFrames: Long,
    val framePending: Boolean,
    val renderQueued: Boolean,
    val runtimeFailure: String?,
)

/** Pure guard for the single-token latest-frame queue used by [GlCropBridge]. */
object GlFrameQueuePolicy {
    fun shouldSchedule(
        active: Boolean,
        released: Boolean,
        framePending: Boolean,
        renderQueued: Boolean,
    ): Boolean = active && !released && framePending && !renderQueued
}

/** Pure crop/rotation math in triangle-strip order: bottom-left, bottom-right, top-left, top-right. */
object FrontTextureCoordinates {
    fun interleaved(crop: NormalizedCropRect, rotationDegrees: Int): FloatArray {
        require(crop.validate().isEmpty()) { "invalid crop" }
        require(rotationDegrees in setOf(0, 90, 180, 270)) { "invalid rotation" }
        val outputCorners = listOf(0f to 1f, 1f to 1f, 0f to 0f, 1f to 0f)
        val positions = listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f)
        return FloatArray(16).also { values ->
            outputCorners.forEachIndexed { index, (x, y) ->
                val (rx, ry) = when (rotationDegrees) {
                    0 -> x to y
                    90 -> y to (1f - x)
                    180 -> (1f - x) to (1f - y)
                    else -> (1f - y) to x
                }
                values[index * 4] = positions[index].first
                values[index * 4 + 1] = positions[index].second
                values[index * 4 + 2] = crop.left + rx * (crop.right - crop.left)
                values[index * 4 + 3] = crop.top + ry * (crop.bottom - crop.top)
            }
        }
    }
}
