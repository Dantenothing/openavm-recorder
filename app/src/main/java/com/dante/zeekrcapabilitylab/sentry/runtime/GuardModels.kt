package com.dante.zeekrcapabilitylab.sentry.runtime

import com.dante.zeekrcapabilitylab.sentry.canary.CanarySnapshot
import com.dante.zeekrcapabilitylab.sentry.ai.TrackRiskEvidence
import com.dante.zeekrcapabilitylab.sentry.ParkingBehavior
import com.dante.zeekrcapabilitylab.service.recorder.SessionSourceSnapshot
import kotlinx.serialization.Serializable

@Serializable
data class GuardAiState(
    val status: String = "OFF", val message: String = "AI 试用已关闭",
    val model: String = "nanodet-2022nov/4b82da9944b8", val modelAccepted: Boolean = false,
    val vehicleCalibrated: Boolean = false, val clockCalibrated: Boolean = false,
    val frames: Long = 0, val dropped: Long = 0, val inferenceMs: Long = 0,
    val analysisWidth: Int = 0, val analysisHeight: Int = 0,
    val tracks: List<TrackRiskEvidence> = emptyList(), val triggers: Long = 0,
    val maxConfidence: Double = 0.0,
    val ruleVersion: String? = null, val profileVersion: String? = null,
    val nearBoundary: Double? = null, val criticalBoundary: Double? = null,
    val clock: GuardClockEvidence? = null,
)

@Serializable
data class GuardState(
    val schemaVersion: Int = 1, val build: String = "", val runId: String = "",
    val running: Boolean = false, val phase: String = "STOPPED", val mode: String? = null,
    val parkingBehavior: ParkingBehavior = ParkingBehavior.SENTRY,
    val runDuration: GuardRunDuration = GuardRunDuration.HOURS_12,
    val runSource: SessionSourceSnapshot? = null,
    val runCamera: GuardCameraCapabilities? = null,
    val preparationStage: String? = null,
    val preparationFailure: String? = null,
    val firstFailure: GuardFailureEvidence? = null,
    val runtime: GuardRuntimeEvidence = GuardRuntimeEvidence(),
    val presence: String = "UNKNOWN", val power: String = "", val manualMode: String? = null,
    val message: String = "请先开始本次运行", val error: String? = null,
    val ai: GuardAiState = GuardAiState(), val capture: CanarySnapshot? = null,
    val normalStatus: String? = null, val eventId: String? = null,
    val normalEvidence: GuardNormalEvidence? = null,
    val normalTransitions: List<GuardNormalEvidence> = emptyList(),
    val eventDeadlineUs: Long? = null, val eventsRevision: Long = 0,
    val startedAtEpochMs: Long = 0, val updatedAtEpochMs: Long = 0,
    val pssKiB: Int = 0, val thermalStatus: Int? = null,
    val journal: List<String> = emptyList(),
)

/** Resource flags describe live owners, not the last retained camera diagnostic snapshot. */
@Serializable
data class GuardRuntimeEvidence(
    val processId: String = "", val pid: Int = 0,
    val serviceForeground: Boolean = false, val wakeLockHeld: Boolean = false,
    val wakeAcquiredAtEpochMs: Long = 0, val wakeReleasedAtEpochMs: Long = 0,
    val runExpiresAtEpochMs: Long = 0, val stopReason: String? = null,
    val heartbeat: Long = 0, val lastElapsedMs: Long = 0, val lastUptimeMs: Long = 0,
    val maxHeartbeatGapMs: Long = 0, val maxSuspendGapMs: Long = 0,
    val deviceIdleMode: Boolean = false, val batteryOptimizationExempt: Boolean = false,
    val appForeground: Boolean = false, val interactive: Boolean = false, val mainDisplay: String = "",
    val cameraLease: String? = null, val normalSessionActive: Boolean = false,
    val sentryPipelineActive: Boolean = false, val resourcesReleased: Boolean = false,
    val idleEntries: Int = 0, val idleEnteredAtEpochMs: Long = 0,
    val returnConfirmedAtEpochMs: Long = 0, val normalRequestedAtEpochMs: Long = 0,
    val normalStartedAtEpochMs: Long = 0, val normalStartedInBackground: Boolean = false,
    val appLastForegroundAtEpochMs: Long = 0,
)

@Serializable
data class GuardTrigger(
    val type: String, val ptsUs: Long, val epochMs: Long,
    val lanes: Set<Int> = emptySet(), val evidence: List<TrackRiskEvidence> = emptyList(),
    val detectorVersion: String? = null, val ruleConfigVersion: String? = null,
    val observedAtElapsedUs: Long? = null, val clockGeneration: Long? = null,
)

@Serializable
data class GuardAsset(
    val name: String, val number: Int, val firstPtsUs: Long, val lastPtsUs: Long,
    val samples: Int, val bytes: Long = 0, val sha256: String? = null,
    val actualDurationMs: Long? = null,
)

@Serializable
data class GuardEvent(
    val schemaVersion: Int = 1, val id: String, val runId: String,
    val source: SessionSourceSnapshot, val state: String = "WRITING",
    val createdAtEpochMs: Long, val triggerPtsUs: Long, val targetEndPtsUs: Long,
    val preRequestedUs: Long = 180_000_000, val postRequestedUs: Long = 60_000_000,
    val triggers: List<GuardTrigger>, val omittedTriggers: Long = 0,
    val ai: GuardAiState, val assets: List<GuardAsset> = emptyList(),
    val pending: GuardAsset? = null, val reason: String? = null,
    val preAchievedUs: Long = 0, val postAchievedUs: Long = 0,
)

/** One event has a bounded tail; subsequent triggers extend it without opening another writer. */
class GuardEventWindow(val firstTriggerUs: Long, val postUs: Long = 60_000_000, val capUs: Long = 120_000_000) {
    init { require(firstTriggerUs >= 0 && postUs > 0 && capUs >= postUs && firstTriggerUs <= Long.MAX_VALUE - capUs) }
    var endUs = firstTriggerUs + postUs
        private set
    private var lastTriggerUs = firstTriggerUs
    fun extend(ptsUs: Long): Boolean {
        if (ptsUs < lastTriggerUs || ptsUs >= firstTriggerUs + capUs) return false
        lastTriggerUs = ptsUs
        val cap = firstTriggerUs + capUs
        endUs = maxOf(endUs, if (postUs >= cap - ptsUs) cap else ptsUs + postUs)
        return true
    }
}
