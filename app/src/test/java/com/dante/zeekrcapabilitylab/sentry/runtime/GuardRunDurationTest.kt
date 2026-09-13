package com.dante.zeekrcapabilitylab.sentry.runtime

import com.dante.zeekrcapabilitylab.sentry.*
import org.junit.Assert.*
import org.junit.Test

class GuardRunDurationTest {
    @Test fun finiteRunsExpireByElapsedTimeIncludingSuspendAndNormalRecording() {
        val duration = GuardRunDuration.MINUTES_30
        val start = 700_000L
        assertEquals(1_800_000L, duration.remainingMs(start, start))
        assertEquals(1L, duration.remainingMs(start, start + 1_799_999))
        assertEquals(0L, duration.remainingMs(start, start + 1_800_000))
        assertEquals(0L, duration.remainingMs(start, start + 80_000_000))
        assertEquals(1_800_000L, duration.remainingMs(start, start - 100))
    }

    @Test fun unlimitedDoesNotAcquireAnImplicitTwelveHourDeadline() {
        assertNull(GuardRunDuration.UNLIMITED.durationMs)
        assertNull(GuardRunDuration.UNLIMITED.remainingMs(0, 72L * 60 * 60 * 1000))
        assertEquals(43_200_000L, GuardRunDuration.HOURS_12.durationMs)
        assertEquals(GuardRunDuration.UNLIMITED, GuardRunDuration.fromStored("UNLIMITED"))
        assertEquals(GuardRunDuration.HOURS_12, GuardRunDuration.fromStored("unknown"))
        assertEquals(GuardRunDuration.HOURS_12, GuardRunDuration.fromStored(null))
    }

    @Test fun timerStopClosesBothRecordingModesAndNeverRearmsOnReturn() {
        for (away in listOf(false, true)) {
            val machine = RecorderModeCoordinator(true)
            machine.setPolicy(GuardPolicy.AUTO)
            var ticket = (machine.explicitStart().single() as ModeEffect.Acquire).ticket
            machine.acquired(ticket)
            if (away) {
                machine.presence(machine.permit.generation, VehiclePresencePhase.AWAY_CONFIRMED)
                ticket = (machine.released(ticket).single() as ModeEffect.Acquire).ticket
                machine.acquired(ticket)
            }
            val generation = machine.permit.generation
            assertEquals(listOf(ModeEffect.Release(ticket)), machine.stop(SentryStopReason.RUN_LIMIT))
            assertEquals(RecorderModePhase.STOPPING, machine.phase)
            assertEquals(RunPermitState.DISARMED, machine.permit.state)
            assertTrue(machine.released(ticket).isEmpty())
            assertEquals(RecorderModePhase.STOPPED, machine.phase)
            assertNull(machine.lease.snapshot)
            assertTrue(machine.presence(generation, VehiclePresencePhase.OCCUPIED).isEmpty())
            assertTrue(machine.presence(machine.permit.generation, VehiclePresencePhase.OCCUPIED).isEmpty())
        }
    }

    @Test fun olderRunReportsStillReadWithThePreviousTwelveHourLimit() {
        val state = GuardEventStore.json.decodeFromString<GuardState>("""{"running":false,"phase":"STOPPED"}""")
        assertEquals(GuardRunDuration.HOURS_12, state.runDuration)
        assertFalse(state.running)
    }
}
