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
    private var normalGroup: Set<Any>? = null
    @Synchronized fun beginNormalOpen(token: Any, cameraId: String = "default"): Boolean {
        if (normalGroup != null || canaryToken != null || normalOpens.size >= 16 || cameraId in normalOpens.values || cameraId in cleanup.values) return false
        normalOpens[token] = cameraId
        return true
    }
    /** All-or-nothing group reservation. No partially reserved camera set is observable. */
    @Synchronized fun beginNormalGroup(tokens: Map<Any, String>): Boolean {
        if (tokens.size !in 2..3 || tokens.values.any(String::isBlank) || tokens.values.distinct().size != tokens.size ||
            canaryToken != null || normalGroup != null || !normalIdle()) return false
        normalGroup = tokens.keys.toSet(); normalOpens.putAll(tokens); return true
    }
    @Synchronized fun ownsNormalReservation(token: Any, id: String) = normalOpens[token] == id
    @Synchronized fun normalClosed(token: Any) {
        normalOpens.remove(token)
        if (normalGroup?.none { it in normalOpens } == true) normalGroup = null
    }
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
