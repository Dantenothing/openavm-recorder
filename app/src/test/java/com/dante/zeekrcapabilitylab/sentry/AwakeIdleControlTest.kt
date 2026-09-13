package com.dante.zeekrcapabilitylab.sentry

import org.junit.Assert.*
import org.junit.Test

class AwakeIdleControlTest {
    private fun active() = RecorderModeCoordinator(true).also {
        it.setPolicy(GuardPolicy.AUTO)
        it.acquired((it.explicitStart(ParkingBehavior.AWAKE_IDLE).single() as ModeEffect.Acquire).ticket)
    }
    @Test fun idleWaitsForCloseAndKeepsAuthorityWithoutCamera() {
        val machine = active()
        val ticket = machine.lease.snapshot!!.ticket
        val generation = machine.permit.generation
        assertEquals(listOf(ModeEffect.Release(ticket)), machine.presence(generation, VehiclePresencePhase.AWAY_CONFIRMED))
        assertEquals(RecorderModePhase.TRANSITION_TO_AWAKE_IDLE, machine.phase)
        assertTrue(machine.released(ticket.copy(id = 999)).isEmpty())
        assertEquals(ticket, machine.lease.snapshot!!.ticket)
        assertTrue(machine.released(ticket).isEmpty())
        assertNull(machine.lease.snapshot)
        assertNull(machine.desiredMode)
        assertEquals(RecorderModePhase.AWAKE_IDLE, machine.phase)
        assertTrue(machine.permit.accepts(generation))
        assertTrue(machine.presence(generation, VehiclePresencePhase.AWAY_CONFIRMED).isEmpty())
        val resumed = (machine.presence(generation, VehiclePresencePhase.OCCUPIED).single() as ModeEffect.Acquire).ticket
        assertEquals(CaptureMode.NORMAL, resumed.mode)
        machine.acquired(resumed)
        assertEquals(RecorderModePhase.NORMAL_ACTIVE, machine.phase)
    }
    @Test fun returnDuringCloseUsesLatestTargetAndCannotOpenEarly() {
        val machine = active()
        val ticket = machine.lease.snapshot!!.ticket
        machine.presence(machine.permit.generation, VehiclePresencePhase.AWAY_CONFIRMED)
        assertTrue(machine.presence(machine.permit.generation, VehiclePresencePhase.OCCUPIED).isEmpty())
        assertEquals(ticket, machine.lease.snapshot!!.ticket)
        val resumed = (machine.released(ticket).single() as ModeEffect.Acquire).ticket
        assertEquals(CaptureMode.NORMAL, resumed.mode)
        assertNotEquals(ticket.stamp, resumed.stamp)
    }
    @Test fun stopDuringCloseOrIdleRejectsAllReturnAndLateCallbacks() {
        for (idle in listOf(false, true)) {
            val machine = active()
            val generation = machine.permit.generation
            val ticket = machine.lease.snapshot!!.ticket
            machine.presence(generation, VehiclePresencePhase.AWAY_CONFIRMED)
            if (idle) machine.released(ticket)
            machine.stop()
            assertFalse(machine.permit.accepts(generation))
            assertTrue(machine.released(ticket).isEmpty())
            assertEquals(RecorderModePhase.STOPPED, machine.phase)
            assertTrue(machine.presence(machine.permit.generation, VehiclePresencePhase.OCCUPIED).isEmpty())
            assertEquals(listOf(ModeEffect.CloseStaleResource(ticket)), machine.acquired(ticket))
            assertNull(machine.lease.snapshot)
        }
    }
    @Test fun unknownPresenceAndTimeoutNeverOpenCamera() {
        val machine = active()
        val ticket = machine.lease.snapshot!!.ticket
        machine.presence(machine.permit.generation, VehiclePresencePhase.AWAY_CONFIRMED)
        machine.releaseTimedOut(ticket)
        assertEquals(RecorderModePhase.FAULT, machine.phase)
        machine.released(ticket)
        assertTrue(machine.presence(machine.permit.generation, VehiclePresencePhase.OCCUPIED).isEmpty())
        assertNull(machine.lease.snapshot)
    }
    @Test fun backgroundReturnNeedsBothPowerSignalsStableAndFresh() {
        val machine = VehiclePresenceStateMachine(PresencePolicy(returnRequiresForeground = false))
        machine.begin(RunPermit(RunPermitState.ARMED, 7))
        fun off(at: Long) = PresenceSignals(SignalValue.OFF, SignalValue.OFF, SignalValue.OFF, at)
        fun on(at: Long) = off(at).copy(screen = SignalValue.ON, mainDisplay = SignalValue.ON)
        machine.observe(7, off(0), 0)
        machine.onTimer(machine.deadline!!, off(30_000), 30_000)
        machine.observe(7, on(31_000), 31_000)
        val interrupted = machine.deadline!!
        machine.observe(7, on(32_000).copy(mainDisplay = SignalValue.UNKNOWN), 32_000)
        assertNull(machine.deadline)
        assertEquals(VehiclePresencePhase.AWAY_CONFIRMED, machine.onTimer(interrupted, on(36_000), 36_000))
        machine.observe(7, on(37_000), 37_000)
        assertEquals(VehiclePresencePhase.AWAY_CONFIRMED, machine.onTimer(machine.deadline!!, on(37_000), 42_000))
        machine.observe(7, on(43_000), 43_000)
        assertEquals(VehiclePresencePhase.RETURN_PENDING, machine.onTimer(machine.deadline!!, on(47_999), 47_999))
        assertEquals(VehiclePresencePhase.OCCUPIED, machine.onTimer(machine.deadline!!, on(48_000), 48_000))
        machine.end()
        assertEquals(VehiclePresencePhase.UNKNOWN, machine.observe(7, on(49_000), 49_000))
    }
}
