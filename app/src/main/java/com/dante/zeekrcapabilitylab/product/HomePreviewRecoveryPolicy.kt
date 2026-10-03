package com.dante.zeekrcapabilitylab.product

import com.dante.zeekrcapabilitylab.service.recorder.RecorderCloseTiming

/** Home may restore preview only while the same visible-page intent still owns admission. */
internal object HomePreviewRecoveryPolicy {
    const val RELEASE_WAIT_MS = RecorderCloseTiming.CONTINUOUS_RELEASE_CEILING_MS

    data class State(
        val foreground: Boolean,
        val permission: Boolean,
        val sourceReady: Boolean,
        val recorderActive: Boolean = false,
        val recorderServiceRunning: Boolean = false,
        val otherOwnerActive: Boolean = false,
        val handoffPending: Boolean = false,
        val cleanupPending: Boolean = false,
        val cleanupUnconfirmed: Boolean = false,
        val cleanupOwners: Int = 0,
        val nativeIdle: Boolean = true,
        val ownPreviewRequested: Boolean = false,
    )

    enum class Decision { STOP_OWN_PREVIEW, KEEP_OWN_PREVIEW, WAIT_RELEASE, RESTORE, TIMED_OUT }

    fun decide(state: State, waitedMs: Long = 0): Decision = when {
        !state.foreground || !state.permission || !state.sourceReady || state.recorderActive ||
            state.recorderServiceRunning || state.otherOwnerActive || state.handoffPending -> Decision.STOP_OWN_PREVIEW
        // The 1->0 cleanup notification can arrive after the previous waiter
        // already restored this source. It must not close that new producer.
        state.ownPreviewRequested -> Decision.KEEP_OWN_PREVIEW
        !state.cleanupPending && !state.cleanupUnconfirmed && state.cleanupOwners == 0 && state.nativeIdle -> Decision.RESTORE
        waitedMs >= RELEASE_WAIT_MS -> Decision.TIMED_OUT
        else -> Decision.WAIT_RELEASE
    }
}
