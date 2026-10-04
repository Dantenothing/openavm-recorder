package com.dante.zeekrcapabilitylab.product

import com.dante.zeekrcapabilitylab.service.recorder.RecordingSourceRole

/** Eligibility for one explicit retry. This policy never schedules a camera request. */
internal object HomePreviewRetryPolicy {
    data class State(
        val source: RecordingSourceRole,
        val floatingActive: Boolean,
        val floatingFailed: Boolean,
        val floatingBusy: Boolean,
        val foreground: Boolean,
        val permission: Boolean,
        val recording: Boolean,
        val recorderRunning: Boolean,
        val previewRunning: Boolean,
        val otherOwnerActive: Boolean,
        val otherOwnerFailed: Boolean,
        val transitionPending: Boolean,
        val cleanupPending: Boolean,
        val cleanupUnconfirmed: Boolean,
        val cleanupOwners: Int,
        val nativeIdle: Boolean,
        val glIdle: Boolean,
    )

    fun visible(state: State): Boolean = state.floatingActive && state.floatingFailed &&
        state.source in setOf(RecordingSourceRole.SURROUND, RecordingSourceRole.CABIN)

    fun allowed(state: State): Boolean = visible(state) && !state.floatingBusy && state.foreground && state.permission &&
        !state.recording && !state.recorderRunning && !state.previewRunning && !state.otherOwnerActive &&
        !state.otherOwnerFailed && !state.transitionPending && !state.cleanupPending && !state.cleanupUnconfirmed &&
        state.cleanupOwners == 0 && state.nativeIdle && state.glIdle
}
