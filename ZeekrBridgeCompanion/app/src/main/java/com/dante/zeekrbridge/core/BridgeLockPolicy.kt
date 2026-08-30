package com.dante.zeekrbridge.core

/**
 * Pure hold/release policy for the two independent bridge power locks. Each
 * lock is idempotent on its own: the service may start before Wi-Fi connects,
 * and the Wi-Fi lock must still be acquirable later (or on a repeated
 * onStartCommand) without an early return on the combined state.
 */
class BridgeLockPolicy {
    var wakeHeld: Boolean = false
        private set

    var wifiHeld: Boolean = false
        private set

    /** Returns true only when this call actually transitioned to held. */
    fun acquireWake(): Boolean {
        if (wakeHeld) return false
        wakeHeld = true
        return true
    }

    /** Independent of wake state: a later Wi-Fi connection can still acquire. */
    fun acquireWifi(): Boolean {
        if (wifiHeld) return false
        wifiHeld = true
        return true
    }

    fun releaseWake() {
        wakeHeld = false
    }

    fun releaseWifi() {
        wifiHeld = false
    }

    fun releaseAll() {
        wakeHeld = false
        wifiHeld = false
    }

    fun isHeld(): Boolean = wakeHeld || wifiHeld
}

object BridgeLockRules {
    /**
     * Hold the Wi-Fi lock whenever WifiManager exists; do not depend on the
     * initial connected snapshot (the service can start before a hotspot is up).
     */
    fun holdWifiLock(wifiManagerAvailable: Boolean): Boolean = wifiManagerAvailable
}
