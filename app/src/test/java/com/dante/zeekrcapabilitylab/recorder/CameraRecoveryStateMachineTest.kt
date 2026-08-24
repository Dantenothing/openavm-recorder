package com.dante.zeekrcapabilitylab.recorder

import com.dante.zeekrcapabilitylab.service.recorder.CameraRecoveryAction
import com.dante.zeekrcapabilitylab.service.recorder.CameraRecoveryPhase
import com.dante.zeekrcapabilitylab.service.recorder.CameraRecoveryPolicy
import com.dante.zeekrcapabilitylab.service.recorder.CameraRecoveryStateMachine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraRecoveryStateMachineTest {

    private val generation = 7L
    private val policy = CameraRecoveryPolicy(
        availabilityDebounceMs = 1_500L,
        retryDelaysMs = longArrayOf(2_000L, 5_000L, 10_000L, 20_000L),
        maxAttempts = 5,
        recoveryWindowMs = 120_000L,
        stabilityGraceMs = 30_000L,
    )

    private fun recordingMachine(nowMs: Long = 1_000L): CameraRecoveryStateMachine =
        CameraRecoveryStateMachine(policy).also {
            it.beginManualSession(generation, "2")
            assertEquals(
                CameraRecoveryStateMachine.RecordingStartOutcome.NORMAL,
                it.markRecordingStarted(generation, nowMs),
            )
        }

    @Test
    fun aSessionThatNeverRecordedCannotArmRecovery() {
        val machine = CameraRecoveryStateMachine(policy)
        machine.beginManualSession(generation, "2")

        assertFalse(machine.beginRecoverableLoss(generation, "CAMERA_IN_USE", 2_000L))
        assertEquals(CameraRecoveryPhase.HEALTHY, machine.snapshot.phase)
        assertNull(machine.nextWakeAtMs())
    }

    @Test
    fun availabilityBeforeFinalizeIsRememberedAndDebouncedAfterFinalize() {
        val machine = recordingMachine()
        assertTrue(machine.beginRecoverableLoss(generation, "CAMERA_DISCONNECTED", 2_000L))

        machine.onAvailability(generation, "2", available = true, nowMs = 2_100L)
        assertEquals(CameraRecoveryPhase.FINALIZING, machine.snapshot.phase)
        assertNull(machine.snapshot.nextAttemptAtMs)

        machine.finalizeCompleted(generation, 3_000L)
        assertEquals(CameraRecoveryPhase.WAITING_CAMERA, machine.snapshot.phase)
        assertEquals(4_500L, machine.snapshot.nextAttemptAtMs)
        assertEquals(4_500L, machine.nextWakeAtMs())
    }

    @Test
    fun wrongCameraAndDuplicateAvailabilityCannotCreateDuplicateAttempts() {
        val machine = recordingMachine()
        machine.beginRecoverableLoss(generation, "CAMERA_DISCONNECTED", 2_000L)
        machine.finalizeCompleted(generation, 2_100L)

        machine.onAvailability(generation, "0", available = true, nowMs = 2_200L)
        assertNull(machine.snapshot.nextAttemptAtMs)

        machine.onAvailability(generation, "2", available = true, nowMs = 2_300L)
        val firstWake = machine.nextWakeAtMs()
        machine.onAvailability(generation, "2", available = true, nowMs = 2_400L)
        assertEquals(firstWake, machine.nextWakeAtMs())
    }

    @Test
    fun failedReopenRetriesWithoutAnotherAvailabilityCallback() {
        val machine = recordingMachine()
        machine.beginRecoverableLoss(generation, "CAMERA_DISCONNECTED", 2_000L)
        machine.onAvailability(generation, "2", available = true, nowMs = 2_050L)
        machine.finalizeCompleted(generation, 2_100L)

        assertEquals(
            CameraRecoveryAction.Attempt(generation, 1),
            machine.onTimer(generation, 3_600L),
        )
        assertEquals(CameraRecoveryPhase.RESUMING, machine.snapshot.phase)

        assertEquals(
            CameraRecoveryAction.None,
            machine.attemptFailed(generation, recoverable = true, reason = "CAMERA_IN_USE", nowMs = 4_000L),
        )
        assertEquals(6_000L, machine.snapshot.nextAttemptAtMs)
        assertEquals(
            CameraRecoveryAction.Attempt(generation, 2),
            machine.onTimer(generation, 6_000L),
        )
    }

    @Test
    fun unavailableCancelsAnUnspentAvailabilityDebounce() {
        val machine = recordingMachine()
        machine.beginRecoverableLoss(generation, "CAMERA_DISCONNECTED", 2_000L)
        machine.finalizeCompleted(generation, 2_100L)
        machine.onAvailability(generation, "2", available = true, nowMs = 2_200L)
        assertEquals(3_700L, machine.snapshot.nextAttemptAtMs)

        machine.onAvailability(generation, "2", available = false, nowMs = 2_300L)
        assertNull(machine.snapshot.nextAttemptAtMs)
        assertEquals(machine.snapshot.deadlineAtMs, machine.nextWakeAtMs())
    }

    @Test
    fun hardDeadlineAlwaysAbandonsWaiting() {
        val machine = recordingMachine()
        machine.beginRecoverableLoss(generation, "CAMERA_DISCONNECTED", 2_000L)
        machine.finalizeCompleted(generation, 2_100L)

        assertEquals(
            CameraRecoveryAction.Abandon("RECOVERY_WINDOW_EXPIRED"),
            machine.onTimer(generation, 122_000L),
        )
        assertEquals(CameraRecoveryPhase.TERMINAL, machine.snapshot.phase)
        assertNull(machine.nextWakeAtMs())
    }

    @Test
    fun fifthFailedOpenExhaustsTheAttemptBudget() {
        val machine = recordingMachine()
        machine.beginRecoverableLoss(generation, "CAMERA_DISCONNECTED", 2_000L)
        machine.onAvailability(generation, "2", available = true, nowMs = 2_100L)
        machine.finalizeCompleted(generation, 2_200L)

        var now = 3_700L
        for (attempt in 1..5) {
            assertEquals(
                CameraRecoveryAction.Attempt(generation, attempt),
                machine.onTimer(generation, now),
            )
            val failure = machine.attemptFailed(
                generation,
                recoverable = true,
                reason = "CAMERA_IN_USE",
                nowMs = now + 100L,
            )
            if (attempt < 5) {
                assertEquals(CameraRecoveryAction.None, failure)
                now = machine.snapshot.nextAttemptAtMs!!
            } else {
                assertEquals(CameraRecoveryAction.Abandon("RECOVERY_ATTEMPT_LIMIT"), failure)
            }
        }
        assertEquals(CameraRecoveryPhase.TERMINAL, machine.snapshot.phase)
    }

    @Test
    fun anAttemptStartedBeforeDeadlineMayFinishButCannotRetryAfterDeadline() {
        val machine = recordingMachine()
        machine.beginRecoverableLoss(generation, "CAMERA_DISCONNECTED", 2_000L)
        machine.finalizeCompleted(generation, 2_100L)
        machine.onAvailability(generation, "2", available = true, nowMs = 120_400L)

        assertEquals(
            CameraRecoveryAction.Attempt(generation, 1),
            machine.onTimer(generation, 121_900L),
        )
        assertEquals(
            CameraRecoveryAction.Abandon("RECOVERY_WINDOW_EXPIRED"),
            machine.attemptFailed(
                generation,
                recoverable = true,
                reason = "CAMERA_IN_USE",
                nowMs = 122_100L,
            ),
        )
    }

    @Test
    fun recoveryBudgetResetsOnlyAfterStabilityGrace() {
        val machine = recordingMachine()
        machine.beginRecoverableLoss(generation, "CAMERA_DISCONNECTED", 2_000L)
        machine.onAvailability(generation, "2", available = true, nowMs = 2_100L)
        machine.finalizeCompleted(generation, 2_200L)
        machine.onTimer(generation, 3_700L)

        assertEquals(
            CameraRecoveryStateMachine.RecordingStartOutcome.RESUMED,
            machine.markRecordingStarted(generation, 4_000L),
        )
        assertEquals(CameraRecoveryPhase.PROBATION, machine.snapshot.phase)
        assertEquals(1, machine.snapshot.attemptsMade)

        assertTrue(machine.beginRecoverableLoss(generation, "CAMERA_DISCONNECTED", 10_000L))
        assertEquals(1, machine.snapshot.attemptsMade)
        assertEquals(122_000L, machine.snapshot.deadlineAtMs)

        machine.finalizeCompleted(generation, 10_100L)
        machine.onAvailability(generation, "2", available = true, nowMs = 10_200L)
        machine.onTimer(generation, 11_700L)
        machine.markRecordingStarted(generation, 12_000L)
        assertEquals(2, machine.snapshot.attemptsMade)

        assertEquals(CameraRecoveryAction.None, machine.onTimer(generation, 42_000L))
        assertEquals(CameraRecoveryPhase.HEALTHY, machine.snapshot.phase)
        assertEquals(0, machine.snapshot.attemptsMade)
        assertNull(machine.snapshot.deadlineAtMs)
    }

    @Test
    fun stopInvalidatesAllLateTimersAndAsyncCompletions() {
        val machine = recordingMachine()
        machine.beginRecoverableLoss(generation, "CAMERA_DISCONNECTED", 2_000L)
        machine.onAvailability(generation, "2", available = true, nowMs = 2_100L)
        machine.finalizeCompleted(generation, 2_200L)
        machine.onTimer(generation, 3_700L)

        machine.cancelManualSession(generation)

        assertEquals(CameraRecoveryAction.None, machine.onTimer(generation, 4_000L))
        assertEquals(
            CameraRecoveryStateMachine.RecordingStartOutcome.STALE,
            machine.markRecordingStarted(generation, 4_100L),
        )
        assertEquals(CameraRecoveryPhase.TERMINAL, machine.snapshot.phase)
        assertFalse(machine.snapshot.hasRecordedSuccessfully)
    }

    @Test
    fun staleAvailabilityFromAnOlderSessionCannotAffectTheCurrentOne() {
        val machine = recordingMachine()
        machine.beginRecoverableLoss(generation, "CAMERA_DISCONNECTED", 2_000L)
        machine.finalizeCompleted(generation, 2_100L)

        machine.onAvailability(generation - 1L, "2", available = true, nowMs = 2_200L)

        assertEquals(CameraRecoveryPhase.WAITING_CAMERA, machine.snapshot.phase)
        assertNull(machine.snapshot.nextAttemptAtMs)
    }

    @Test
    fun permanentReopenFailureFailsClosedImmediately() {
        val machine = recordingMachine()
        machine.beginRecoverableLoss(generation, "CAMERA_DISCONNECTED", 2_000L)
        machine.onAvailability(generation, "2", available = true, nowMs = 2_100L)
        machine.finalizeCompleted(generation, 2_200L)
        machine.onTimer(generation, 3_700L)

        assertEquals(
            CameraRecoveryAction.Abandon("CAMERA_DISABLED"),
            machine.attemptFailed(generation, recoverable = false, reason = "CAMERA_DISABLED", nowMs = 4_000L),
        )
        assertEquals(CameraRecoveryPhase.TERMINAL, machine.snapshot.phase)
    }
}
