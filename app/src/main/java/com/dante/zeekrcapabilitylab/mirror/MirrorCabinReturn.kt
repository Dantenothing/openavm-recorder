package com.dante.zeekrcapabilitylab.mirror

import com.dante.zeekrcapabilitylab.service.recorder.*

/** Frozen only for an explicit Cabin visit; the return click still needs a new bounded handoff. */
data class MirrorCabinReturn(val config: RecorderConfig, val storage: RecordingStorageIdentity) {
    companion object {
        fun capture(config: RecorderConfig, state: RecorderState): MirrorCabinReturn? {
            if (state.status != RecorderStatus.RECORDING || state.sourceRole != RecordingSourceRole.SURROUND ||
                config.source.sourceRole != RecordingSourceRole.SURROUND || config.cameraId != state.cameraId ||
                state.lastError != null || state.cleanupPending || state.cleanupUnconfirmed ||
                state.recordingSessionId == null || !state.mirrorPreviewManaged) return null
            if (state.activeStorageKind == RecordingStorageKind.USB_MEDIASTORE && state.activeStorageUuid.isNullOrBlank()) return null
            return MirrorCabinReturn(config, RecordingStorageIdentity(state.activeStorageKind, state.activeStorageUuid))
        }
    }
}
