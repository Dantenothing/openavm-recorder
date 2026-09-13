package com.dante.zeekrcapabilitylab.sentry

/**
 * Process-wide admission for ordinary preview/recording and the integrated guard.
 * Tracks real normal open requests through their CameraDevice.onClosed acknowledgements.
 * An unknown/timed-out owner is retained, never treated as permission to steal its camera.
 */
object CanaryCameraInterlock {
    private val normalOpens = mutableMapOf<Any, String>()
    private val cleanup = mutableMapOf<Any, String>()
    private var canaryToken: Any? = null
    @Synchronized fun beginNormalOpen(token: Any, cameraId: String = "default"): Boolean {
        if (canaryToken != null || normalOpens.size >= 16 || cameraId in normalOpens.values || cameraId in cleanup.values) return false
        normalOpens[token] = cameraId
        return true
    }
    @Synchronized fun normalClosed(token: Any) { normalOpens.remove(token) }
    @Synchronized fun beginCleanup(token: Any, cameraId: String) { cleanup[token] = cameraId }
    @Synchronized fun cleanupComplete(token: Any) { cleanup.remove(token) }
    @Synchronized fun normalIdle(): Boolean = normalOpens.isEmpty() && cleanup.isEmpty()
    @Synchronized fun cameraIdle(cameraId: String): Boolean = cameraId !in normalOpens.values && cameraId !in cleanup.values
    @Synchronized fun reserveCanary(token: Any): Boolean {
        if (canaryToken != null || !normalIdle()) return false
        canaryToken = token
        return true
    }
    @Synchronized fun canaryClosed(token: Any): Boolean {
        if (canaryToken !== token) return false
        canaryToken = null
        return true
    }
}
