package com.dante.zeekrcapabilitylab.sentry

import android.annotation.SuppressLint
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper

/** Callback ownership survives page/service destruction and native worker stalls. */
object CanaryCameraOpenAdapter {
    // At most 16 outstanding opens are admitted by the interlock. A dead preview looper
    // must not force a potentially blocking vendor close onto the UI thread.
    private val orphanCloser = Handler(HandlerThread("sentry-canary-orphan-close").apply { start() }.looper)
    private val callbacks = Handler(HandlerThread("camera-device-callbacks").apply { start() }.looper)
    @SuppressLint("MissingPermission")
    fun open(manager: CameraManager, id: String, callback: CameraDevice.StateCallback, destination: Handler?) {
        val token = Any()
        check(CanaryCameraInterlock.beginNormalOpen(token, id)) { "CAMERA_RELEASE_PENDING" }
        val target = destination ?: Handler(Looper.getMainLooper())
        val proxy = object : CameraDevice.StateCallback() {
            private fun dispatch(camera: CameraDevice, action: () -> Unit) {
                if (!target.post(action)) orphanCloser.post { runCatching { camera.close() } }
            }
            override fun onOpened(camera: CameraDevice) = dispatch(camera) { callback.onOpened(camera) }
            override fun onDisconnected(camera: CameraDevice) = dispatch(camera) { callback.onDisconnected(camera) }
            override fun onError(camera: CameraDevice, error: Int) = dispatch(camera) { callback.onError(camera, error) }
            override fun onClosed(camera: CameraDevice) {
                com.dante.zeekrcapabilitylab.service.recorder.CaptureCleanupRuntime.deviceClosed(camera)
                CanaryCameraInterlock.normalClosed(token)
                target.post { callback.onClosed(camera) }
            }
        }
        try { manager.openCamera(id, proxy, callbacks) }
        catch (error: Throwable) { CanaryCameraInterlock.normalClosed(token); throw error }
    }
}
