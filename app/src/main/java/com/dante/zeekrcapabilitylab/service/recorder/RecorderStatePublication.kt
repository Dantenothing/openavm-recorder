package com.dante.zeekrcapabilitylab.service.recorder

/**
 * One serialized publication path for the camera worker and independent cleanup control.
 * A terminal latch revokes run authority, not the resource-cleanup callbacks themselves.
 * It never invents a device/FD close acknowledgement.
 */
internal class RecorderStatePublication(
    private val syncWakeLock: (String) -> Boolean,
    private val releaseWakeLock: () -> Unit,
    private val publish: (RecorderState) -> Unit,
    private val onTerminated: (RecorderState, String, String) -> Unit = { _, _, _ -> },
) {
    @Volatile var state = RecorderState()
        private set
    private var terminalReason: String? = null
    private var terminalStatus: String? = null
    private var terminalError: String? = null

    /** Called only after the manual-start admission checks, never by a callback. */
    @Synchronized fun begin(sessionId: String) {
        check(!state.cleanupPending && !state.cleanupUnconfirmed)
        terminalReason = null; terminalStatus = null; terminalError = null
        state = RecorderState(recordingSessionId = sessionId, libraryRevision = state.libraryRevision)
    }

    @Synchronized fun isCurrent(sessionId: String?): Boolean = sessionId == state.recordingSessionId
    @Synchronized fun allowsOpen(sessionId: String?): Boolean =
        sessionId != null && isCurrent(sessionId) && terminalReason == null

    @Synchronized fun update(value: RecorderState, expectedSessionId: String? = value.recordingSessionId,
                             source: String = "STATE_UPDATE") {
        if (!isCurrent(expectedSessionId)) return
        var newTerminal = false
        if (value.status in END_STATUSES) {
            newTerminal = latch(value.lastError ?: value.status, value.status, value.lastError)
            if (!state.cleanupUnconfirmed || value.status != RecorderStatus.STOPPED) terminalStatus = value.status
        }
        val next = if (terminalReason == null) value else value.copy(
            status = terminalStatus ?: RecorderStatus.FINALIZING,
            lastError = terminalError ?: value.lastError,
            cleanupPending = value.cleanupPending || state.cleanupUnconfirmed,
            cleanupUnconfirmed = value.cleanupUnconfirmed || state.cleanupUnconfirmed,
            recovery = value.recovery.copy(phase = CameraRecoveryPhase.TERMINAL, resumeAllowed = false,
                nextAttemptAtMs = null, nextAttemptKind = null, waitingForAvailability = false),
        )
        state = next.copy(wakeLockHeld = syncWakeLock(next.status))
        if (newTerminal) onTerminated(state, requireNotNull(terminalReason), source)
        publish(state)
    }

    /** Caller must already have real transaction proof; ordinary state updates cannot clear a timeout. */
    @Synchronized fun cleanupSettled(expectedSessionId: String?) {
        if (!isCurrent(expectedSessionId)) return
        state = state.copy(cleanupPending = false, cleanupUnconfirmed = false)
        update(state, expectedSessionId, "CLEANUP_SETTLED")
    }

    /** Normal Stop may finish its bounded cleanup; it no longer permits capture or recovery. */
    @Synchronized fun terminate(reason: String, source: String, error: String? = null,
                                expectedSessionId: String? = state.recordingSessionId) {
        if (!isCurrent(expectedSessionId)) return
        val first = latch(reason, RecorderStatus.FINALIZING, error)
        update(state, expectedSessionId, source)
        if (first) onTerminated(state, reason, source)
    }

    @Synchronized fun unconfirmed(failure: String, message: String,
                                  expectedSessionId: String? = state.recordingSessionId) {
        if (!isCurrent(expectedSessionId)) return
        val first = latch(failure, RecorderStatus.ERROR, failure)
        terminalStatus = RecorderStatus.ERROR
        terminalError = failure
        releaseWakeLock()
        update(state.copy(status = RecorderStatus.ERROR, cleanupPending = true,
            cleanupUnconfirmed = true, lastError = failure, message = message), expectedSessionId, "CLEANUP_TIMEOUT")
        if (first) onTerminated(state, failure, "CLEANUP_TIMEOUT")
    }

    private fun latch(reason: String, status: String, error: String?): Boolean {
        if (terminalReason != null) return false
        terminalReason = reason; terminalStatus = status; terminalError = error
        return true
    }

    private companion object {
        val END_STATUSES = setOf(RecorderStatus.ERROR, RecorderStatus.STOPPED, RecorderStatus.CAMERA_UNAVAILABLE)
    }
}
