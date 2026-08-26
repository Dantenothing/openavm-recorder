package com.dante.zeekrcapabilitylab.ui.product

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
import com.dante.zeekrcapabilitylab.data.Categories
import com.dante.zeekrcapabilitylab.event.EventLogger
import com.dante.zeekrcapabilitylab.probe.camera.ProfileSize
import com.dante.zeekrcapabilitylab.product.CompositePreviewSizePolicy
import com.dante.zeekrcapabilitylab.product.CameraRuntime
import com.dante.zeekrcapabilitylab.product.EmulatorTestRecording
import com.dante.zeekrcapabilitylab.product.SettingsStore
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
    val message: String = "预览默认关闭",
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
    private var released = false
    @Volatile
    private var textureView: TextureView? = null
    @Volatile
    private var recorderSurfaceHandedOff = false
    @Volatile
    private var requiredPreviewSize: Size? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    // Accessed only on cameraHandler.
    private var opening = false
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var openWatchdog: Runnable? = null
    private var sessionWatchdog: Runnable? = null

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
            }

            override fun onSurfaceTextureSizeChanged(
                texture: SurfaceTexture,
                width: Int,
                height: Int,
            ) = Unit

            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                if (textureView === view) {
                    recorderSurfaceHandedOff = false
                    stopPreview()
                    textureView = null
                }
                return true
            }

            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
                val current = _state.value
                if ((requested || recorderSurfaceHandedOff) && current.active && !current.firstFrame) {
                    cancelSessionWatchdog()
                    _state.value = current.copy(
                        firstFrame = true,
                        message = "实时原始视频已显示，四格由显示层重排",
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
        if (requested && view.isAvailable) openIfReady()
    }

    fun startPreview(requiredSourceSize: ProfileSize? = null) {
        if (released || requested) return
        requiredPreviewSize = requiredSourceSize?.let { Size(it.width, it.height) }
        requested = true
        _state.value = ManualPreviewState(message = "正在安全打开预览…")
        openIfReady()
    }

    fun stopPreview() {
        requested = false
        requiredPreviewSize = null
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

    suspend fun stopAndAwait() {
        requested = false
        val token = generation.incrementAndGet()
        val handler = cameraHandler
        if (handler == null) {
            _state.value = ManualPreviewState()
            return
        }
        suspendCancellableCoroutine { continuation ->
            handler.post {
                closeCameraOnWorker()
                if (generation.get() == token) _state.value = ManualPreviewState()
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
    }

    /**
     * Creates a separate Surface wrapper for the recorder capture session while
     * retaining the same ordinary TextureView/SurfaceTexture input path.
     */
    fun acquireRecorderPreviewSurface(): Surface? {
        val view = textureView ?: return null
        val texture = view.surfaceTexture?.takeIf { view.isAvailable } ?: return null
        val surface = runCatching { Surface(texture) }.getOrNull() ?: return null
        if (!surface.isValid) {
            surface.release()
            return null
        }
        recorderSurfaceHandedOff = true
        _state.value = ManualPreviewState(
            active = true,
            firstFrame = false,
            message = "等待录像预览画面…",
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
        recorderSurfaceHandedOff = false
        generation.incrementAndGet()
        val handler = cameraHandler
        val thread = cameraThread
        textureView = null
        if (handler != null) {
            handler.post {
                closeCameraOnWorker()
                thread?.quitSafely()
            }
        } else {
            thread?.quitSafely()
        }
        cameraHandler = null
        cameraThread = null
        _state.value = ManualPreviewState()
    }

    private fun openIfReady() {
        val view = textureView ?: return
        if (!view.isAvailable || !requested || released) return
        if (appContext.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            _state.value = ManualPreviewState(error = "尚未授予摄像头权限", message = "预览未启动")
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
        val manager = appContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        if (manager == null) {
            fail("系统没有 CameraManager")
            return
        }
        try {
            val settings = SettingsStore.get(appContext)
            val cameraId = EmulatorTestRecording.source(appContext)?.cameraId
                ?: CameraRuntime.sourceCatalog(appContext).singleOrNull {
                    it.cameraId == settings.selectedCameraId && it.fingerprint == settings.sourceFingerprint
                }?.cameraId
            if (cameraId == null) {
                fail("请先在设置中确认摄像头来源")
                return
            }
            val declaredSizes = declaredSurfaceTextureSizes(manager, cameraId)
            val stableSize = chooseSafePreviewSize(declaredSizes)
            if (stableSize == null) {
                fail("摄像头没有报告可用的 SurfaceTexture 预览尺寸")
                requested = false
                return
            }
            val declaredHighResolution = if (attemptHighResolution) {
                val required = requiredPreviewSize
                if (required != null) {
                    declaredSizes.singleOrNull { it.width == required.width && it.height == required.height }
                } else {
                    chooseDeclaredHighResolution(declaredSizes)
                }
            } else {
                null
            }
            val forcedSize = declaredHighResolution?.let { candidate ->
                try {
                    texture.setDefaultBufferSize(candidate.width, candidate.height)
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
            if (attemptHighResolution && declaredHighResolution == null) {
                EventLogger.logEvent(
                    category = Categories.SYSTEM,
                    eventName = "PRODUCT_MANUAL_PREVIEW_HIGH_RES_SKIPPED",
                    payload = mapOf(
                        "cameraId" to cameraId,
                        "reason" to "required composite size not declared for SurfaceTexture",
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
                message = "正在打开摄像头 $cameraId 的${streamKind}…",
            )
            val handler = cameraHandler ?: return
            val watchdog = Runnable {
                if (opening && requested && token == generation.get()) {
                    fail("摄像头 8 秒内没有打开，已安全取消")
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
                fail("摄像头权限已被撤销")
                return
            }
            manager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
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
                                reason = "摄像头已断开",
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
                                reason = "摄像头打开失败：$error",
                                cameraId = cameraId,
                                texture = texture,
                                stableSize = stableSize,
                                token = token,
                                highResolutionAttempt = forcedSize != null,
                            )
                        }
                    }
                },
                handler,
            )
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
                                message = "${previewStreamKind(stableSize, forcedSize)}已启动，等待首帧…",
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
                                reason = "预览请求失败：${t.message ?: t.javaClass.simpleName}",
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
                            reason = "预览会话配置失败",
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
                reason = "创建预览会话失败：${t.message ?: t.javaClass.simpleName}",
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
            "高清四路预览（${forcedSize.width}×${forcedSize.height}）"
        } else {
            "兼容预览（Surface 提示 ${stableSize.width}×${stableSize.height}）"
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
                    reason = "预览 8 秒内没有显示首帧",
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
            message = "高清预览不可用，正在恢复兼容模式…",
        )
        cameraHandler?.postDelayed(
            {
                openCameraOnWorker(
                    token = fallbackToken,
                    attemptHighResolution = false,
                )
            },
            250L,
        )
    }

    private fun restoreTextureViewBuffer(texture: SurfaceTexture) {
        val view = textureView ?: return
        if (view.surfaceTexture !== texture || view.width <= 0 || view.height <= 0) return
        runCatching { texture.setDefaultBufferSize(view.width, view.height) }
    }

    private fun closeCameraOnWorker() {
        cancelWatchdog()
        cancelSessionWatchdog()
        opening = false
        runCatching { captureSession?.stopRepeating() }
        runCatching { captureSession?.close() }
        captureSession = null
        runCatching { cameraDevice?.close() }
        cameraDevice = null
        runCatching { previewSurface?.release() }
        previewSurface = null
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
        _state.value = _state.value.copy(active = false, error = message, message = "预览未启动")
        EventLogger.logEvent(
            category = Categories.SYSTEM,
            eventName = "PRODUCT_MANUAL_PREVIEW_FAILED",
            payload = mapOf("message" to message),
        )
    }

    private fun cameraError(error: CameraAccessException): String = when (error.reason) {
        CameraAccessException.CAMERA_IN_USE -> "摄像头正被原厂 360/倒车占用"
        CameraAccessException.MAX_CAMERAS_IN_USE -> "已达到摄像头使用上限"
        CameraAccessException.CAMERA_DISABLED -> "系统已禁用摄像头"
        CameraAccessException.CAMERA_DISCONNECTED -> "摄像头已断开"
        else -> "Camera2 错误 ${error.reason}"
    }
}
