package com.dante.zeekrcapabilitylab.sentry

import org.junit.Assert.*
import org.junit.Test

class IntegratedPresenceRegressionTest {
    private fun signals(at: Long, off: Boolean = true) = PresenceSignals(
        SignalValue.OFF, if (off) SignalValue.OFF else SignalValue.ON,
        if (off) SignalValue.OFF else SignalValue.ON, at)
    private fun machine() = VehiclePresenceStateMachine(PresencePolicy.integrated()).also {
        it.begin(RunPermit(RunPermitState.ARMED, 1))
    }
    private fun sample(machine: VehiclePresenceStateMachine, at: Long, off: Boolean = true): VehiclePresencePhase {
        val value = signals(at, off)
        machine.observe(1, value, at)
        machine.deadline?.takeIf { it.atMs <= at }?.let { machine.onTimer(it, value, at) }
        return machine.phase
    }

    @Test fun departureStartsHandoffBeforeObservedVehicleSleepWindow() {
        val machine = machine()
        assertEquals(VehiclePresencePhase.OCCUPIED, sample(machine, 0, off = false))
        assertEquals(VehiclePresencePhase.AWAY_PENDING, sample(machine, 1_000))
        // Beta5 still waited for 30 seconds here; the vehicle suspended about eight seconds after OFF.
        assertEquals(VehiclePresencePhase.AWAY_CONFIRMED, sample(machine, 2_000))
        val coordinator = RecorderModeCoordinator(true)
        coordinator.setPolicy(GuardPolicy.AUTO)
        val normal = (coordinator.explicitStart().single() as ModeEffect.Acquire).ticket
        coordinator.acquired(normal)
        assertEquals(listOf(ModeEffect.Release(normal)), coordinator.presence(1, machine.phase))
        assertEquals(CameraLeasePhase.RELEASING, coordinator.lease.snapshot?.phase)
        val sentry = (coordinator.released(normal).single() as ModeEffect.Acquire).ticket
        assertEquals(CaptureMode.SENTRY, sentry.mode)
    }

    @Test fun measuredSleepGapCannotConfirmDepartureOnFirstWakeSample() {
        val machine = machine()
        sample(machine, 0)
        val beforeSleep = machine.deadline!!
        assertEquals(VehiclePresencePhase.AWAY_PENDING, sample(machine, 412_803))
        assertNotEquals(beforeSleep, machine.deadline)
        // The car reports screen ON shortly after wake. Do not stop normal recording in between.
        assertEquals(VehiclePresencePhase.OCCUPIED, sample(machine, 414_246, off = false))
        assertNull(machine.deadline)
    }

    @Test fun sleepGapCannotConfirmReturnWithoutContinuousAwakeSamples() {
        val machine = machine()
        sample(machine, 0)
        for (at in 1_000L..30_000L step 1_000) sample(machine, at)
        assertEquals(VehiclePresencePhase.AWAY_CONFIRMED, machine.phase)
        assertEquals(VehiclePresencePhase.RETURN_PENDING, sample(machine, 31_000, off = false))
        assertEquals(VehiclePresencePhase.RETURN_PENDING, sample(machine, 80_000, off = false))
        for (at in 81_000L..84_000L step 1_000) assertEquals(VehiclePresencePhase.RETURN_PENDING, sample(machine, at, off = false))
        assertEquals(VehiclePresencePhase.OCCUPIED, sample(machine, 85_000, off = false))
    }

    @Test fun backgroundAloneAndMismatchedPowerSignalsCannotArmSentry() {
        val machine = machine()
        for (at in 0L..60_000L step 1_000) {
            machine.observe(1, signals(at).copy(mainDisplay = SignalValue.ON), at)
            assertNull(machine.deadline)
            assertNotEquals(VehiclePresencePhase.AWAY_CONFIRMED, machine.phase)
        }
        machine.end()
        assertEquals(VehiclePresencePhase.UNKNOWN, sample(machine, 90_000))
    }
}
