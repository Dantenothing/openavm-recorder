package com.dante.zeekrcapabilitylab.recorder

import com.dante.zeekrcapabilitylab.service.recorder.*
import org.junit.Assert.*
import org.junit.Test

class CameraExternalOwnerRecoveryTest {
    @Test fun ownOpenAvailabilityCannotRejectEncoderStartButExpiryStillDoes() {
        val m = held()
        assertFalse(m.mayStartRecording(1, 900))
        m.onAvailability(1, "surround", true, 1_000)
        assertTrue(m.onTimer(1, 2_500) is CameraRecoveryAction.Attempt)
        m.onAvailability(1, "surround", false, 2_501)
        assertFalse(m.mayOpen(1, 2_501))
        assertTrue(m.mayStartRecording(1, 2_501))
        assertFalse(m.mayStartRecording(1, 121_000))
        m.cancelManualSession(1)
        assertFalse(m.mayStartRecording(1, 2_502))
    }
    @Test fun issuedAttemptMustBeRecheckedAfterStopExpiryAndNewSession() {
        for (reason in listOf("STOP", "EXPIRY", "NEW_SESSION", "UNAVAILABLE")) {
            val m = held()
            m.onAvailability(1, "surround", true, 1_000)
            assertTrue(m.onTimer(1, 2_500) is CameraRecoveryAction.Attempt)
            assertTrue(m.mayOpen(1, 2_500))
            when (reason) {
                "STOP" -> m.cancelManualSession(1)
                "NEW_SESSION" -> m.beginManualSession(2, "surround")
                "UNAVAILABLE" -> m.onAvailability(1, "surround", false, 2_501)
            }
            assertFalse(m.mayOpen(1, if (reason == "EXPIRY") 121_000 else 2_501))
        }
    }

    @Test fun powerOffObservedDuringCleanupRevokesARecoveryThatWasInitiallyValid() {
        val away = VehicleAwayStateMachine().apply {
            beginManualSession(1)
            onPowerSnapshot(1, false, true, true, 100)
        }
        assertEquals(VehicleAwayAction.None, away.onCameraLoss(1))
        val m = held()
        m.onAvailability(1, "surround", true, 1_000)
        assertTrue(m.onTimer(1, 2_500) is CameraRecoveryAction.Attempt)
        away.onPowerSnapshot(1, false, false, false, 2_501)
        assertTrue(away.onCameraLoss(1) is VehicleAwayAction.Confirm)
        m.disarmResume(1, "VEHICLE_AWAY_CONFIRMED")
        assertFalse(m.mayOpen(1, 2_502))
    }

    private fun held(): CameraRecoveryStateMachine = CameraRecoveryStateMachine(
        CameraRecoveryPolicy(availabilityWaitMs = 30 * 60_000)).apply {
        beginManualSession(1, "surround"); markRecordingStarted(1, 0)
        onAvailability(1, "surround", false, 50)
        assertTrue(beginRecoverableLoss(1, "CAMERA_DISCONNECTED", 100))
        finalizeCompleted(1, 200)
    }
    @Test fun tenMinutesInCabinSpendsNoReopenAttempts() {
        val m = held()
        assertEquals(CameraRecoveryAction.None, m.onTimer(1, 600_000))
        assertEquals(0, m.snapshot.attemptsMade); assertTrue(m.snapshot.waitingForAvailability)
        m.onAvailability(1, "surround", true, 600_000)
        assertEquals(720_000L, m.snapshot.deadlineAtMs)
        assertEquals(CameraRecoveryAction.Attempt(1, 1), m.onTimer(1, 601_500))
        assertEquals(CameraRecoveryStateMachine.RecordingStartOutcome.RESUMED, m.markRecordingStarted(1, 602_000))
    }
    @Test fun withdrawnAvailabilityDoesNotBurnAttemptOrKeepOpeningBusyCamera() {
        val m = held(); m.onAvailability(1, "surround", true, 1_000)
        m.onAvailability(1, "surround", false, 2_000)
        assertEquals(CameraRecoveryAction.None, m.onTimer(1, 3_000)); assertNull(m.snapshot.nextAttemptAtMs)
        m.onAvailability(1, "surround", true, 4_000)
        assertEquals(CameraRecoveryAction.Attempt(1, 1), m.onTimer(1, 5_500))
        m.onAvailability(1, "surround", false, 5_600)
        m.attemptFailed(1, true, "CAMERA_IN_USE", 5_700)
        assertNull(m.snapshot.nextAttemptAtMs); assertEquals(1, m.snapshot.attemptsMade)
    }
    @Test fun absoluteWaitLimitCannotBeExtendedByRepeatedAvailabilityEdges() {
        val m = held()
        for (t in 1..10) { m.onAvailability(1, "surround", true, t * 100_000L); m.onAvailability(1, "surround", false, t * 100_000L + 1) }
        assertEquals(1_800_100L, m.snapshot.deadlineAtMs)
        assertEquals(CameraRecoveryAction.Abandon("RECOVERY_WINDOW_EXPIRED"), m.onTimer(1, 1_800_100))
    }
    @Test fun manualStopOrPowerDisarmMakesLateCabinReleaseHarmless() {
        for (power in listOf(false, true)) {
            val m = held()
            if (power) m.disarmResume(1, "VEHICLE_AWAY") else m.cancelManualSession(1)
            m.onAvailability(1, "surround", true, 600_000)
            assertEquals(CameraRecoveryAction.None, m.onTimer(1, 601_500))
            assertFalse(m.snapshot.resumeAllowed); assertNull(m.nextWakeAtMs())
        }
    }
    @Test fun availabilityDoesNotAuthorizeOpeningBeforeNativeCleanup() {
        val m = CameraRecoveryStateMachine(CameraRecoveryPolicy(availabilityWaitMs = 1_800_000))
        m.beginManualSession(1, "surround"); m.markRecordingStarted(1, 0)
        m.beginRecoverableLoss(1, "CAMERA_DISCONNECTED", 100)
        m.onAvailability(1, "surround", true, 1_000)
        assertEquals(CameraRecoveryAction.None, m.onTimer(1, 4_000)); assertNull(m.nextWakeAtMs())
    }
    @Test fun aLaterHardDeviceFailureRevokesAnAlreadyPendingRecovery() {
        val m = held(); m.terminateManualSession(1, "ERROR_CAMERA_DEVICE")
        m.onAvailability(1, "surround", true, 1_000)
        assertEquals(CameraRecoveryAction.None, m.onTimer(1, 3_000))
        assertFalse(m.snapshot.resumeAllowed); assertEquals("ERROR_CAMERA_DEVICE", m.snapshot.lastReason)
    }
}
