package com.dante.zeekrcapabilitylab.mirror

import com.dante.zeekrcapabilitylab.service.recorder.*

/** Captured for one explicit source switch; a saved source configuration is not permission to resume. */
data class MirrorRecordingTransfer(val config: RecorderConfig, val storage: RecordingStorageIdentity, val session: String) {
    fun configure(target: RecorderConfig): RecorderConfig = target.copy(
        segmentSeconds = config.segmentSeconds,
        storageLimitBytes = config.storageLimitBytes,
        minFreeBytes = config.minFreeBytes,
        usbQuotaBytes = config.usbQuotaBytes,
        storagePreference = if (storage.kind == RecordingStorageKind.INTERNAL)
            RecordingStoragePreference.INTERNAL_ONLY else RecordingStoragePreference.USB_PREFERRED,
    )

    companion object {
        fun capture(config: RecorderConfig, state: RecorderState): MirrorRecordingTransfer? {
            if (state.status != RecorderStatus.RECORDING || state.sourceRole != config.source.sourceRole ||
                !MirrorPreviewPolicy.supports(config) || config.cameraId != state.cameraId ||
                state.recordingMode != config.recordingMode || state.timeLapseMultiplier != config.timeLapseMultiplier ||
                state.lastError != null || state.cleanupPending || state.cleanupUnconfirmed ||
                state.recordingSessionId == null || !state.mirrorPreviewManaged) return null
            if (state.activeStorageKind == RecordingStorageKind.USB_MEDIASTORE && state.activeStorageUuid.isNullOrBlank()) return null
            return MirrorRecordingTransfer(config, RecordingStorageIdentity(state.activeStorageKind, state.activeStorageUuid), state.recordingSessionId)
        }
    }
}
