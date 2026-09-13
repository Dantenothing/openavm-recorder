package com.dante.zeekrcapabilitylab.sentry

import kotlinx.serialization.Serializable

enum class GuardPolicy {
    OFF, AUTO;
    companion object {
        fun fromStoredValue(value: String?): GuardPolicy = entries.firstOrNull { it.name == value } ?: OFF
    }
}

object SentryFeatureGate {
    fun <T> createIfEnabled(available: Boolean, policy: GuardPolicy, create: () -> T): T? =
        if (available && policy == GuardPolicy.AUTO) create() else null
}

enum class RunPermitState { DISARMED, ARMED }
data class RunPermit(val state: RunPermitState = RunPermitState.DISARMED, val generation: Long = 0) {
    fun accepts(generation: Long) = state == RunPermitState.ARMED && this.generation == generation
}
data class ModeStamp(val run: Long, val transition: Long)
enum class CaptureMode { NORMAL, SENTRY }
@Serializable
enum class ParkingBehavior { SENTRY, AWAKE_IDLE }
enum class RecorderModePhase {
    STOPPED, STARTING_NORMAL, NORMAL_ACTIVE, TRANSITION_TO_SENTRY, SENTRY_ACTIVE,
    TRANSITION_TO_NORMAL, TRANSITION_TO_AWAKE_IDLE, AWAKE_IDLE, STOPPING, DEGRADED, FAULT,
}
@Serializable
enum class SentryStopReason {
    MANUAL_STOP, MASTER_OFF, FATAL_STOP, CAMERA_FAULT, RELEASE_TIMEOUT, MEMORY_LIMIT,
    CODEC_STALLED, INVALID_TIMELINE, STORAGE_FAILURE, SERVICE_DESTROYED, CANARY_LIMIT, RUN_LIMIT,
}
@Serializable
enum class SentryDegradeReason { HISTORY_SHORTENED, GOP_DROPPED, WRITER_BACKLOG, DETECTOR_THROTTLED }
@Serializable
enum class SentrySeverity { SOFT, HARD }
@Serializable
enum class SentryTriggerType { MANUAL, VISUAL_RISK, SUSPECTED_IMPACT, TRUSTED_PHYSICAL }
@Serializable
enum class SentryPersistenceState { COMPLETE, PARTIAL, FAILED_STORAGE, AWAITING_TRANSFER }
@Serializable
data class SentryEventAsset(
    val ordinal: Int,
    val assetToken: String,
    val firstMediaPtsUs: Long,
    val lastMediaPtsUs: Long,
    val byteCount: Long,
    val sha256: String?,
)
@Serializable
data class SentryEventManifest(
    val schemaVersion: Int = 1,
    val eventId: String,
    val sentrySessionId: String,
    val state: SentryPersistenceState,
    val severity: SentrySeverity,
    val triggerTypes: Set<SentryTriggerType>,
    val involvedLanes: Set<Int>,
    val armedAtEpochMs: Long,
    val eventStartedAtEpochMs: Long,
    val firstTriggerAtEpochMs: Long,
    val eventEndedAtEpochMs: Long,
    val preRollRequestedMs: Long,
    val preRollAchievedMs: Long,
    val postRollRequestedMs: Long,
    val postRollAchievedMs: Long,
    val coverageNotes: List<String>,
    val detectorVersion: String? = null,
    val ruleConfigVersion: String? = null,
    val endReason: String,
    val assets: List<SentryEventAsset>,
)

/** Lifecycle observations, not a claim that Android power state identifies an occupant. */
interface VehiclePresenceSignalSource {
    fun snapshot(nowMonotonicMs: Long): PresenceSignals
}
