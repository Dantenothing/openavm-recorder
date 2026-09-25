package com.dante.zeekrcapabilitylab.mirror

import com.dante.zeekrcapabilitylab.probe.camera.*
import com.dante.zeekrcapabilitylab.service.recorder.*
import org.junit.Assert.*
import org.junit.Test

class MirrorRecordingTransferTest {
    private fun config(role: RecordingSourceRole = RecordingSourceRole.SURROUND, lapse: Boolean = false) = RecorderConfig(
        SessionSourceSnapshot(role, if (role == RecordingSourceRole.SURROUND) "surround" else "cabin",
            CameraFormatProfile(if (role == RecordingSourceRole.SURROUND) ProfileSize(1280, 5140) else ProfileSize(3840, 2160), 28_000_000),
            if (role == RecordingSourceRole.SURROUND) RecordingLayoutKind.FOUR_LANE_V1 else RecordingLayoutKind.SINGLE_V1),
        120, 15L * 1024 * 1024 * 1024,
        recordingMode = if (lapse) RecordingMode.TIME_LAPSE else RecordingMode.NORMAL,
        timeLapseMultiplier = if (lapse) 30 else 1, mirrorPreviewEnabled = true,
    )
    private fun state(config: RecorderConfig) = RecorderState(status = RecorderStatus.RECORDING,
        sourceRole = config.source.sourceRole, cameraId = config.cameraId, recordingSessionId = "session",
        recordingMode = config.recordingMode, timeLapseMultiplier = config.timeLapseMultiplier,
        mirrorPreviewManaged = true, activeStorageKind = RecordingStorageKind.USB_MEDIASTORE, activeStorageUuid = "usb-a")

    @Test fun bothSourceDirectionsKeepModeAndStorageWithoutSelectingASecondConcurrentCamera() {
        for (role in listOf(RecordingSourceRole.SURROUND, RecordingSourceRole.CABIN)) for (lapse in listOf(false, true)) {
            val from = config(role, lapse)
            val target = config(if (role == RecordingSourceRole.SURROUND) RecordingSourceRole.CABIN else RecordingSourceRole.SURROUND, lapse)
            val transfer = checkNotNull(MirrorRecordingTransfer.capture(from, state(from)))
            val next = transfer.configure(target.copy(segmentSeconds = 60, storageLimitBytes = 8L * 1024 * 1024 * 1024))
            assertEquals(target.source, next.source)
            assertEquals(from.segmentSeconds, next.segmentSeconds)
            assertEquals(from.storageLimitBytes, next.storageLimitBytes)
            assertEquals(from.recordingMode, next.recordingMode)
            assertEquals(from.timeLapseMultiplier, next.timeLapseMultiplier)
            assertEquals(RecordingStoragePreference.USB_PREFERRED, next.storagePreference)
            assertFalse(RecordingModeSwitchGate.storageMatches(transfer.storage, RecordingStorageIdentity(RecordingStorageKind.INTERNAL)))
            assertFalse(RecordingModeSwitchGate.storageMatches(transfer.storage, transfer.storage.copy(storageUuid = "usb-b")))
            assertTrue(next.validate().isEmpty())
        }
    }
    @Test fun internalRecordingStaysInternalEvenWhenTargetDefaultsToUsb() {
        val config = config()
        val transfer = checkNotNull(MirrorRecordingTransfer.capture(config, state(config).copy(
            activeStorageKind = RecordingStorageKind.INTERNAL, activeStorageUuid = null)))
        assertEquals(RecordingStoragePreference.INTERNAL_ONLY, transfer.configure(config(RecordingSourceRole.CABIN)).storagePreference)
    }
    @Test fun stoppedCabinCannotReuseAnEarlierRecordingIntentOnReturn() {
        val cabin = config(RecordingSourceRole.CABIN)
        assertNotNull(MirrorRecordingTransfer.capture(cabin, state(cabin)))
        for (status in listOf(RecorderStatus.STOPPED, RecorderStatus.IDLE, RecorderStatus.PREVIEWING, RecorderStatus.WAITING_CAMERA,
            RecorderStatus.ERROR, RecorderStatus.FINALIZING, RecorderStatus.STARTING)) {
            assertNull(status, MirrorRecordingTransfer.capture(cabin, state(cabin).copy(status = status)))
        }
    }
    @Test fun staleConfigAndUnsettledOrUnidentifiedOutputCannotAuthorizeRecording() {
        val config = config(lapse = true); val state = state(config)
        for (bad in listOf(state.copy(cameraId = "other"), state.copy(sourceRole = RecordingSourceRole.CABIN),
            state.copy(recordingMode = RecordingMode.NORMAL), state.copy(timeLapseMultiplier = 60),
            state.copy(lastError = "FAILED"), state.copy(cleanupPending = true), state.copy(cleanupUnconfirmed = true),
            state.copy(recordingSessionId = null), state.copy(mirrorPreviewManaged = false), state.copy(activeStorageUuid = ""))) {
            assertNull(MirrorRecordingTransfer.capture(config, bad))
        }
        val ir = config(RecordingSourceRole.IR)
        assertNull(MirrorRecordingTransfer.capture(ir, state(ir)))
    }
    @Test fun cancelledSourceSwitchCannotRestartAfterLateNativeReleaseAndNewPreviewCommand() {
        val gate = MirrorHandoffGate()
        val old = gate.begin(MirrorHandoffGate.Target.RECORD, 0, "surround")!!
        gate.cancel()
        val preview = gate.begin(MirrorHandoffGate.Target.CABIN, 50)!!
        assertEquals(MirrorHandoffGate.Decision.CANCEL, gate.poll(old.token, 60, true, true, true, true, true, true))
        assertEquals(preview, gate.pending)
        assertEquals(MirrorHandoffGate.Decision.START, gate.poll(preview.token, 60, true, true, true, true, true, true))
        assertNull(gate.pending)
    }
}
