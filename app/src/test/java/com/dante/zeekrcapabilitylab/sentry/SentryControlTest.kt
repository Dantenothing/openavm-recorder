package com.dante.zeekrcapabilitylab.sentry

import org.junit.Assert.*
import org.junit.Test

class SentryControlTest {
    private fun active(): RecorderModeCoordinator = RecorderModeCoordinator(true).also {
        it.setPolicy(GuardPolicy.AUTO)
        it.acquired((it.explicitStart().single() as ModeEffect.Acquire).ticket)
    }

    @Test fun featureOffNeverConstructsComponentsOrGrantsPermission() {
        var calls = 0
        assertNull(SentryFeatureGate.createIfEnabled(false, GuardPolicy.AUTO) { calls++; Any() })
        assertNull(SentryFeatureGate.createIfEnabled(true, GuardPolicy.OFF) { calls++; Any() })
        assertEquals(0, calls)
        val machine = RecorderModeCoordinator()
        machine.setPolicy(GuardPolicy.AUTO)
        assertTrue(machine.explicitStart().isEmpty())
        assertEquals(RunPermitState.DISARMED, machine.permit.state)
        assertEquals(GuardPolicy.OFF, GuardPolicy.fromStoredValue("bad"))
    }

    @Test fun autoSettingAndPresenceNeverCreateAuthority() {
        val machine = RecorderModeCoordinator(true)
        machine.setPolicy(GuardPolicy.AUTO)
        assertTrue(machine.presence(0, VehiclePresencePhase.OCCUPIED).isEmpty())
        assertNull(machine.lease.snapshot)
    }

    @Test fun manualFatalAndMasterOffDisarmBeforeTeardownAndRejectAllOldWork() {
        for (reason in listOf(SentryStopReason.MANUAL_STOP, SentryStopReason.FATAL_STOP, SentryStopReason.MASTER_OFF)) {
            val machine = active()
            val generation = machine.permit.generation
            val ticket = machine.lease.snapshot!!.ticket
            val effects = if (reason == SentryStopReason.MASTER_OFF) machine.setPolicy(GuardPolicy.OFF) else machine.stop(reason)
            assertEquals(RunPermitState.DISARMED, machine.permit.state)
            assertTrue(machine.permit.generation > generation)
            assertEquals(listOf(ModeEffect.Release(ticket)), effects)
            assertTrue(machine.presence(generation, VehiclePresencePhase.OCCUPIED).isEmpty())
            assertTrue(machine.fault(ticket, SentryStopReason.CAMERA_FAULT).isEmpty())
            assertEquals(listOf(ModeEffect.CloseStaleResource(ticket)), machine.acquired(ticket))
            assertTrue(machine.released(ticket).isEmpty())
            assertNull(machine.lease.snapshot)
            machine.setPolicy(GuardPolicy.AUTO)
            assertTrue(machine.presence(machine.permit.generation, VehiclePresencePhase.OCCUPIED).isEmpty())
        }
    }

    @Test fun handoffWaitsForExactReleaseAndCoalescesReturnWhileReleasing() {
        val machine = active()
        val first = machine.lease.snapshot!!.ticket
        assertEquals(listOf(ModeEffect.Release(first)), machine.presence(machine.permit.generation, VehiclePresencePhase.AWAY_CONFIRMED))
        assertTrue(machine.presence(machine.permit.generation, VehiclePresencePhase.OCCUPIED).isEmpty())
        assertNull(machine.lease.acquire(machine.stamp, CaptureMode.SENTRY))
        assertTrue(machine.released(first.copy(id = 999)).isEmpty())
        val next = (machine.released(first).single() as ModeEffect.Acquire).ticket
        assertEquals(CaptureMode.NORMAL, next.mode)
        assertNotEquals(first.stamp, next.stamp)
        assertEquals(listOf(ModeEffect.CloseStaleResource(first)), machine.acquired(first))
        machine.acquired(next)
        assertEquals(RecorderModePhase.NORMAL_ACTIVE, machine.phase)
    }

    @Test fun sentryFailureNeverRequestsNormalAndRequiresNewExplicitStart() {
        val machine = active()
        val normal = machine.lease.snapshot!!.ticket
        machine.presence(machine.permit.generation, VehiclePresencePhase.AWAY_CONFIRMED)
        val sentry = (machine.released(normal).single() as ModeEffect.Acquire).ticket
        assertEquals(CaptureMode.SENTRY, sentry.mode)
        assertEquals(listOf(ModeEffect.Release(sentry)), machine.fault(sentry, SentryStopReason.CAMERA_FAULT))
        assertTrue(machine.released(sentry).isEmpty())
        assertNull(machine.desiredMode)
        assertTrue(machine.presence(machine.permit.generation, VehiclePresencePhase.OCCUPIED).isEmpty())
    }

    @Test fun releaseTimeoutCannotFreeLeaseOrAdmitAnotherOwner() {
        val machine = active()
        val ticket = machine.lease.snapshot!!.ticket
        machine.stop()
        machine.releaseTimedOut(ticket)
        assertEquals(RecorderModePhase.FAULT, machine.phase)
        assertTrue(machine.explicitStart().isEmpty())
        assertEquals(ticket, machine.lease.snapshot!!.ticket)
        machine.released(ticket)
        assertEquals(1, machine.explicitStart().size)
    }

    @Test fun duplicateAcquiredCallbackDoesNotCloseCurrentCamera() {
        val machine = active()
        assertTrue(machine.acquired(machine.lease.snapshot!!.ticket).isEmpty())
        assertEquals(RecorderModePhase.NORMAL_ACTIVE, machine.phase)
    }

    @Test fun repeatedHandoffsNeverReuseAuthorityOrLeakOwnership() {
        val machine = active()
        val generation = machine.permit.generation
        repeat(1_000) { index ->
            val old = machine.lease.snapshot!!.ticket
            val presence = if (index % 2 == 0) VehiclePresencePhase.AWAY_CONFIRMED else VehiclePresencePhase.OCCUPIED
            assertEquals(listOf(ModeEffect.Release(old)), machine.presence(generation, presence))
            val next = (machine.released(old).single() as ModeEffect.Acquire).ticket
            machine.acquired(next)
            assertTrue(machine.released(old).isEmpty())
            assertEquals(generation, machine.permit.generation)
        }
        val old = machine.lease.snapshot!!.ticket
        machine.stop()
        machine.released(old)
        assertNull(machine.lease.snapshot)
    }
}
