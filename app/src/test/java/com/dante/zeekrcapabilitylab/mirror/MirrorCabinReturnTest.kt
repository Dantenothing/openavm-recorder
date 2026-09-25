package com.dante.zeekrcapabilitylab.mirror

import com.dante.zeekrcapabilitylab.probe.camera.*
import com.dante.zeekrcapabilitylab.service.recorder.*
import org.junit.Assert.*
import org.junit.Test

class MirrorCabinReturnTest {
    private val config = RecorderConfig(SessionSourceSnapshot(RecordingSourceRole.SURROUND, "camera", CameraFormatProfile(
        ProfileSize(1280, 5140), 28_000_000), RecordingLayoutKind.FOUR_LANE_V1), 60, 15L * 1024 * 1024 * 1024,
        recordingMode = RecordingMode.TIME_LAPSE, timeLapseMultiplier = 30, mirrorPreviewEnabled = true)
    private val state = RecorderState(status = RecorderStatus.RECORDING, sourceRole = RecordingSourceRole.SURROUND,
        cameraId = "camera", recordingSessionId = "session", mirrorPreviewManaged = true,
        activeStorageKind = RecordingStorageKind.USB_MEDIASTORE, activeStorageUuid = "original-usb")

    @Test fun returnPreservesTimeLapseAndExactUsbRatherThanFallingBackToInternal() {
        val plan = checkNotNull(MirrorCabinReturn.capture(config, state))
        assertEquals(config, plan.config)
        assertTrue(RecordingModeSwitchGate.storageMatches(plan.storage, RecordingStorageIdentity(RecordingStorageKind.USB_MEDIASTORE, "original-usb")))
        assertFalse(RecordingModeSwitchGate.storageMatches(plan.storage, RecordingStorageIdentity(RecordingStorageKind.USB_MEDIASTORE, "different-usb")))
        assertFalse(RecordingModeSwitchGate.storageMatches(plan.storage, RecordingStorageIdentity(RecordingStorageKind.INTERNAL)))
    }
    @Test fun idleErrorUnconfirmedCleanupAndDifferentProducerCannotAuthorizeReturnRecording() {
        for (blocked in listOf(state.copy(status = RecorderStatus.IDLE), state.copy(status = RecorderStatus.WAITING_CAMERA),
            state.copy(lastError = "FAILED"), state.copy(cleanupPending = true), state.copy(cleanupUnconfirmed = true),
            state.copy(cameraId = "other"), state.copy(recordingSessionId = null), state.copy(mirrorPreviewManaged = false),
            state.copy(sourceRole = RecordingSourceRole.CABIN), state.copy(activeStorageUuid = null))) {
            assertNull(MirrorCabinReturn.capture(config, blocked))
        }
    }
}
