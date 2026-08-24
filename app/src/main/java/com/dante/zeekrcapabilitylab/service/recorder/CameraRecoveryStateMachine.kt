package com.dante.zeekrcapabilitylab.service.recorder

/** Tunable values for one bounded Camera-interruption episode. */
data class CameraRecoveryPolicy(
    val availabilityDebounceMs: Long = 1_500L,
    val retryDelaysMs: LongArray = longArrayOf(2_000L, 5_000L, 10_000L, 20_000L),
    val maxAttempts: Int = 5,
    val recoveryWindowMs: Long = 120_000L,
    val stabilityGraceMs: Long = 30_000L,
) {
    init {
        require(availabilityDebounceMs >= 0L)
        require(retryDelaysMs.isNotEmpty() && retryDelaysMs.all { it >= 0L })
        require(maxAttempts > 0)
        require(recoveryWindowMs > 0L)
        require(stabilityGraceMs >= 0L)
    }

    fun retryDelayAfter(attemptsMade: Int): Long =
        retryDelaysMs[(attemptsMade - 1).coerceIn(0, retryDelaysMs.lastIndex)]
}

enum class CameraRecoveryPhase {
    HEALTHY,
    FINALIZING,
    WAITING_CAMERA,
    RESUMING,
    PROBATION,
    TERMINAL,
}

enum class CameraAvailabilityState { UNKNOWN, AVAILABLE, UNAVAILABLE }

enum class CameraRecoveryScheduleKind { DEBOUNCE, BACKOFF }

data class CameraRecoverySnapshot(
    val generation: Long = 0L,
    val targetCameraId: String? = null,
    val phase: CameraRecoveryPhase = CameraRecoveryPhase.TERMINAL,
    val hasRecordedSuccessfully: Boolean = false,
    val availability: CameraAvailabilityState = CameraAvailabilityState.UNKNOWN,
    val episodeStartedAtMs: Long? = null,
    val deadlineAtMs: Long? = null,
    val attemptsMade: Int = 0,
    val nextAttemptAtMs: Long? = null,
    val nextAttemptKind: CameraRecoveryScheduleKind? = null,
    val probationUntilMs: Long? = null,
    val lastReason: String? = null,
)

sealed interface CameraRecoveryAction {
    data object None : CameraRecoveryAction
    data class Attempt(val generation: Long, val attemptNumber: Int) : CameraRecoveryAction
    data class Abandon(val reason: String) : CameraRecoveryAction
}

/**
 * Pure single-Session Camera recovery state machine.
 *
 * It owns no Android resources. RecorderSession serializes events onto its Camera handler,
 * applies returned actions, and schedules only [nextWakeAtMs]. This keeps callback/timer/Stop
 * ordering testable without a Camera HAL.
 */
class CameraRecoveryStateMachine(
    private val policy: CameraRecoveryPolicy = CameraRecoveryPolicy(),
) {
    enum class RecordingStartOutcome { NORMAL, RESUMED, STALE }

    var snapshot: CameraRecoverySnapshot = CameraRecoverySnapshot()
        private set

    val maxAttempts: Int get() = policy.maxAttempts
    val recoveryWindowMs: Long get() = policy.recoveryWindowMs

    fun beginManualSession(generation: Long, targetCameraId: String) {
        snapshot = CameraRecoverySnapshot(
            generation = generation,
            targetCameraId = targetCameraId,
            phase = CameraRecoveryPhase.HEALTHY,
        )
    }

    fun cancelManualSession(generation: Long) {
        if (!isCurrent(generation)) return
        snapshot = snapshot.copy(
            phase = CameraRecoveryPhase.TERMINAL,
            hasRecordedSuccessfully = false,
            nextAttemptAtMs = null,
            nextAttemptKind = null,
            probationUntilMs = null,
            lastReason = "SESSION_CANCELLED",
        )
    }

    fun terminateManualSession(generation: Long, reason: String) {
        if (!isCurrent(generation)) return
        snapshot = snapshot.copy(
            phase = CameraRecoveryPhase.TERMINAL,
            nextAttemptAtMs = null,
            nextAttemptKind = null,
            probationUntilMs = null,
            lastReason = reason,
        )
    }

    fun markRecordingStarted(generation: Long, nowMs: Long): RecordingStartOutcome {
        if (!isCurrent(generation) || snapshot.phase == CameraRecoveryPhase.TERMINAL) {
            return RecordingStartOutcome.STALE
        }
        return if (snapshot.phase == CameraRecoveryPhase.RESUMING) {
            snapshot = snapshot.copy(
                phase = CameraRecoveryPhase.PROBATION,
                hasRecordedSuccessfully = true,
                nextAttemptAtMs = null,
                nextAttemptKind = null,
                probationUntilMs = nowMs + policy.stabilityGraceMs,
                lastReason = null,
            )
            RecordingStartOutcome.RESUMED
        } else if (snapshot.phase == CameraRecoveryPhase.PROBATION) {
            snapshot = snapshot.copy(hasRecordedSuccessfully = true)
            RecordingStartOutcome.NORMAL
        } else {
            snapshot = snapshot.copy(
                phase = CameraRecoveryPhase.HEALTHY,
                hasRecordedSuccessfully = true,
                nextAttemptAtMs = null,
                nextAttemptKind = null,
                probationUntilMs = null,
            )
            RecordingStartOutcome.NORMAL
        }
    }

    fun beginRecoverableLoss(generation: Long, reason: String, nowMs: Long): Boolean {
        if (!isCurrent(generation) || !snapshot.hasRecordedSuccessfully) return false
        if (snapshot.phase !in setOf(CameraRecoveryPhase.HEALTHY, CameraRecoveryPhase.PROBATION)) {
            return false
        }
        val continuingEpisode = snapshot.phase == CameraRecoveryPhase.PROBATION &&
            snapshot.deadlineAtMs?.let { nowMs < it } == true
        snapshot = snapshot.copy(
            phase = CameraRecoveryPhase.FINALIZING,
            episodeStartedAtMs = if (continuingEpisode) snapshot.episodeStartedAtMs else nowMs,
            deadlineAtMs = if (continuingEpisode) {
                snapshot.deadlineAtMs
            } else {
                nowMs + policy.recoveryWindowMs
            },
            attemptsMade = if (continuingEpisode) snapshot.attemptsMade else 0,
            nextAttemptAtMs = null,
            nextAttemptKind = null,
            probationUntilMs = null,
            lastReason = reason,
        )
        return true
    }

    fun finalizeCompleted(generation: Long, nowMs: Long): CameraRecoveryAction {
        if (!isCurrent(generation) || snapshot.phase != CameraRecoveryPhase.FINALIZING) {
            return CameraRecoveryAction.None
        }
        if (deadlineReached(nowMs)) return abandon("RECOVERY_WINDOW_EXPIRED")
        val nextAttemptAt = if (snapshot.availability == CameraAvailabilityState.AVAILABLE) {
            nowMs + policy.availabilityDebounceMs
        } else {
            null
        }
        snapshot = snapshot.copy(
            phase = CameraRecoveryPhase.WAITING_CAMERA,
            nextAttemptAtMs = nextAttemptAt,
            nextAttemptKind = nextAttemptAt?.let { CameraRecoveryScheduleKind.DEBOUNCE },
        )
        return CameraRecoveryAction.None
    }

    fun onAvailability(
        generation: Long,
        cameraId: String,
        available: Boolean,
        nowMs: Long,
    ): CameraRecoveryAction {
        if (!isCurrent(generation) || cameraId != snapshot.targetCameraId ||
            snapshot.phase == CameraRecoveryPhase.TERMINAL
        ) {
            return CameraRecoveryAction.None
        }
        snapshot = snapshot.copy(
            availability = if (available) {
                CameraAvailabilityState.AVAILABLE
            } else {
                CameraAvailabilityState.UNAVAILABLE
            },
        )
        if (snapshot.phase != CameraRecoveryPhase.WAITING_CAMERA) return CameraRecoveryAction.None
        if (deadlineReached(nowMs)) return abandon("RECOVERY_WINDOW_EXPIRED")
        if (available) {
            val candidate = nowMs + policy.availabilityDebounceMs
            val current = snapshot.nextAttemptAtMs
            if (current == null || candidate < current) {
                snapshot = snapshot.copy(
                    nextAttemptAtMs = candidate,
                    nextAttemptKind = CameraRecoveryScheduleKind.DEBOUNCE,
                )
            }
        } else if (snapshot.nextAttemptKind == CameraRecoveryScheduleKind.DEBOUNCE) {
            // A pre-attempt availability edge was withdrawn. Backoff after a real failed
            // attempt is deliberately not cancelled, so recovery does not require another edge.
            snapshot = snapshot.copy(nextAttemptAtMs = null, nextAttemptKind = null)
        }
        return CameraRecoveryAction.None
    }

    fun onTimer(generation: Long, nowMs: Long): CameraRecoveryAction {
        if (!isCurrent(generation) || snapshot.phase == CameraRecoveryPhase.TERMINAL) {
            return CameraRecoveryAction.None
        }
        if (snapshot.phase == CameraRecoveryPhase.PROBATION) {
            val probationUntil = snapshot.probationUntilMs
            if (probationUntil != null && nowMs >= probationUntil) {
                snapshot = snapshot.copy(
                    phase = CameraRecoveryPhase.HEALTHY,
                    episodeStartedAtMs = null,
                    deadlineAtMs = null,
                    attemptsMade = 0,
                    probationUntilMs = null,
                    lastReason = null,
                )
            }
            return CameraRecoveryAction.None
        }
        if (snapshot.phase != CameraRecoveryPhase.WAITING_CAMERA) {
            return CameraRecoveryAction.None
        }
        if (deadlineReached(nowMs)) return abandon("RECOVERY_WINDOW_EXPIRED")
        val attemptAt = snapshot.nextAttemptAtMs ?: return CameraRecoveryAction.None
        if (nowMs < attemptAt) return CameraRecoveryAction.None
        if (snapshot.attemptsMade >= policy.maxAttempts) return abandon("RECOVERY_ATTEMPT_LIMIT")
        val attemptNumber = snapshot.attemptsMade + 1
        snapshot = snapshot.copy(
            phase = CameraRecoveryPhase.RESUMING,
            attemptsMade = attemptNumber,
            nextAttemptAtMs = null,
            nextAttemptKind = null,
        )
        return CameraRecoveryAction.Attempt(generation, attemptNumber)
    }

    fun attemptFailed(
        generation: Long,
        recoverable: Boolean,
        reason: String,
        nowMs: Long,
    ): CameraRecoveryAction {
        if (!isCurrent(generation) || snapshot.phase != CameraRecoveryPhase.RESUMING) {
            return CameraRecoveryAction.None
        }
        if (!recoverable) return abandon(reason)
        if (snapshot.attemptsMade >= policy.maxAttempts) return abandon("RECOVERY_ATTEMPT_LIMIT")
        if (deadlineReached(nowMs)) return abandon("RECOVERY_WINDOW_EXPIRED")
        snapshot = snapshot.copy(
            phase = CameraRecoveryPhase.WAITING_CAMERA,
            nextAttemptAtMs = nowMs + policy.retryDelayAfter(snapshot.attemptsMade),
            nextAttemptKind = CameraRecoveryScheduleKind.BACKOFF,
            lastReason = reason,
        )
        return CameraRecoveryAction.None
    }

    fun nextWakeAtMs(): Long? = when (snapshot.phase) {
        CameraRecoveryPhase.WAITING_CAMERA -> listOfNotNull(
            snapshot.deadlineAtMs,
            snapshot.nextAttemptAtMs,
        ).minOrNull()
        CameraRecoveryPhase.PROBATION -> snapshot.probationUntilMs
        else -> null
    }

    private fun deadlineReached(nowMs: Long): Boolean =
        snapshot.deadlineAtMs?.let { nowMs >= it } == true

    private fun isCurrent(generation: Long): Boolean = generation == snapshot.generation

    private fun abandon(reason: String): CameraRecoveryAction.Abandon {
        snapshot = snapshot.copy(
            phase = CameraRecoveryPhase.TERMINAL,
            nextAttemptAtMs = null,
            nextAttemptKind = null,
            probationUntilMs = null,
            lastReason = reason,
        )
        return CameraRecoveryAction.Abandon(reason)
    }
}
