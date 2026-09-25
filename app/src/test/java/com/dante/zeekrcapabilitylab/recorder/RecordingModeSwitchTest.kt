package com.dante.zeekrcapabilitylab.recorder

import com.dante.zeekrcapabilitylab.probe.camera.CameraFormatProfile
import com.dante.zeekrcapabilitylab.probe.camera.ProfileSize
import com.dante.zeekrcapabilitylab.product.RecordingModeSwitchConfig
import com.dante.zeekrcapabilitylab.product.RecordingQuality
import com.dante.zeekrcapabilitylab.service.recorder.*
import org.junit.Assert.*
import org.junit.Test

class RecordingModeSwitchTest {
    private val running = RecorderState(status = RecorderStatus.RECORDING, recordingSessionId = "manual-1")
    private val timeLapse = RecordingModeChoice(RecordingMode.TIME_LAPSE, 30)
    private val ready = RecordingModeSwitchReadiness(true, true, true, true, true, true)
    private fun config() = RecorderConfig(
        source = SessionSourceSnapshot(RecordingSourceRole.SURROUND, "camera-2",
            CameraFormatProfile(ProfileSize(1280, 5120), 20_000_000), RecordingLayoutKind.FOUR_LANE_V1,
            mappingRevision = 7),
        segmentSeconds = 120, storageLimitBytes = 15L * 1024 * 1024 * 1024,
        mirrorPreviewEnabled = true, requestedFrameRate = 20, strictFrameRate = true,
    )

    @Test fun onlySupportedSpeedsAreAccepted() {
        val gate = RecordingModeSwitchGate()
        assertNull(gate.begin(running, RecordingModeChoice(RecordingMode.NORMAL, 30), 0))
        assertNull(gate.begin(running, RecordingModeChoice(RecordingMode.TIME_LAPSE, 1), 0))
        assertNull(gate.begin(running, RecordingModeChoice(RecordingMode.TIME_LAPSE, 999), 0))
        assertNotNull(gate.begin(running, timeLapse, 0))
    }

    @Test fun unsupportedRasterKeepsCompatibilityBackendWhenChangingMode() {
        val next = RecordingModeSwitchConfig.build(config().copy(sharedInputRecordingEnabled = true),
            timeLapse, RecordingQuality.ORIGINAL, true)
        assertFalse(next.sharedInputRecordingEnabled)
        assertEquals(RecordingMode.TIME_LAPSE, next.recordingMode)
    }

    @Test fun stoppedStartingFinalizingAndUnconfirmedSessionsCannotAuthorizeSwitch() {
        for (status in listOf(RecorderStatus.IDLE, RecorderStatus.STARTING, RecorderStatus.FINALIZING,
            RecorderStatus.STOPPED, RecorderStatus.ERROR, RecorderStatus.WAITING_CAMERA, RecorderStatus.PREVIEWING)) {
            assertFalse(status, RecordingModeSwitchGate.eligible(running.copy(status = status)))
        }
        assertFalse(RecordingModeSwitchGate.eligible(running.copy(cleanupPending = true)))
        assertFalse(RecordingModeSwitchGate.eligible(running.copy(cleanupUnconfirmed = true)))
        assertFalse(RecordingModeSwitchGate.eligible(running.copy(lastError = "USB_COMMIT_FAILED")))
        assertFalse(RecordingModeSwitchGate.eligible(running.copy(recordingSessionId = null)))
    }

    @Test fun selectingCurrentModeDoesNotCreateAStopAndRestart() {
        val gate = RecordingModeSwitchGate()
        assertNull(gate.begin(running, RecordingModeChoice(RecordingMode.NORMAL), 0))
        assertNull(gate.begin(running.copy(recordingMode = RecordingMode.TIME_LAPSE, timeLapseMultiplier = 30), timeLapse, 0))
        assertNull(gate.pending)
    }

    @Test fun duplicateTapsDoNotReplaceRequestAndCompletionIsOneShot() {
        val gate = RecordingModeSwitchGate()
        val request = requireNotNull(gate.begin(running, timeLapse, 0))
        assertNull(gate.begin(running, timeLapse.copy(multiplier = 60), 1))
        assertEquals(request, gate.take(request.token, 100, ready))
        assertNull(gate.take(request.token, 101, ready))
        assertNull(gate.pending)
    }

    @Test fun stopPowerOffOrDestructionCancellationSurvivesLateCloseAcknowledgement() {
        val gate = RecordingModeSwitchGate()
        val request = requireNotNull(gate.begin(running, timeLapse, 0))
        gate.cancel()
        assertNull(gate.take(request.token, 50, ready))
        assertNull(gate.pending)
    }

    @Test fun staleCompletionCannotConsumeANewerManualRequest() {
        val gate = RecordingModeSwitchGate()
        val old = requireNotNull(gate.begin(running, timeLapse, 0))
        gate.cancel()
        val next = requireNotNull(gate.begin(running.copy(recordingSessionId = "manual-2"), timeLapse, 100))
        assertNull(gate.take(old.token, 101, ready))
        assertEquals(next, gate.pending)
        assertEquals(next, gate.take(next.token, 102, ready))
    }

    @Test fun deadlineRevokesContinuationEvenWhenNativeCleanupEventuallySucceeds() {
        val gate = RecordingModeSwitchGate(timeoutMs = 100)
        val request = requireNotNull(gate.begin(running, timeLapse, 500))
        assertNull(gate.take(request.token, 600, ready))
        assertNull(gate.take(request.token, 601, ready))
    }

    @Test fun everyReleaseSavePowerStorageAndOwnerConditionMustHold() {
        val unsafe = listOf(ready.copy(released = false), ready.copy(saved = false), ready.copy(interactive = false),
            ready.copy(displayOn = false), ready.copy(storageMatches = false), ready.copy(ownerCurrent = false))
        for (condition in unsafe) {
            val gate = RecordingModeSwitchGate()
            val request = requireNotNull(gate.begin(running, timeLapse, 0))
            assertNull(condition.toString(), gate.take(request.token, 1, condition))
            assertNull(gate.take(request.token, 2, ready))
        }
    }

    @Test fun usbCannotBeReplacedByInternalStorageOrAnotherDrive() {
        val usb = RecordingStorageIdentity(RecordingStorageKind.USB_MEDIASTORE, "usb-a")
        val internal = RecordingStorageIdentity(RecordingStorageKind.INTERNAL)
        assertTrue(RecordingModeSwitchGate.storageMatches(usb, usb))
        assertTrue(RecordingModeSwitchGate.storageMatches(usb, usb.copy(storageUuid = "USB-A")))
        assertFalse(RecordingModeSwitchGate.storageMatches(usb, usb.copy(storageUuid = "usb-b")))
        assertFalse(RecordingModeSwitchGate.storageMatches(usb, internal))
        assertFalse(RecordingModeSwitchGate.storageMatches(internal, usb))
        assertFalse(RecordingModeSwitchGate.storageMatches(usb.copy(storageUuid = null), usb.copy(storageUuid = null)))
    }

    @Test fun anOffOnCycleRevokesTheOldRequestButAnOldDelayedProbeDoesNotCancelANewOne() {
        val on = VehiclePowerSnapshot(false, true, true, "ON")
        val off = on.copy(interactive = false, mainDisplayOn = false, mainDisplayState = "OFF")
        assertTrue(RecordingModeSwitchGate.powerRevoked(off, "DISPLAY_CHANGED"))
        assertTrue(RecordingModeSwitchGate.powerRevoked(on, "SCREEN_OFF"))
        assertTrue(RecordingModeSwitchGate.powerRevoked(on, "SYSTEM_SHUTDOWN"))
        assertFalse(RecordingModeSwitchGate.powerRevoked(on, "SCREEN_OFF_DELAYED_5"))
        val gate = RecordingModeSwitchGate()
        val request = requireNotNull(gate.begin(running, timeLapse, 0))
        if (RecordingModeSwitchGate.powerRevoked(off, "DISPLAY_CHANGED")) gate.cancel()
        assertNull(gate.take(request.token, 1, ready))
    }

    @Test fun removalEdgeIsBoundToTheRecordingDriveEvenIfItHasAlreadyRemounted() {
        val usb = RecordingStorageIdentity(RecordingStorageKind.USB_MEDIASTORE, "a1b2-c3d4")
        assertTrue(RecordingModeSwitchGate.mediaRemovalApplies(usb, "/storage/A1B2-C3D4/"))
        assertTrue(RecordingModeSwitchGate.mediaRemovalApplies(usb, "/mnt/media_rw/a1b2-c3d4"))
        assertTrue(RecordingModeSwitchGate.mediaRemovalApplies(usb, null))
        assertFalse(RecordingModeSwitchGate.mediaRemovalApplies(usb, "/storage/another-usb"))
        assertFalse(RecordingModeSwitchGate.mediaRemovalApplies(RecordingStorageIdentity(RecordingStorageKind.INTERNAL), null))
        assertFalse(RecordingModeSwitchGate.mediaRemovalApplies(null, null))
    }

    @Test fun timeLapseRetainsPhysicalCameraAndStorageWhileRestoringItsEncoderSettings() {
        val original = config()
        val next = RecordingModeSwitchConfig.build(original, timeLapse, RecordingQuality.BALANCED, true)
        assertEquals(original.source.copy(profile = next.profile), next.source)
        assertEquals(original.storagePreference, next.storagePreference)
        assertEquals(original.usbQuotaBytes, next.usbQuotaBytes)
        assertEquals(original.segmentSeconds, next.segmentSeconds)
        assertEquals(28_000_000, next.profile.bitrateBps)
        assertEquals(30, next.requestedFrameRate)
        assertEquals(30, next.timeLapseMultiplier)
        assertTrue(next.mirrorPreviewEnabled)
        assertFalse(next.strictFrameRate)
        assertEquals(1.0, requireNotNull(next.captureRateFpsOrNull()), 0.0)
        assertTrue(next.validate().isEmpty())
    }

    @Test fun returningToNormalRestoresQualityAndMirrorWithoutRetainingCaptureRate() {
        val lapse = RecordingModeSwitchConfig.build(config(), timeLapse, RecordingQuality.BALANCED, true)
        val next = RecordingModeSwitchConfig.build(lapse, RecordingModeChoice(RecordingMode.NORMAL), RecordingQuality.BALANCED, true)
        assertEquals(config(), next)
        assertNull(next.captureRateFpsOrNull())
        assertEquals(120, next.effectiveSegmentSeconds())
    }

    @Test fun cabinCanKeepItsSingleSourceMirrorWhileIrRemainsUnsupported() {
        for (role in listOf(RecordingSourceRole.CABIN, RecordingSourceRole.IR)) {
            val single = config().copy(source = SessionSourceSnapshot(role, "camera-9",
                CameraFormatProfile(ProfileSize(3840, 2160), 14_000_000), RecordingLayoutKind.SINGLE_V1), mirrorPreviewEnabled = false)
            val next = RecordingModeSwitchConfig.build(single, timeLapse, RecordingQuality.ORIGINAL, true)
            val normal = RecordingModeSwitchConfig.build(next, RecordingModeChoice(RecordingMode.NORMAL), RecordingQuality.ORIGINAL, true)
            assertEquals(single.source, next.source)
            assertEquals(role == RecordingSourceRole.CABIN, next.mirrorPreviewEnabled)
            assertEquals(role == RecordingSourceRole.CABIN, normal.mirrorPreviewEnabled)
            assertFalse(next.sharedInputRecordingEnabled)
            assertFalse(normal.sharedInputRecordingEnabled)
        }
    }

    @Test fun returningFromTimeLapseRestoresOptedInContinuousBackendAndSameCameraLayout() {
        val base = config().let { it.copy(sharedInputRecordingEnabled = true,
            source = it.source.copy(profile = it.profile.copy(size = ProfileSize(1280, 5140)),
                laneLayout = SegmentLaneLayoutFactory.forProfile(1280, 5140,
                    listOf("Front", "Rear", "Left", "Right"), listOf(1, 2, 3, 4), listOf(0, 0, 0, 0)))) }
        assertTrue(ProductContinuousPolicy.eligible(base))
        val lapse = RecordingModeSwitchConfig.build(base, timeLapse, RecordingQuality.ORIGINAL, true)
        assertTrue("The accepted shared-input route must survive a switch to time-lapse", ProductContinuousPolicy.eligible(lapse))
        assertTrue(lapse.mirrorPreviewEnabled)
        assertEquals(CaptureSubmissionMode.REPEATING_ENCODER, ContinuousVideoTiming.capturePlan(lapse).submissionMode)
        val normal = RecordingModeSwitchConfig.build(lapse, RecordingModeChoice(RecordingMode.NORMAL),
            RecordingQuality.ORIGINAL, true, normalSharedInput = true)
        assertTrue(ProductContinuousPolicy.eligible(normal))
        assertEquals(base.source.laneLayout, normal.source.laneLayout)
        assertEquals(base.cameraId, normal.cameraId)
        assertNull(normal.captureRateFpsOrNull())
        assertTrue(normal.mirrorPreviewEnabled)
        assertFalse(ProductContinuousPolicy.eligible(RecordingModeSwitchConfig.build(lapse,
            RecordingModeChoice(RecordingMode.NORMAL), RecordingQuality.ORIGINAL, true, normalSharedInput = false)))
    }
}
