package com.dante.zeekrbridge.service

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import com.dante.zeekrbridge.core.BridgeLockPolicy
import com.dante.zeekrbridge.core.BridgeLockRules
import com.dante.zeekrbridge.core.ServerLog

/**
 * Exception-safe, non-reference-counted PARTIAL_WAKE_LOCK + Wi-Fi lock held for
 * the lifetime of BridgeService. Each lock is acquired independently and
 * idempotently, so a service started before Wi-Fi connects can still pick up
 * the Wi-Fi lock later; release best-effort clears every real held lock.
 */
object BridgePowerLocks {
    private const val WAKE_TAG = "zeekr:bridge"
    private const val WIFI_TAG = "zeekr:bridge-wifi"

    private val policy = BridgeLockPolicy()

    @Volatile
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var wifiLock: WifiManager.WifiLock? = null

    fun isHeld(): Boolean = policy.isHeld()

    fun isWakeHeld(): Boolean = policy.wakeHeld

    fun isWifiHeld(): Boolean = policy.wifiHeld

    @SuppressLint("WakelockTimeout")
    fun acquire(context: Context): Boolean {
        if (policy.acquireWake()) {
            try {
                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                if (pm != null) {
                    val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG)
                    lock.setReferenceCounted(false)
                    lock.acquire()
                    wakeLock = lock
                } else {
                    policy.releaseWake()
                }
            } catch (t: Throwable) {
                wakeLock = null
                policy.releaseWake()
            }
        }
        val wifiAvailable = try {
            context.getSystemService(Context.WIFI_SERVICE) != null
        } catch (t: Throwable) {
            false
        }
        if (BridgeLockRules.holdWifiLock(wifiAvailable)) {
            if (policy.acquireWifi()) {
                try {
                    val wm = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    val lock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WIFI_TAG)
                    lock.setReferenceCounted(false)
                    lock.acquire()
                    wifiLock = lock
                } catch (t: Throwable) {
                    wifiLock = null
                    policy.releaseWifi()
                }
            }
        }
        ServerLog.log("BRIDGE_LOCKS_ACQUIRED wake=${isWakeHeld()} wifi=${isWifiHeld()}")
        return isWakeHeld() || isWifiHeld()
    }

    fun release() {
        policy.releaseAll()
        val wake = wakeLock
        if (wake != null) {
            try {
                if (wake.isHeld) wake.release()
            } catch (t: Throwable) {
                // Already released.
            }
        }
        wakeLock = null
        val wifi = wifiLock
        if (wifi != null) {
            try {
                if (wifi.isHeld) wifi.release()
            } catch (t: Throwable) {
                // Already released.
            }
        }
        wifiLock = null
        ServerLog.log("BRIDGE_LOCKS_RELEASED")
    }
}
