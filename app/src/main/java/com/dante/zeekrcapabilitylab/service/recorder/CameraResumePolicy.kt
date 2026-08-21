package com.dante.zeekrcapabilitylab.service.recorder

/**
 * Pure rules for resuming a session after the camera is lost to another
 * client (issue #9: the OEM rear-seat camera can win vendor pipeline
 * arbitration mid-recording).
 *
 * The session does not poll: it waits in CAMERA_UNAVAILABLE and reacts to
 * CameraManager availability callbacks. These rules bound that waiting so the
 * recorder never fights another app or waits forever:
 *  - only availability of the session's own camera id can trigger an attempt;
 *  - attempts happen only inside [RESUME_WINDOW_MS] after the first loss;
 *  - at most [MAX_ATTEMPTS] attempts, spaced by [ATTEMPT_MIN_INTERVALS_MS]
 *    (immediate first, then escalating — an id can report available while the
 *    shared ISP is still contended, so rapid flapping must not thrash);
 *  - each attempt is debounced by [RESUME_DEBOUNCE_MS] because the other
 *    client may reacquire immediately after releasing.
 */
object CameraResumePolicy {

    /** How long after the first loss the session keeps waiting for the camera. */
    const val RESUME_WINDOW_MS = 30L * 60L * 1000L

    /** Delay between the availability signal and the reopen attempt. */
    const val RESUME_DEBOUNCE_MS = 1_500L

    /** Hard cap on automatic resume attempts per waiting period. */
    const val MAX_ATTEMPTS = 10

    /** Minimum spacing before attempt N+1, indexed by attempts already made (clamped). */
    val ATTEMPT_MIN_INTERVALS_MS = longArrayOf(0L, 10_000L, 30_000L, 60_000L)

    fun windowExpired(
        armedAtMs: Long?,
        nowMs: Long,
        windowMs: Long = RESUME_WINDOW_MS,
    ): Boolean = armedAtMs != null && nowMs - armedAtMs > windowMs

    fun minIntervalBeforeAttempt(attemptsMade: Int): Long =
        ATTEMPT_MIN_INTERVALS_MS[attemptsMade.coerceIn(0, ATTEMPT_MIN_INTERVALS_MS.size - 1)]

    fun shouldAttempt(
        availableCameraId: String,
        targetCameraId: String,
        armedAtMs: Long?,
        lastAttemptAtMs: Long?,
        attemptsMade: Int,
        nowMs: Long,
        windowMs: Long = RESUME_WINDOW_MS,
        maxAttempts: Int = MAX_ATTEMPTS,
    ): Boolean {
        if (availableCameraId != targetCameraId) return false
        if (armedAtMs == null) return false
        if (nowMs - armedAtMs > windowMs) return false
        if (attemptsMade >= maxAttempts) return false
        val minInterval = minIntervalBeforeAttempt(attemptsMade)
        if (lastAttemptAtMs != null && nowMs - lastAttemptAtMs < minInterval) return false
        return true
    }
}
