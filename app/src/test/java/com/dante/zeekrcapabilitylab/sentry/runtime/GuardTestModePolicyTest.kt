package com.dante.zeekrcapabilitylab.sentry.runtime

import com.dante.zeekrcapabilitylab.sentry.*
import org.junit.Assert.*
import org.junit.Test

class GuardTestModePolicyTest {
    @Test fun returningFromCarTestCannotMaskTheLaterConfirmedDeparture() {
        val mode = GuardTestModePolicy()
        val control = RecorderModeCoordinator(true)
        control.setPolicy(GuardPolicy.AUTO)
        var owned = (control.explicitStart().single() as ModeEffect.Acquire).ticket
        control.acquired(owned)
        fun sample(actual: VehiclePresencePhase, foreground: Boolean): List<ModeEffect> =
            control.presence(control.permit.generation, mode.effective(actual, foreground))
        mode.select(CaptureMode.SENTRY)
        assertEquals(listOf(ModeEffect.Release(owned)), sample(VehiclePresencePhase.OCCUPIED, true))
        owned = (control.released(owned).single() as ModeEffect.Acquire).ticket
        control.acquired(owned)
        mode.select(CaptureMode.NORMAL)
        assertEquals(listOf(ModeEffect.Release(owned)), sample(VehiclePresencePhase.OCCUPIED, true))
        owned = (control.released(owned).single() as ModeEffect.Acquire).ticket
        control.acquired(owned)
        // Beta7 remained NORMAL here at 21:38:14 despite AWAY_CONFIRMED.
        assertEquals(listOf(ModeEffect.Release(owned)), sample(VehiclePresencePhase.AWAY_CONFIRMED, false))
        val sentry = (control.released(owned).single() as ModeEffect.Acquire).ticket
        assertEquals(CaptureMode.SENTRY, sentry.mode)
    }

    @Test fun leavingTheAppEndsAnyUnfinishedCarTest() {
        val mode = GuardTestModePolicy()
        mode.select(CaptureMode.SENTRY)
        assertEquals(VehiclePresencePhase.AWAY_CONFIRMED, mode.effective(VehiclePresencePhase.OCCUPIED, true))
        assertEquals(VehiclePresencePhase.OCCUPIED, mode.effective(VehiclePresencePhase.OCCUPIED, false))
        assertNull(mode.override)
        assertEquals(VehiclePresencePhase.OCCUPIED, mode.effective(VehiclePresencePhase.OCCUPIED, true))
    }

    @Test fun normalCommandEndsTheTestWithoutHoldingPresenceOccupied() {
        val mode = GuardTestModePolicy()
        mode.select(CaptureMode.SENTRY); mode.select(CaptureMode.NORMAL)
        assertNull(mode.override)
        assertEquals(VehiclePresencePhase.UNKNOWN, mode.effective(VehiclePresencePhase.UNKNOWN, true))
    }

    @Test fun clearedTestCannotRestartADisarmedRun() {
        val control = RecorderModeCoordinator(true)
        control.setPolicy(GuardPolicy.AUTO)
        val owned = (control.explicitStart().single() as ModeEffect.Acquire).ticket
        control.acquired(owned)
        val mode = GuardTestModePolicy(); mode.select(CaptureMode.SENTRY)
        mode.clear(); control.stop(); control.released(owned)
        assertTrue(control.presence(control.permit.generation,
            mode.effective(VehiclePresencePhase.AWAY_CONFIRMED, false)).isEmpty())
        assertNull(control.lease.snapshot)
    }
}
