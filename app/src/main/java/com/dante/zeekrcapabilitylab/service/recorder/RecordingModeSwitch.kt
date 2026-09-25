package com.dante.zeekrcapabilitylab.service.recorder

data class RecordingModeChoice(val mode: RecordingMode, val multiplier: Int = 1) {
    fun valid() = when (mode) {
        RecordingMode.NORMAL -> multiplier == 1
        RecordingMode.TIME_LAPSE -> multiplier in TimeLapsePolicy.MULTIPLIERS
    }
}

data class RecordingModeSwitchProgress(
    val target: RecordingModeChoice,
    val releasing: Boolean = true,
    val cancelled: Boolean = false,
)

data class RecordingModeHandoffResult(val released: Boolean, val failure: String? = null)

data class RecordingModeSwitchReadiness(
    val released: Boolean,
    val saved: Boolean,
    val interactive: Boolean,
    val displayOn: Boolean,
    val storageMatches: Boolean,
    val ownerCurrent: Boolean,
) {
    val ready: Boolean get() = released && saved && interactive && displayOn && storageMatches && ownerCurrent
}

/** One explicit gesture grants one bounded continuation, never a persistent auto-start preference. */
class RecordingModeSwitchGate(private val timeoutMs: Long = 20_000) {
    data class Request(val token: Long, val sessionId: String, val target: RecordingModeChoice, val deadlineMs: Long)
    private var serial = 0L
    var pending: Request? = null; private set

    fun begin(state: RecorderState, target: RecordingModeChoice, nowMs: Long): Request? {
        if (pending != null || !eligible(state) || !target.valid() ||
            target == RecordingModeChoice(state.recordingMode, state.timeLapseMultiplier)) return null
        return Request(++serial, requireNotNull(state.recordingSessionId), target, nowMs + timeoutMs).also { pending = it }
    }

    fun take(token: Long, nowMs: Long, readiness: RecordingModeSwitchReadiness): Request? {
        val request = pending?.takeIf { it.token == token } ?: return null
        pending = null
        return request.takeIf { nowMs < it.deadlineMs && readiness.ready }
    }

    fun cancel() { pending = null }

    companion object {
        fun eligible(state: RecorderState): Boolean = state.status == RecorderStatus.RECORDING &&
            !state.recordingSessionId.isNullOrBlank() && !state.cleanupPending && !state.cleanupUnconfirmed && state.lastError == null

        fun storageMatches(required: RecordingStorageIdentity, selected: RecordingStorageIdentity): Boolean =
            required.kind == selected.kind && (required.kind == RecordingStorageKind.INTERNAL ||
                (!required.storageUuid.isNullOrBlank() && required.storageUuid.equals(selected.storageUuid, ignoreCase = true)))

        /** Preserve the captured edge; a later ON snapshot cannot re-authorize this request. */
        fun powerRevoked(snapshot: VehiclePowerSnapshot, source: String): Boolean =
            source == "SCREEN_OFF" || source == "SYSTEM_SHUTDOWN" || !snapshot.interactive || snapshot.mainDisplayState != "ON"

        fun mediaRemovalApplies(required: RecordingStorageIdentity?, path: String?): Boolean =
            required?.kind == RecordingStorageKind.USB_MEDIASTORE &&
                (path.isNullOrBlank() || required.storageUuid.isNullOrBlank() ||
                    path.split('/').any { it.equals(required.storageUuid, ignoreCase = true) })
    }
}
