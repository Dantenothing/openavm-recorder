package com.dante.zeekrcapabilitylab.service.recorder

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock

/**
 * Non-reference-counted PARTIAL_WAKE_LOCK held only while segments are actively
 * recording. Each acquire carries the platform timeout and is safely renewed
 * before expiry on every state change; release is idempotent and exception-safe.
 */
class RecorderWakeLockHolder(
    private val context: Context,
    private val onEvent: (eventName: String, message: String) -> Unit,
) {
    private var wakeLock: PowerManager.WakeLock? = null
    private var heldSinceElapsedMs: Long? = null

    @Volatile
    var isHeld: Boolean = false
        private set

    /** Called on every state change; no-op when the decision is NONE. */
    fun sync(status: String) {
        when (RecorderWakeLockPolicy.decide(isHeld, status)) {
            WakeLockAction.ACQUIRE -> acquire()
            WakeLockAction.RELEASE -> release("STATE_$status")
            WakeLockAction.NONE -> renewIfDue()
        }
    }

    private fun acquire() {
        if (isHeld) return
        try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val lock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "zeekr:segment-recorder",
            ).apply {
                setReferenceCounted(false)
                acquire(RecorderWakeLockPolicy.TIMEOUT_MS)
            }
            wakeLock = lock
            isHeld = true
            heldSinceElapsedMs = SystemClock.elapsedRealtime()
            onEvent(
                "RECORDER_WAKE_LOCK_ACQUIRED",
                "timeoutMs=${RecorderWakeLockPolicy.TIMEOUT_MS} held=true",
            )
        } catch (t: Throwable) {
            isHeld = false
            wakeLock = null
            heldSinceElapsedMs = null
            onEvent(
                "RECORDER_WAKE_LOCK_ACQUIRE_FAILED",
                t.message ?: "acquire failed",
            )
        }
    }

    private fun release(reason: String) {
        if (!isHeld) return
        isHeld = false
        heldSinceElapsedMs = null
        try {
            wakeLock?.release()
        } catch (t: Throwable) {
            // Ignore: the lock is already cleared; failure must not leak into state.
        }
        wakeLock = null
        onEvent("RECORDER_WAKE_LOCK_RELEASED", "reason=$reason held=false")
    }

    /** Re-acquire before the platform timeout can silently drop the lock. */
    private fun renewIfDue() {
        if (!isHeld) return
        val since = heldSinceElapsedMs ?: return
        val heldFor = SystemClock.elapsedRealtime() - since
        if (RecorderWakeLockPolicy.shouldRenew(heldFor)) {
            release("RENEW")
            acquire()
        }
    }

    /** Idempotent teardown for STOPPED / camera-loss / error / storage-block / onDestroy. */
    fun releaseAll() {
        release("SHUTDOWN")
    }
}
