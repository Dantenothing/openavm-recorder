package com.dante.zeekrcapabilitylab.sentry.canary

import com.dante.zeekrcapabilitylab.sentry.RingSnapshot

data class CanaryRamReadiness(val clipReady: Boolean, val targetReady: Boolean, val reason: String)
enum class CanaryHistoryDeadline { TARGET_REACHED, CONTINUE_DIAGNOSTIC, STOP_UNUSABLE }
data class CanaryClipCoverage(val partial: Boolean, val reason: String)

/** Diagnostic availability does not lower the full RAM / 10 s post-roll acceptance gate. */
object CanaryRamPolicy {
    const val TARGET_SECONDS = 180
    const val TARGET_US = TARGET_SECONDS * 1_000_000L
    const val STARTUP_DEADLINE_SECONDS = TARGET_SECONDS + 60
    const val STARTUP_DEADLINE_MS = STARTUP_DEADLINE_SECONDS * 1000L
    const val DIAGNOSTIC_MIN_US = 5_000_000L
    const val POST_ROLL_US = 10_000_000L

    fun assess(ring: RingSnapshot, active: Boolean, clockHealthy: Boolean): CanaryRamReadiness {
        val reason = when {
            !active -> "INACTIVE"
            ring.droppedGops != 0L -> "GOP_GAP_RESTART_REQUIRED"
            !clockHealthy -> "CLOCK_NOT_READY"
            ring.completedGops < 2 || ring.completedHistoryUs < DIAGNOSTIC_MIN_US -> "WAITING_FOR_COMPLETED_HISTORY"
            ring.historyUs < TARGET_US -> "SHORT_CLIP_READY"
            else -> "TARGET_READY"
        }
        return CanaryRamReadiness(reason == "SHORT_CLIP_READY" || reason == "TARGET_READY", reason == "TARGET_READY", reason)
    }

    fun deadline(readiness: CanaryRamReadiness) = when {
        readiness.targetReady -> CanaryHistoryDeadline.TARGET_REACHED
        readiness.clipReady -> CanaryHistoryDeadline.CONTINUE_DIAGNOSTIC
        else -> CanaryHistoryDeadline.STOP_UNUSABLE
    }

    fun coverage(preRollUs: Long, postRollUs: Long, endReason: String?): CanaryClipCoverage {
        val reason = endReason ?: when {
            preRollUs < TARGET_US && postRollUs < POST_ROLL_US -> "PRE_AND_POST_ROLL_SHORT"
            preRollUs < TARGET_US -> "PRE_ROLL_SHORT"
            postRollUs < POST_ROLL_US -> "POST_ROLL_SHORT"
            else -> "VERIFIED_PLAYBACK_ACCEPTANCE_PENDING"
        }
        return CanaryClipCoverage(endReason != null || preRollUs < TARGET_US || postRollUs < POST_ROLL_US, reason)
    }
}
