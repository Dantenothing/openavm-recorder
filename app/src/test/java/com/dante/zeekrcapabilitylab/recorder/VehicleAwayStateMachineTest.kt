package com.dante.zeekrcapabilitylab.recorder

import com.dante.zeekrcapabilitylab.service.recorder.VehicleAwayAction
import com.dante.zeekrcapabilitylab.service.recorder.VehicleAwayPhase
import com.dante.zeekrcapabilitylab.service.recorder.VehicleAwayPolicy
import com.dante.zeekrcapabilitylab.service.recorder.VehicleAwayStateMachine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleAwayStateMachineTest {
    private val policy = VehicleAwayPolicy(confirmWindowMs = 30_000L)
    private val generation = 11L

    @Test
    fun ordinaryAppBackgroundNeverArmsWhileScreenAndMainDisplayStayOn() {
        val machine = activeMachine()

        assertEquals(VehicleAwayAction.None, machine.onAppForeground(generation, false, 1_000L))
        assertEquals(VehicleAwayPhase.ACTIVE, machine.snapshot.phase)
        assertNull(machine.nextWakeAtMs())
    }

    @Test
    fun allThreeSignalsArmOneBoundedConfirmationWindow() {
        val machine = activeMachine()
        machine.onAppForeground(generation, false, 1_000L)
        machine.onScreenPower(generation, screenOn = false, nowMs = 2_000L)

        val action = machine.onMainDisplayPower(generation, displayOn = false, nowMs = 3_000L)

        assertTrue(action is VehicleAwayAction.Schedule)
        assertEquals(33_000L, (action as VehicleAwayAction.Schedule).atMs)
        assertEquals(VehicleAwayPhase.PENDING, machine.snapshot.phase)
        assertEquals(33_000L, machine.nextWakeAtMs())
    }

    @Test
    fun timeoutConfirmsOnlyWhileAllSignalsStillHold() {
        val machine = pendingMachine()
        val token = machine.snapshot.pendingToken

        assertEquals(VehicleAwayAction.None, machine.onTimer(generation, token, 32_999L))
        assertEquals(
            VehicleAwayAction.Confirm(generation, "POWER_SIGNALS_STABLE"),
            machine.onTimer(generation, token, 33_000L),
        )
        assertEquals(VehicleAwayPhase.CONFIRMED, machine.snapshot.phase)
    }

    @Test
    fun screenOnOrForegroundCancelsPending() {
        val screenMachine = pendingMachine()
        assertEquals(
            VehicleAwayAction.Cancel("SCREEN_ON"),
            screenMachine.onScreenPower(generation, screenOn = true, nowMs = 5_000L),
        )
        assertEquals(VehicleAwayPhase.ACTIVE, screenMachine.snapshot.phase)

        val foregroundMachine = pendingMachine()
        assertEquals(
            VehicleAwayAction.Cancel("APP_FOREGROUND"),
            foregroundMachine.onAppForeground(generation, true, 5_000L),
        )
        assertEquals(VehicleAwayPhase.ACTIVE, foregroundMachine.snapshot.phase)
    }

    @Test
    fun mainDisplayMustRemainContinuouslyOff() {
        val machine = pendingMachine()
        val oldToken = machine.snapshot.pendingToken

        assertEquals(
            VehicleAwayAction.Cancel("MAIN_DISPLAY_ON"),
            machine.onMainDisplayPower(generation, displayOn = true, nowMs = 10_000L),
        )
        val rearmed = machine.onMainDisplayPower(generation, displayOn = false, nowMs = 12_000L)

        assertTrue(rearmed is VehicleAwayAction.Schedule)
        assertTrue(machine.snapshot.pendingToken != oldToken)
        assertEquals(42_000L, (rearmed as VehicleAwayAction.Schedule).atMs)
        assertEquals(VehicleAwayAction.None, machine.onTimer(generation, oldToken, 33_000L))
    }

    @Test
    fun cameraLossDuringPendingIsImmediateStrongConfirmation() {
        val machine = pendingMachine()

        assertEquals(
            VehicleAwayAction.Confirm(generation, "CAMERA_LOSS_DURING_PENDING"),
            machine.onCameraLoss(generation),
        )
        assertEquals(VehicleAwayPhase.CONFIRMED, machine.snapshot.phase)
    }

    @Test
    fun oemCameraLossWithoutScreenOffIsNotVehicleAway() {
        val machine = activeMachine()
        machine.onAppForeground(generation, false, 1_000L)

        assertEquals(VehicleAwayAction.None, machine.onCameraLoss(generation))
        assertEquals(VehicleAwayPhase.ACTIVE, machine.snapshot.phase)
        assertFalse(machine.snapshot.backgroundPowerOffEvidence)
    }

    @Test
    fun screenOffWhileBackgroundedMakesLaterCameraLossTerminalWithoutDisplayOff() {
        val machine = activeMachine()
        machine.onAppForeground(generation, false, 1_000L)
        machine.onScreenPower(generation, screenOn = false, nowMs = 2_000L)

        assertTrue(machine.snapshot.backgroundPowerOffEvidence)
        assertTrue(machine.snapshot.sawScreenOffWhileBackground)
        assertFalse(machine.snapshot.sawMainDisplayOffWhileBackground)
        assertEquals(
            VehicleAwayAction.Confirm(generation, "CAMERA_LOSS_AFTER_BACKGROUND_POWER_OFF"),
            machine.onCameraLoss(generation),
        )
    }

    @Test
    fun displayOffWhileBackgroundedMakesLaterCameraLossTerminalWithoutScreenOff() {
        val machine = activeMachine()
        machine.onAppForeground(generation, false, 1_000L)
        machine.onMainDisplayPower(generation, displayOn = false, nowMs = 2_000L)

        assertTrue(machine.snapshot.backgroundPowerOffEvidence)
        assertFalse(machine.snapshot.sawScreenOffWhileBackground)
        assertTrue(machine.snapshot.sawMainDisplayOffWhileBackground)
        assertEquals(
            VehicleAwayAction.Confirm(generation, "CAMERA_LOSS_AFTER_BACKGROUND_POWER_OFF"),
            machine.onCameraLoss(generation),
        )
    }

    @Test
    fun powerOnBounceDoesNotEraseEvidenceBeforeCameraLoss() {
        val machine = pendingMachine()
        machine.onScreenPower(generation, screenOn = true, nowMs = 4_000L)
        machine.onMainDisplayPower(generation, displayOn = true, nowMs = 5_000L)

        assertEquals(VehicleAwayPhase.ACTIVE, machine.snapshot.phase)
        assertTrue(machine.snapshot.backgroundPowerOffEvidence)
        assertEquals(
            VehicleAwayAction.Confirm(generation, "CAMERA_LOSS_AFTER_BACKGROUND_POWER_OFF"),
            machine.onCameraLoss(generation),
        )
    }

    @Test
    fun foregroundClearsBackgroundEvidenceBeforeLaterCameraLoss() {
        val machine = activeMachine()
        machine.onAppForeground(generation, false, 1_000L)
        machine.onScreenPower(generation, screenOn = false, nowMs = 2_000L)

        machine.onAppForeground(generation, true, 3_000L)

        assertFalse(machine.snapshot.backgroundPowerOffEvidence)
        assertFalse(machine.snapshot.sawScreenOffWhileBackground)
        assertEquals(VehicleAwayAction.None, machine.onCameraLoss(generation))
        assertEquals(VehicleAwayPhase.ACTIVE, machine.snapshot.phase)
    }

    @Test
    fun powerOffBeforeBackgroundIsLatchedWhenAppLeavesForeground() {
        val machine = activeMachine()
        machine.onScreenPower(generation, screenOn = false, nowMs = 1_000L)

        machine.onAppForeground(generation, false, 2_000L)

        assertTrue(machine.snapshot.backgroundPowerOffEvidence)
        assertTrue(machine.snapshot.sawScreenOffWhileBackground)
        assertEquals(
            VehicleAwayAction.Confirm(generation, "CAMERA_LOSS_AFTER_BACKGROUND_POWER_OFF"),
            machine.onCameraLoss(generation),
        )
    }

    @Test
    fun newManualSessionCannotInheritOldBackgroundPowerEvidence() {
        val machine = activeMachine()
        machine.onAppForeground(generation, false, 1_000L)
        machine.onScreenPower(generation, screenOn = false, nowMs = 2_000L)
        machine.endSession(generation, "MANUAL_STOP")

        machine.beginManualSession(generation + 1L)

        assertFalse(machine.snapshot.backgroundPowerOffEvidence)
        assertFalse(machine.snapshot.sawScreenOffWhileBackground)
        assertFalse(machine.snapshot.sawMainDisplayOffWhileBackground)
        assertEquals(VehicleAwayAction.None, machine.onCameraLoss(generation + 1L))
    }

    @Test
    fun staleTimerFromOldSessionCannotStopNewManualSession() {
        val machine = pendingMachine()
        val oldToken = machine.snapshot.pendingToken
        machine.endSession(generation, "MANUAL_STOP")
        machine.beginManualSession(generation + 1L)

        assertEquals(VehicleAwayAction.None, machine.onTimer(generation, oldToken, 100_000L))
        assertEquals(VehicleAwayPhase.ACTIVE, machine.snapshot.phase)
        assertEquals(generation + 1L, machine.snapshot.generation)
    }

    @Test
    fun duplicateEdgesDoNotCreateDuplicateTimers() {
        val machine = pendingMachine()
        val token = machine.snapshot.pendingToken
        val deadline = machine.snapshot.confirmAtMs

        assertEquals(VehicleAwayAction.None, machine.onAppForeground(generation, false, 4_000L))
        assertEquals(VehicleAwayAction.None, machine.onScreenPower(generation, false, 4_100L))
        assertEquals(VehicleAwayAction.None, machine.onMainDisplayPower(generation, false, 4_200L))
        assertEquals(token, machine.snapshot.pendingToken)
        assertEquals(deadline, machine.snapshot.confirmAtMs)
    }

    @Test
    fun signalArrivalOrderDoesNotChangeTheDecision() {
        val screenFirst = activeMachine()
        screenFirst.onScreenPower(generation, false, 1_000L)
        screenFirst.onMainDisplayPower(generation, false, 2_000L)
        assertTrue(screenFirst.onAppForeground(generation, false, 3_000L) is VehicleAwayAction.Schedule)

        val displayFirst = activeMachine()
        displayFirst.onMainDisplayPower(generation, false, 1_000L)
        displayFirst.onAppForeground(generation, false, 2_000L)
        assertTrue(displayFirst.onScreenPower(generation, false, 3_000L) is VehicleAwayAction.Schedule)

        assertEquals(VehicleAwayPhase.PENDING, screenFirst.snapshot.phase)
        assertEquals(VehicleAwayPhase.PENDING, displayFirst.snapshot.phase)
    }

    private fun activeMachine() = VehicleAwayStateMachine(policy).also {
        it.beginManualSession(generation)
    }

    private fun pendingMachine() = activeMachine().also {
        it.onAppForeground(generation, false, 1_000L)
        it.onScreenPower(generation, screenOn = false, nowMs = 2_000L)
        it.onMainDisplayPower(generation, displayOn = false, nowMs = 3_000L)
    }
}
