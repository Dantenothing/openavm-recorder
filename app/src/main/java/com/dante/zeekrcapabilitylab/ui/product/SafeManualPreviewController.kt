package com.dante.zeekrcapabilitylab.ui.product

import com.dante.zeekrcapabilitylab.util.Utils
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.view.TextureView
import com.dante.zeekrcapabilitylab.BuildConfig
import com.dante.zeekrcapabilitylab.sentry.CanaryCameraOpenAdapter
import com.dante.zeekrcapabilitylab.service.recorder.CaptureCleanupRuntime
import com.dante.zeekrcapabilitylab.service.recorder.CaptureCloseTransaction
import com.dante.zeekrcapabilitylab.service.recorder.CaptureCloseResources
import com.dante.zeekrcapabilitylab.service.recorder.HandlerCloseDispatcher
import com.dante.zeekrcapabilitylab.data.Categories
import com.dante.zeekrcapabilitylab.event.EventLogger
import com.dante.zeekrcapabilitylab.probe.camera.ProfileSize
import com.dante.zeekrcapabilitylab.product.CompositePreviewSizePolicy
import com.dante.zeekrcapabilitylab.service.recorder.RecordingLayoutKind
import com.dante.zeekrcapabilitylab.service.recorder.RecordingSourceRole
import com.dante.zeekrcapabilitylab.service.recorder.SessionSourceSnapshot
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class ManualPreviewState(
    val active: Boolean = false,
    val firstFrame: Boolean = false,
    val cameraId: String? = null,
    val previewSize: Size? = null,
    val forcedBufferSize: Size? = null,
    val fallbackUsed: Boolean = false,
    val sourceRole: RecordingSourceRole? = null,
    val layoutKind: RecordingLayoutKind? = null,
    val message: String = Utils.t("Preview is off by default", "预览默认关闭"),
    val error: String? = null,
)

/**
 * User-triggered camera preview feeding one ordinary TextureView.
 *
 * The ordinary TextureView producer path remains unchanged. When camera 2
 * explicitly declares 1280x5140 for SurfaceTexture, the controller makes one
 * guarded high-resolution attempt. Configuration failure or a missing first
 * frame restores the TextureView-sized buffer and retries the proven stable
 * path once.
 */
class SafeManualPreviewController(context: Context) {
    private val appContext = context.applicationContext
    private val generation = AtomicLong(0L)
    private val _state = MutableStateFlow(ManualPreviewState())
    val state: StateFlow<ManualPreviewState> = _state.asStateFlow()

    @Volatile
    private var requested = false
    @Volatile
    private var requestedSource: SessionSourceSnapshot? = null
    @Volatile
    private var released = false
    @Volatile
    private var textureView: TextureView? = null
    @Volatile
    private var recorderSurfaceHandedOff = false
    @Volatile
    private var configuredTextureBufferSize: Size? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    // Accessed only on cameraHandler.
    private var closeTransaction: CaptureCloseTransaction? = null
    private var opening = false
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var openWatchdog: Runnable? = null
    private var sessionWatchdog: Runnable? = null

    /** Called when the UI destroys a SurfaceTexture already handed to the recorder. */
    var onRecorderPreviewSurfaceDestroyed: (() -> Unit)? = null

    /** Called when a newly composed TextureView can be handed to an active recorder. */
    var onRecorderPreviewSurfaceAvailable: (() -> Unit)? = null

    /** Attaches the proven ordinary TextureView path. Does not auto-start Camera2. */
    fun attach(view: TextureView) {
        if (released) return
        textureView = view
        view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                texture: SurfaceTexture,
                width: Int,
                height: Int,
            ) {
                if (requested && textureView === view) openIfReady()
                if (!requested && textureView === view) onRecorderPreviewSurfaceAvailable?.invoke()
            }

            override fun onSurfaceTextureSizeChanged(
                texture: SurfaceTexture,
                width: Int,
                height: Int,
            ) = Unit

            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                if (textureView === view) {
                    val recorderLosesPreview = recorderSurfaceHandedOff
                    recorderSurfaceHandedOff = false
                    if (recorderLosesPreview) {
                        requested = false
                        _state.value = ManualPreviewState()
                    } else {
                        stopPreview()
                    }
                    textureView = null
                    configuredTextureBufferSize = null
                    if (recorderLosesPreview) onRecorderPreviewSurfaceDestroyed?.invoke()
                }
                return true
            }

            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
                val current = _state.value
                if ((requested || recorderSurfaceHandedOff) && current.active && !current.firstFrame) {
                    cancelSessionWatchdog()
                    _state.value = current.copy(
                        firstFrame = true,
                        message = Utils.t("Live preview is ready", "实时原始视频已显示，四格由显示层重排"),
                    )
                    EventLogger.logEvent(
                        category = Categories.SYSTEM,
                        eventName = "PRODUCT_MANUAL_PREVIEW_FIRST_FRAME",
                        payload = mapOf(
                            "cameraId" to current.cameraId.orEmpty(),
                            "producer" to "TextureView",
                            "forcedBuffer" to (current.forcedBufferSize?.let {
                                "${it.width}x${it.height}"
                            } ?: "none"),
                            "fallbackUsed" to current.fallbackUsed.toString(),
                        ),
                    )
                }
            }
        }
        if (view.isAvailable) {
            if (requested) openIfReady() else onRecorderPreviewSurfaceAvailable?.invoke()
        }
    }

    fun startPreview(source: SessionSourceSnapshot) {
        if (released) return
        if (requested && requestedSource == source) return
        requestedSource = source
        requested = true
        _state.value = ManualPreviewState(
            sourceRole = source.sourceRole,
            layoutKind = source.layoutKind,
            message = Utils.t("Opening preview…", "正在安全打开预览…"),
        )
        openIfReady()
    }

    fun stopPreview() {
        requested = false
        val token = generation.incrementAndGet()
        val handler = cameraHandler
        if (handler == null) {
            _state.value = ManualPreviewState()
            return
        }
        handler.post {
            closeCameraOnWorker()
            if (generation.get() == token) {
                _state.value = ManualPreviewState()
            }
        }
    }

    suspend fun stopAndAwait(): Boolean {
        requested = false
        val token = generation.incrementAndGet()
        val handler = cameraHandler
        return suspendCancellableCoroutine { continuation ->
            if (handler != null && !handler.post { closeCameraOnWorker() }) {
                if (continuation.isActive) continuation.resume(false)
                return@suspendCancellableCoroutine
            }
            // Do not resume on close() return: the admission ledger requires onClosed and output release.
            CaptureCleanupRuntime.awaitIdle(8_000) { idle ->
                if (generation.get() == token) {
                    if (idle) _state.value = ManualPreviewState()
                    else fail(Utils.t("Camera release is unconfirmed. Recording is blocked; copy diagnostics.", "相机释放尚未确认，暂不能开始录像；请复制诊断"))
                }
                if (continuation.isActive) continuation.resume(idle)
            }
        }
    }

    /**
     * Creates a separate Surface wrapper for the recorder capture session while
     * retaining the same ordinary TextureView/SurfaceTexture input path.
     */
    fun acquireRecorderPreviewSurface(targetBufferSize: Size? = configuredTextureBufferSize): Surface? {
        if (recorderSurfaceHandedOff) return null
        val view = textureView ?: return null
        val texture = view.surfaceTexture?.takeIf { view.isAvailable } ?: return null
        val configuredSize = targetBufferSize ?: configuredTextureBufferSize
        if (configuredSize != null) {
            val configured = runCatching {
                if (targetBufferSize != null) {
                    texture.setDefaultBufferSize(targetBufferSize.width, targetBufferSize.height)
                } else {
                    texture.setDefaultBufferSize(configuredSize.width, configuredSize.height)
                }
            }.isSuccess
            if (!configured) return null
            configuredTextureBufferSize = configuredSize
        }
        val surface = runCatching { Surface(texture) }.getOrNull() ?: return null
        if (!surface.isValid) {
            surface.release()
            return null
        }
        recorderSurfaceHandedOff = true
        _state.value = ManualPreviewState(
            active = true,
            firstFrame = false,
            previewSize = configuredSize,
            forcedBufferSize = configuredSize?.takeIf {
                it.width == 1280 && it.height == 5140
            },
            sourceRole = requestedSource?.sourceRole,
            layoutKind = requestedSource?.layoutKind,
            message = Utils.t("Waiting for recording preview…", "等待录像预览画面…"),
        )
        EventLogger.logEvent(
            category = Categories.SYSTEM,
            eventName = "RECORDER_PREVIEW_SURFACE_HANDOFF",
            payload = mapOf(
                "bufferSize" to (configuredSize?.let { "${it.width}x${it.height}" } ?: "unchanged"),
                "viewSize" to "${view.width}x${view.height}",
                "surfaceValid" to surface.isValid.toString(),
            ),
        )
        return surface
    }

    fun clearRecorderPreviewHandoff() {
        recorderSurfaceHandedOff = false
        if (!requested) _state.value = ManualPreviewState()
    }

    fun release() {
        if (released) return
        released = true
        requested = false
        requestedSource = null
        recorderSurfaceHandedOff = false
        generation.incrementAndGet()
        textureView = null
        cameraHandler?.post {
            closeCameraOnWorker()
            CaptureCleanupRuntime.awaitIdle { idle -> if (idle) cameraThread?.quitSafely() }
        }
        _state.value = ManualPreviewState()
    }

    private fun openIfReady() {
        val view = textureView ?: return
        if (!view.isAvailable || !requested || released) return
        if (appContext.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            _state.value = ManualPreviewState(error = Utils.t("Camera permission has not been granted", "尚未授予摄像头权限"), message = Utils.t("Preview has not started", "预览未启动"))
            requested = false
            return
        }
        val handler = ensureCameraHandler()
        val token = generation.incrementAndGet()
        handler.post { openCameraOnWorker(token, attemptHighResolution = true) }
    }

    private fun ensureCameraHandler(): Handler {
        cameraHandler?.let { return it }
        val thread = HandlerThread("product-manual-preview").also { it.start() }
        cameraThread = thread
        return Handler(thread.looper).also { cameraHandler = it }
    }

    private fun openCameraOnWorker(token: Long, attemptHighResolution: Boolean) {
        if (!requested || released || token != generation.get() || opening || cameraDevice != null) return
        val view = textureView ?: return
        if (!view.isAvailable) return
        val texture = view.surfaceTexture ?: return
        val source = requestedSource ?: run {
            fail(Utils.t("The recording source is not ready", "录像源尚未解析"))
            requested = false
            return
        }
        val manager = appContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        if (manager == null) {
            fail(Utils.t("The camera service is unavailable", "系统没有 CameraManager"))
            return
        }
        try {
            val cameraId = source.cameraId
            if (cameraId !in manager.cameraIdList) {
                fail(Utils.t("The selected camera {0} is unavailable", "映射的摄像头 {0} 不可用", cameraId))
                requested = false
                return
            }
            val declaredSizes = declaredSurfaceTextureSizes(manager, cameraId)
            val stableSize = chooseSafePreviewSize(declaredSizes)
            if (stableSize == null) {
                fail(Utils.t("The camera did not report a supported preview size", "摄像头没有报告可用的 SurfaceTexture 预览尺寸"))
                requested = false
                return
            }
            val declaredHighResolution = if (
                attemptHighResolution && source.layoutKind == RecordingLayoutKind.FOUR_LANE_V1
            ) {
                chooseDeclaredHighResolution(declaredSizes)
            } else {
                null
            }
            val forcedSize = declaredHighResolution?.let { candidate ->
                try {
                    texture.setDefaultBufferSize(candidate.width, candidate.height)
                    configuredTextureBufferSize = candidate
                    EventLogger.logEvent(
                        category = Categories.SYSTEM,
                        eventName = "PRODUCT_MANUAL_PREVIEW_HIGH_RES_ATTEMPT",
                        payload = mapOf(
                            "cameraId" to cameraId,
                            "requestedBuffer" to "${candidate.width}x${candidate.height}",
                            "viewSize" to "${view.width}x${view.height}",
                            "surfaceTextureSizes" to declaredSizes.joinToString(",") {
                                "${it.width}x${it.height}"
                            },
                        ),
                    )
                    candidate
                } catch (t: Throwable) {
                    restoreTextureViewBuffer(texture)
                    EventLogger.logEvent(
                        category = Categories.SYSTEM,
                        eventName = "PRODUCT_MANUAL_PREVIEW_HIGH_RES_SKIPPED",
                        payload = mapOf(
                            "cameraId" to cameraId,
                            "reason" to "setDefaultBufferSize failed: ${t.message ?: t.javaClass.simpleName}",
                        ),
                    )
                    null
                }
            }
            if (
                attemptHighResolution &&
                source.layoutKind == RecordingLayoutKind.FOUR_LANE_V1 &&
                declaredHighResolution == null
            ) {
                EventLogger.logEvent(
                    category = Categories.SYSTEM,
                    eventName = "PRODUCT_MANUAL_PREVIEW_HIGH_RES_SKIPPED",
                    payload = mapOf(
                        "cameraId" to cameraId,
                        "reason" to "1280x5140 not declared for SurfaceTexture",
                        "surfaceTextureSizes" to declaredSizes.joinToString(",") {
                            "${it.width}x${it.height}"
                        },
                    ),
                )
            }
            val previewSize = forcedSize ?: stableSize
            val streamKind = previewStreamKind(stableSize, forcedSize)
            opening = true
            _state.value = ManualPreviewState(
                cameraId = cameraId,
                previewSize = previewSize,
                forcedBufferSize = forcedSize,
                fallbackUsed = !attemptHighResolution,
                sourceRole = source.sourceRole,
                layoutKind = source.layoutKind,
                message = Utils.t("Opening camera {0}: {1}…", "正在打开摄像头 {0} 的{1}…", cameraId, streamKind),
            )
            val handler = cameraHandler ?: return
            val watchdog = Runnable {
                if (opening && requested && token == generation.get()) {
                    fail(Utils.t("Camera opening timed out after 8 seconds and was cancelled", "摄像头 8 秒内没有打开，已安全取消"))
                    closeCameraOnWorker()
                    requested = false
                }
            }
            openWatchdog = watchdog
            handler.postDelayed(watchdog, 8_000L)
            // Permission can be revoked after the UI button check and before
            // this worker reaches CameraManager.
            if (appContext.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                cancelWatchdog()
                opening = false
                requested = false
                fail(Utils.t("Camera permission was revoked", "摄像头权限已被撤销"))
                return
            }
            val callback = object : CameraDevice.StateCallback() {
                    override fun onClosed(camera: CameraDevice) {
                        CaptureCleanupRuntime.deviceClosed(camera)
                        EventLogger.logEvent(Categories.SYSTEM, "PRODUCT_MANUAL_PREVIEW_DEVICE_CLOSED",
                            payload = mapOf("cameraId" to cameraId, "generation" to token.toString()))
                    }

                    override fun onOpened(camera: CameraDevice) {
                        if (!requested || released || token != generation.get()) {
                            camera.close()
                            return
                        }
                        cancelWatchdog()
                        opening = false
                        cameraDevice = camera
                        createSession(
                            camera = camera,
                            texture = texture,
                            cameraId = cameraId,
                            stableSize = stableSize,
                            forcedSize = forcedSize,
                            token = token,
                            highResolutionAttempt = forcedSize != null,
                        )
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        if (token == generation.get()) {
                            recoverOrFail(
                                reason = Utils.t("Camera disconnected", "摄像头已断开"),
                                cameraId = cameraId,
                                texture = texture,
                                stableSize = stableSize,
                                token = token,
                                highResolutionAttempt = forcedSize != null,
                            )
                        }
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        if (token == generation.get()) {
                            recoverOrFail(
                                reason = Utils.t("Camera opening failed: {0}", "摄像头打开失败：{0}", error),
                                cameraId = cameraId,
                                texture = texture,
                                stableSize = stableSize,
                                token = token,
                                highResolutionAttempt = forcedSize != null,
                            )
                        }
                    }
                }
            CanaryCameraOpenAdapter.open(manager, cameraId, callback, handler)
        } catch (e: CameraAccessException) {
            opening = false
            cancelWatchdog()
            fail(cameraError(e))
        } catch (t: Throwable) {
            opening = false
            cancelWatchdog()
            fail(t.message ?: t.javaClass.simpleName)
        }
    }

    private fun createSession(
        camera: CameraDevice,
        texture: SurfaceTexture,
        cameraId: String,
        stableSize: Size,
        forcedSize: Size?,
        token: Long,
        highResolutionAttempt: Boolean,
    ) {
        try {
            val surface = Surface(texture)
            previewSurface = surface
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
            }.build()
            scheduleSessionWatchdog(
                token = token,
                cameraId = cameraId,
                texture = texture,
                stableSize = stableSize,
                highResolutionAttempt = highResolutionAttempt,
            )
            camera.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (!requested || released || token != generation.get() || cameraDevice !== camera) {
                            session.close()
                            return
                        }
                        captureSession = session
                        try {
                            session.setRepeatingRequest(request, null, cameraHandler)
                            _state.value = ManualPreviewState(
                                active = true,
                                cameraId = cameraId,
                                previewSize = forcedSize ?: stableSize,
                                forcedBufferSize = forcedSize,
                                fallbackUsed = !highResolutionAttempt,
                                sourceRole = requestedSource?.sourceRole,
                                layoutKind = requestedSource?.layoutKind,
                                message = Utils.t("{0} started; waiting for the first frame…", "{0}已启动，等待首帧…", previewStreamKind(stableSize, forcedSize)),
                            )
                            EventLogger.logEvent(
                                category = Categories.SYSTEM,
                                eventName = "PRODUCT_MANUAL_PREVIEW_STARTED",
                                payload = mapOf(
                                    "cameraId" to cameraId,
                                    "declaredHint" to "${stableSize.width}x${stableSize.height}",
                                    "requestedBuffer" to (forcedSize?.let {
                                        "${it.width}x${it.height}"
                                    } ?: "none"),
                                    "bufferSizeForced" to (forcedSize != null).toString(),
                                    "fallbackUsed" to (!highResolutionAttempt).toString(),
                                ),
                            )
                        } catch (t: Throwable) {
                            recoverOrFail(
                                reason = Utils.t("Preview request failed: {0}", "预览请求失败：{0}", t.message ?: t.javaClass.simpleName),
                                cameraId = cameraId,
                                texture = texture,
                                stableSize = stableSize,
                                token = token,
                                highResolutionAttempt = highResolutionAttempt,
                            )
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        session.close()
                        recoverOrFail(
                            reason = Utils.t("Preview configuration failed", "预览会话配置失败"),
                            cameraId = cameraId,
                            texture = texture,
                            stableSize = stableSize,
                            token = token,
                            highResolutionAttempt = highResolutionAttempt,
                        )
                    }
                },
                cameraHandler,
            )
        } catch (t: Throwable) {
            recoverOrFail(
                reason = Utils.t("Could not create preview: {0}", "创建预览会话失败：{0}", t.message ?: t.javaClass.simpleName),
                cameraId = cameraId,
                texture = texture,
                stableSize = stableSize,
                token = token,
                highResolutionAttempt = highResolutionAttempt,
            )
        }
    }

    private fun declaredSurfaceTextureSizes(manager: CameraManager, cameraId: String): List<Size> {
        val map = manager.getCameraCharacteristics(cameraId)
            .get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return emptyList()
        return map.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
    }

    private fun chooseSafePreviewSize(sizes: List<Size>): Size? {
        val selected = CompositePreviewSizePolicy.choose(
            sizes.map { ProfileSize(it.width, it.height) },
        ) ?: return null
        return sizes.firstOrNull { it.width == selected.width && it.height == selected.height }
    }

    private fun chooseDeclaredHighResolution(sizes: List<Size>): Size? {
        val selected = CompositePreviewSizePolicy.chooseDeclaredHighResolution(
            sizes.map { ProfileSize(it.width, it.height) },
        ) ?: return null
        return sizes.firstOrNull { it.width == selected.width && it.height == selected.height }
    }

    private fun previewStreamKind(stableSize: Size, forcedSize: Size?): String =
        if (forcedSize != null) {
            Utils.t("High-resolution preview ({0}×{1})", "高清四路预览（{0}×{1}）", forcedSize.width, forcedSize.height)
        } else {
            Utils.t("Compatible preview ({0}×{1})", "兼容预览（Surface 提示 {0}×{1}）", stableSize.width, stableSize.height)
        }

    private fun scheduleSessionWatchdog(
        token: Long,
        cameraId: String,
        texture: SurfaceTexture,
        stableSize: Size,
        highResolutionAttempt: Boolean,
    ) {
        cancelSessionWatchdog()
        val watchdog = Runnable {
            val current = _state.value
            if (
                requested &&
                !released &&
                token == generation.get() &&
                !current.firstFrame
            ) {
                recoverOrFail(
                    reason = Utils.t("Preview did not receive a frame within 8 seconds", "预览 8 秒内没有显示首帧"),
                    cameraId = cameraId,
                    texture = texture,
                    stableSize = stableSize,
                    token = token,
                    highResolutionAttempt = highResolutionAttempt,
                )
            }
        }
        sessionWatchdog = watchdog
        cameraHandler?.postDelayed(watchdog, 8_000L)
    }

    private fun recoverOrFail(
        reason: String,
        cameraId: String,
        texture: SurfaceTexture,
        stableSize: Size,
        token: Long,
        highResolutionAttempt: Boolean,
    ) {
        if (!requested || released || token != generation.get()) return
        if (!highResolutionAttempt) {
            fail(reason)
            closeCameraOnWorker()
            requested = false
            return
        }

        EventLogger.logEvent(
            category = Categories.SYSTEM,
            eventName = "PRODUCT_MANUAL_PREVIEW_HIGH_RES_FALLBACK",
            payload = mapOf(
                "cameraId" to cameraId,
                "reason" to reason,
                "stableHint" to "${stableSize.width}x${stableSize.height}",
            ),
        )
        closeCameraOnWorker()
        restoreTextureViewBuffer(texture)
        val fallbackToken = generation.incrementAndGet()
        _state.value = ManualPreviewState(
            cameraId = cameraId,
            previewSize = stableSize,
            fallbackUsed = true,
            sourceRole = requestedSource?.sourceRole,
            layoutKind = requestedSource?.layoutKind,
            message = Utils.t("High-resolution preview is unavailable. Restoring compatible preview…", "高清预览不可用，正在恢复兼容模式…"),
        )
        CaptureCleanupRuntime.awaitIdle(8_000) { idle -> cameraHandler?.post {
            if (idle) openCameraOnWorker(fallbackToken, attemptHighResolution = false)
            else { requested = false; fail(Utils.t("Previous preview release is unconfirmed. Retries have stopped.", "上一次预览释放未确认，已停止重试")) }
        } }

    }

    private fun restoreTextureViewBuffer(texture: SurfaceTexture) {
        val view = textureView ?: return
        if (view.surfaceTexture !== texture || view.width <= 0 || view.height <= 0) return
        runCatching { texture.setDefaultBufferSize(view.width, view.height) }
            .onSuccess { configuredTextureBufferSize = Size(view.width, view.height) }
    }

    private fun closeCameraOnWorker() {
        cancelWatchdog()
        cancelSessionWatchdog()
        if (closeTransaction != null) return
        val camera = cameraDevice
        val session = captureSession
        val surface = previewSurface
        opening = false
        if (camera == null && session == null && surface == null) return
        val hold = Any()
        val traceId = "preview-" + System.identityHashCode(this) + "-" + generation.get()
        CaptureCleanupRuntime.initialize(appContext)
        val tx = CaptureCloseTransaction(
            control = CaptureCleanupRuntime.control,
            native = HandlerCloseDispatcher(requireNotNull(cameraHandler)),
            output = HandlerCloseDispatcher(requireNotNull(cameraHandler)),
            resources = object : CaptureCloseResources {
                override fun stopRepeating() { session?.stopRepeating() }
                override fun abortCaptures() { session?.abortCaptures() }
                override fun closeSession() { session?.close() }
                override fun closeDevice() { camera?.close() }
                override fun stopRecorder() = Unit
                override fun resetRecorder() = Unit
                override fun releaseRecorder() = Unit
                override fun closeOutput(lost: Boolean) { surface?.release() }
            },
            hasSession = session != null, hasDevice = camera != null,
            wasRecording = false, sequences = emptySet(), terminal = true, lost = false,
            trace = { step, detail -> CaptureCleanupRuntime.trace(traceId, step, detail) },
            unconfirmed = { requested = false; fail(Utils.t("Camera release is unconfirmed. Retries have stopped; copy diagnostics.", "相机释放未确认，已停止重试；请复制诊断")) },
            completed = { result -> cameraHandler?.post {
                if (result.safeToContinue) {
                    if (cameraDevice === camera) cameraDevice = null
                    if (captureSession === session) captureSession = null
                    if (previewSurface === surface) previewSurface = null
                    closeTransaction = null
                    CaptureCleanupRuntime.settled(hold, true)
                    if (released) cameraThread?.quitSafely()
                }
            } },
            preferDeviceClose = true,
        )
        closeTransaction = tx
        CaptureCleanupRuntime.retain(hold, camera?.id ?: _state.value.cameraId ?: "default", camera, tx)
        tx.begin()
    }

    private fun cancelWatchdog() {
        openWatchdog?.let { cameraHandler?.removeCallbacks(it) }
        openWatchdog = null
    }

    private fun cancelSessionWatchdog() {
        sessionWatchdog?.let { cameraHandler?.removeCallbacks(it) }
        sessionWatchdog = null
    }

    private fun fail(message: String) {
        _state.value = _state.value.copy(active = false, error = message, message = Utils.t("Preview has not started", "预览未启动"))
        EventLogger.logEvent(
            category = Categories.SYSTEM,
            eventName = "PRODUCT_MANUAL_PREVIEW_FAILED",
            payload = mapOf("message" to message),
        )
    }

    private fun cameraError(error: CameraAccessException): String = when (error.reason) {
        CameraAccessException.CAMERA_IN_USE -> Utils.t("The camera is in use by the vehicle’s 360° or reversing system", "摄像头正被原厂 360/倒车占用")
        CameraAccessException.MAX_CAMERAS_IN_USE -> Utils.t("The camera usage limit has been reached", "已达到摄像头使用上限")
        CameraAccessException.CAMERA_DISABLED -> Utils.t("The system disabled the camera", "系统已禁用摄像头")
        CameraAccessException.CAMERA_DISCONNECTED -> Utils.t("Camera disconnected", "摄像头已断开")
        else -> Utils.t("Camera2 error {0}", "Camera2 错误 {0}", error.reason)
    }
}
