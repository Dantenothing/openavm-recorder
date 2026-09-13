package com.dante.zeekrcapabilitylab.product

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Size
import android.view.Surface
import com.dante.zeekrcapabilitylab.BuildConfig
import com.dante.zeekrcapabilitylab.sentry.CanaryCameraOpenAdapter
import com.dante.zeekrcapabilitylab.data.Categories
import com.dante.zeekrcapabilitylab.event.EventLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Camera2 preview-only controller for the product 录像 page.
 *
 * Threading contract (the ANR fix):
 *  - The UI thread never touches Camera2. Every potentially blocking HAL call
 *    (cameraIdList, getCameraCharacteristics, getOutputSizes, openCamera,
 *    createCaptureSession, setRepeatingRequest) runs on [cameraExecutor], a
 *    dedicated single-thread daemon executor.
 *  - All decisions run on [supervisorThread]: the pure [PreviewStateMachine],
 *    debounce timers, the 7s open watchdog and resource teardown. The watchdog
 *    lives on a different thread than the HAL ops, so a wedged HAL can never
 *    prevent the timeout from firing and closing the camera.
 *  - Camera callbacks arrive on the supervisor thread, check the generation
 *    token and post further HAL work back to the executor. Late callbacks only
 *    close their own camera/session and never mutate newer state.
 *
 * The preview is a separate, lightweight session that only runs while the
 * segment recorder service is NOT holding the camera. If the camera is taken by
 * the OEM 360 / reverse view or by the recorder service, the preview simply
 * reports an explicit status and never touches the recording pipeline.
 */
class LivePreviewController(private val context: Context) {

    private val _status = MutableStateFlow(STATUS_IDLE)
    val status: StateFlow<String> = _status.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private val supervisorThread = HandlerThread("product-preview-supervisor").apply { start() }
    private val supervisorHandler = Handler(supervisorThread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "product-preview-camera").apply { isDaemon = true }
    }

    @Volatile
    private var surfaceTexture: SurfaceTexture? = null
    @Volatile
    private var surface: Surface? = null
    private var cameraId: String? = null
    private var previewSize: Size = Size(1280, 720)
    @Volatile
    private var cameraDevice: CameraDevice? = null
    @Volatile
    private var captureSession: CameraCaptureSession? = null
    private var debounceRunnable: Runnable? = null
    private var timeoutRunnable: Runnable? = null
    private var released = false

    private var permissionGranted =
        context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    @Volatile
    private var availabilityState = false

    var onReady: ((SurfaceTexture, Int, Int) -> Unit)? = null

    private val availabilityCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(cameraId: String) {
            availabilityState = true
            supervisorHandler.post { if (!released) machine.setCameraAvailable(true) }
        }

        override fun onCameraUnavailable(cameraId: String) {
            availabilityState = false
            supervisorHandler.post { if (!released) machine.setCameraAvailable(false) }
        }
    }

    private lateinit var machine: PreviewStateMachine

    init {
        machine = PreviewStateMachine(object : PreviewStateMachine.Listener {
            override fun onStateChanged(state: PreviewUiState) {
                val mapped = mapPhase(state)
                _status.value = mapped.first
                _error.value = mapped.second
                if (state.phase == PreviewPhase.DEBOUNCING) {
                    armDebounce()
                } else {
                    cancelDebounce()
                }
            }

            override fun onEnumerateRequested() {
                val gen = machine.currentGeneration
                submitCamera {
                    val result = runCatching { enumerateAndChoose() }
                    val value = result.getOrNull()
                    supervisorHandler.post {
                        if (released) return@post
                        if (value != null) {
                            cameraId = value.cameraId
                            previewSize = value.size
                            machine.onEnumerationComplete(gen)
                        } else {
                            val failure = result.exceptionOrNull()
                            machine.onEnumerationFailed(
                                gen,
                                failure?.let { failureMessage(it) } ?: "ENUMERATION_FAILED",
                            )
                        }
                    }
                }
            }

            override fun onOpenRequested() {
                val gen = machine.currentGeneration
                val size = previewSize
                val texture = surfaceTexture
                if (texture == null) {
                    machine.onStartFailed(gen, "SURFACE_UNAVAILABLE")
                    return
                }
                val id = cameraId
                if (id == null) {
                    machine.onStartFailed(gen, "CAMERA_ID_UNAVAILABLE")
                    return
                }
                armOpenTimeout()
                submitCamera {
                    var openedSurface: Surface? = null
                    try {
                        // Re-check on the worker because permission may be
                        // revoked after the earlier state-machine transition.
                        if (context.checkSelfPermission(Manifest.permission.CAMERA) !=
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            supervisorHandler.post {
                                if (!released) {
                                    machine.onStartFailed(gen, PreviewStateMachine.REASON_PERMISSION)
                                }
                            }
                            return@submitCamera
                        }
                        texture.setDefaultBufferSize(size.width, size.height)
                        val s = Surface(texture)
                        openedSurface = s
                        supervisorHandler.post {
                            if (!released && machine.currentGeneration == gen) {
                                surface = s
                            } else {
                                runCatching { s.release() }
                            }
                        }
                        val callback = object : CameraDevice.StateCallback() {
                                override fun onOpened(camera: CameraDevice) {
                                    supervisorHandler.post {
                                        if (released) {
                                            closeQuietly(camera)
                                            return@post
                                        }
                                        if (machine.onOpened(gen)) {
                                            cameraDevice = camera
                                            createSession(camera, s, gen)
                                        } else {
                                            closeQuietly(camera)
                                        }
                                    }
                                }

                                override fun onDisconnected(camera: CameraDevice) {
                                    supervisorHandler.post {
                                        if (!released && machine.onCameraDisconnected(gen)) {
                                            if (cameraDevice === camera) cameraDevice = null
                                        }
                                        closeQuietly(camera)
                                    }
                                }

                                override fun onError(camera: CameraDevice, error: Int) {
                                    supervisorHandler.post {
                                        if (!released && machine.onCameraError(gen, error)) {
                                            if (cameraDevice === camera) cameraDevice = null
                                        }
                                        closeQuietly(camera)
                                    }
                                }
                            }
                        if (BuildConfig.SENTRY_CANARY_ENABLED) {
                            CanaryCameraOpenAdapter.open(manager, id, callback, supervisorHandler)
                        } else {
                            manager.openCamera(id, callback, supervisorHandler)
                        }
                    } catch (t: Throwable) {
                        openedSurface?.let { runCatching { it.release() } }
                        if (surface === openedSurface) surface = null
                        supervisorHandler.post {
                            if (!released) machine.onStartFailed(gen, failureMessage(t))
                        }
                    }
                }
            }

            override fun onCloseRequested() {
                cancelTimeout()
                cancelDebounce()
                closeResources()
                machine.onCloseComplete()
            }

            override fun onLateCameraClosed() {
                // The stale camera/session closes itself in its own callback path.
            }
        })
        try {
            manager.registerAvailabilityCallback(availabilityCallback, supervisorHandler)
        } catch (t: Throwable) {
            EventLogger.markError(
                Categories.SYSTEM,
                "PREVIEW_AVAILABILITY_REGISTER_FAILED",
                t.message ?: "register failed",
                t,
            )
        }
        supervisorHandler.post {
            machine.setPermissionGranted(permissionGranted)
            machine.setCameraAvailable(availabilityState)
        }
    }

    /** Page visible with a producer texture. Does not itself start Camera2. */
    fun start(surfaceTexture: SurfaceTexture) {
        supervisorHandler.post {
            if (released) return@post
            this.surfaceTexture = surfaceTexture
            machine.setPageVisible(true)
            machine.setPermissionGranted(permissionGranted)
            machine.setCameraAvailable(availabilityState)
        }
    }

    /** Page hidden (tab switch / disposal): cancel and release asynchronously. */
    fun stop() {
        supervisorHandler.post {
            machine.setPageVisible(false)
            surfaceTexture = null
        }
    }

    fun setLifecycleResumed(value: Boolean) {
        supervisorHandler.post {
            if (!released) machine.setLifecycleResumed(value)
        }
    }

    fun setRecordingActive(value: Boolean) {
        supervisorHandler.post {
            if (!released) machine.setRecordingActive(value)
        }
    }

    fun setPermissionGranted(value: Boolean) {
        permissionGranted = value
        supervisorHandler.post {
            if (!released) machine.setPermissionGranted(value)
        }
    }

    fun retry() {
        supervisorHandler.post {
            if (!released) machine.retry()
        }
    }

    /** Final teardown; must be called from onDispose on the UI thread. */
    fun release() {
        supervisorHandler.post {
            if (released) return@post
            released = true
            machine.setPageVisible(false)
            machine.setLifecycleResumed(false)
            cancelTimeout()
            cancelDebounce()
            closeResources()
            runCatching { manager.unregisterAvailabilityCallback(availabilityCallback) }
            supervisorThread.quitSafely()
            cameraExecutor.shutdown()
        }
    }

    private fun createSession(camera: CameraDevice, target: Surface, gen: Long) {
        submitCamera {
            try {
                val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                    .apply { addTarget(target) }
                    .build()
                camera.createCaptureSession(
                    listOf(target),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            supervisorHandler.post {
                                if (released) {
                                    closeQuietlySession(session)
                                    return@post
                                }
                                if (machine.onSessionConfigured(gen)) {
                                    captureSession = session
                                    cancelTimeout()
                                    val size = previewSize
                                    submitCamera {
                                        try {
                                            session.setRepeatingRequest(request, null, supervisorHandler)
                                            val texture = surfaceTexture
                                            if (texture != null) {
                                                mainHandler.post {
                                                    if (!released) {
                                                        onReady?.invoke(texture, size.width, size.height)
                                                    }
                                                }
                                            }
                                        } catch (t: Throwable) {
                                            supervisorHandler.post {
                                                if (!released) {
                                                    machine.onSessionConfigureFailed(gen, failureMessage(t))
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    closeQuietlySession(session)
                                }
                            }
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            supervisorHandler.post {
                                if (!released) machine.onSessionConfigureFailed(gen, "CONFIGURE_FAILED")
                            }
                        }
                    },
                    supervisorHandler,
                )
            } catch (t: Throwable) {
                supervisorHandler.post {
                    if (!released) machine.onSessionConfigureFailed(gen, failureMessage(t))
                }
            }
        }
    }

    private fun enumerateAndChoose(): EnumerateResult {
        val cameraIds = CameraRuntime.cameraIds(context)
        val id = cameraIds.firstOrNull { it == "2" }
            ?: cameraIds.firstOrNull()
            ?: throw IllegalStateException("NO_CAMERA")
        val size = choosePreviewSize(id) ?: Size(1280, 720)
        return EnumerateResult(id, size)
    }

    private fun choosePreviewSize(cameraId: String): Size? {
        return try {
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: return null
            val sizes = map.getOutputSizes(SurfaceTexture::class.java)
                ?.toList()
                ?: return null
            val composite = sizes.firstOrNull { it.width == 5120 && it.height == 1280 }
            if (composite != null) return composite
            val wide = sizes.filter { it.width.toDouble() / it.height >= 3.2 }
                .maxByOrNull { it.width * it.height }
            wide ?: sizes.maxByOrNull { it.width * it.height }
        } catch (t: Throwable) {
            null
        }
    }

    private fun armDebounce() {
        cancelDebounce()
        val runnable = Runnable { machine.onDebounceElapsed() }
        debounceRunnable = runnable
        supervisorHandler.postDelayed(runnable, PreviewTimingPolicy.DEBOUNCE_MS)
    }

    private fun cancelDebounce() {
        debounceRunnable?.let { supervisorHandler.removeCallbacks(it) }
        debounceRunnable = null
    }

    private fun armOpenTimeout() {
        cancelTimeout()
        val runnable = Runnable { machine.onOpenTimeout() }
        timeoutRunnable = runnable
        supervisorHandler.postDelayed(runnable, PreviewTimingPolicy.OPEN_TIMEOUT_MS)
    }

    private fun cancelTimeout() {
        timeoutRunnable?.let { supervisorHandler.removeCallbacks(it) }
        timeoutRunnable = null
    }

    /** Runs on the supervisor thread; CameraDevice/Session close is thread-safe. */
    private fun closeResources() {
        runCatching { captureSession?.close() }
        captureSession = null
        runCatching { cameraDevice?.close() }
        cameraDevice = null
        runCatching { surface?.release() }
        surface = null
    }

    private fun submitCamera(task: () -> Unit) {
        if (cameraExecutor.isShutdown) return
        try {
            cameraExecutor.execute(task)
        } catch (t: RejectedExecutionException) {
            // Releasing; nothing else to do.
        }
    }

    private fun closeQuietly(camera: CameraDevice) {
        runCatching { camera.close() }
    }

    private fun closeQuietlySession(session: CameraCaptureSession) {
        runCatching { session.close() }
    }

    private fun failureMessage(t: Throwable): String = when (t) {
        is SecurityException -> PreviewStateMachine.REASON_PERMISSION
        is CameraAccessException -> cameraAccessMessage(t)
        else -> t.message ?: t.javaClass.simpleName
    }

    private fun cameraAccessMessage(e: CameraAccessException): String = when (e.reason) {
        CameraAccessException.CAMERA_IN_USE -> "CAMERA_IN_USE"
        CameraAccessException.MAX_CAMERAS_IN_USE -> "MAX_CAMERAS_IN_USE"
        CameraAccessException.CAMERA_DISABLED -> "CAMERA_DISABLED"
        CameraAccessException.CAMERA_DISCONNECTED -> "CAMERA_DISCONNECTED"
        CameraAccessException.CAMERA_ERROR -> "CAMERA_ERROR"
        else -> "CAMERA_ACCESS_ERROR code=${e.reason}"
    }

    private fun mapPhase(state: PreviewUiState): Pair<String, String?> = when (state.phase) {
        PreviewPhase.IDLE -> STATUS_IDLE to null
        PreviewPhase.WAITING_FOR_LIFECYCLE -> STATUS_PREPARING to null
        PreviewPhase.WAITING_FOR_AVAILABILITY ->
            if (state.reason == PreviewStateMachine.REASON_RECORDING) {
                STATUS_RECORDING to null
            } else {
                STATUS_WAITING_AVAILABILITY to null
            }
        PreviewPhase.DEBOUNCING -> STATUS_PREPARING to null
        PreviewPhase.ENUMERATING -> STATUS_STARTING to null
        PreviewPhase.OPENING -> STATUS_OPENING to null
        PreviewPhase.PREVIEWING -> "预览中 ${previewSize.width}x${previewSize.height}" to null
        PreviewPhase.CLOSING -> STATUS_CLOSING to null
        PreviewPhase.UNAVAILABLE -> STATUS_UNAVAILABLE to (state.reason ?: "CAMERA_UNAVAILABLE")
        PreviewPhase.TIMED_OUT -> STATUS_TIMED_OUT to (state.reason ?: PreviewStateMachine.REASON_TIMEOUT)
    }

    private data class EnumerateResult(
        val cameraId: String,
        val size: Size,
    )

    companion object {
        const val STATUS_IDLE = "预览未启动"
        const val STATUS_PREPARING = "正在准备摄像头…"
        const val STATUS_WAITING_AVAILABILITY = "摄像头暂不可用，等待原厂系统释放"
        const val STATUS_RECORDING = "录像中，不显示实时预览"
        const val STATUS_STARTING = "正在启动预览…"
        const val STATUS_OPENING = "正在打开预览…"
        const val STATUS_CLOSING = "正在关闭预览…"
        const val STATUS_UNAVAILABLE = "预览不可用"
        const val STATUS_TIMED_OUT = "预览启动超时"
    }
}
