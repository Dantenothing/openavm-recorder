package com.dante.zeekrcapabilitylab.sentry.runtime

import kotlinx.serialization.Serializable

/** First observed failure survives later close callbacks and secondary release errors. */
@Serializable
data class GuardFailureEvidence(
    val atEpochMs: Long, val elapsedMs: Long, val reason: String,
    val phase: String, val mode: String?, val appForeground: Boolean,
    val power: String, val cameraId: String?, val captureStage: String?,
    val captureFrames: Long?, val normalStatus: String?,
    val failureDetail: String? = null,
    val normal: GuardNormalEvidence? = null,
)

internal object GuardFailurePolicy {
    fun record(state: GuardState, reason: String, epochMs: Long, elapsedMs: Long, foreground: Boolean): GuardState {
        val first = state.firstFailure ?: GuardFailureEvidence(
            epochMs, elapsedMs, reason, state.phase, state.mode, foreground, state.power,
            state.runSource?.cameraId, state.capture?.startup?.stage ?: state.preparationStage?.takeUnless { it == "READY" },
            state.capture?.outputFrames, state.normalStatus,
            state.capture?.failureDetail ?: state.preparationFailure, state.normalEvidence)
        return state.copy(firstFailure = first, error = first.reason, message = "运行已停止：" + first.reason)
    }

    fun preserve(state: GuardState): GuardState =
        state.firstFailure?.let { state.copy(error = it.reason) } ?: state
}
