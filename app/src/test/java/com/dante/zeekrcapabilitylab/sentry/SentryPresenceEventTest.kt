package com.dante.zeekrcapabilitylab.sentry

import org.junit.Assert.*
import org.junit.Test

class SentryPresenceEventTest {
    private fun off(at: Long) = PresenceSignals(SignalValue.OFF, SignalValue.OFF, SignalValue.OFF, at)
    private fun on(at: Long) = PresenceSignals(SignalValue.ON, SignalValue.ON, SignalValue.ON, at)
    private fun machine() = VehiclePresenceStateMachine().also { it.begin(RunPermit(RunPermitState.ARMED, 7)) }

    @Test fun awayNeedsContinuousEvidenceAndTimerNeedsFreshSnapshot() {
        val machine = machine()
        machine.observe(7, off(0), 0)
        val timer = machine.deadline!!
        assertEquals(VehiclePresencePhase.AWAY_PENDING, machine.onTimer(timer, off(29_999), 29_999))
        assertEquals(VehiclePresencePhase.UNKNOWN, machine.onTimer(timer, off(0), 30_000))
        assertNull(machine.deadline)
        machine.observe(7, off(31_000), 31_000)
        assertEquals(VehiclePresencePhase.AWAY_CONFIRMED, machine.onTimer(machine.deadline!!, off(61_000), 61_000))
    }

    @Test fun transientWakeAndUnknownSignalsCannotReturnToNormal() {
        val machine = machine()
        machine.observe(7, off(0), 0)
        machine.onTimer(machine.deadline!!, off(30_000), 30_000)
        machine.observe(7, on(31_000), 31_000)
        val old = machine.deadline!!
        machine.observe(7, off(32_000), 32_000)
        machine.onTimer(old, on(36_000), 36_000)
        assertEquals(VehiclePresencePhase.AWAY_CONFIRMED, machine.phase)
        machine.observe(7, on(37_000).copy(mainDisplay = SignalValue.UNKNOWN), 37_000)
        assertNull(machine.deadline)
        machine.observe(7, on(38_000), 38_000)
        assertEquals(VehiclePresencePhase.OCCUPIED, machine.onTimer(machine.deadline!!, on(43_000), 43_000))
    }

    @Test fun staleRunAndOldSnapshotTimerCannotConfirm() {
        val machine = machine()
        machine.observe(6, off(0), 0)
        assertNull(machine.deadline)
        machine.observe(7, off(0), 0)
        val timer = machine.deadline!!
        machine.observe(7, off(29_000), 29_000)
        assertNotEquals(VehiclePresencePhase.AWAY_CONFIRMED, machine.onTimer(timer, off(28_000), 30_000))
        machine.end()
        machine.onTimer(timer, off(50_000), 50_000)
        assertEquals(VehiclePresencePhase.UNKNOWN, machine.phase)
    }

    @Test fun eventMergeEscalationAndCommitAreScopedToExactEvent() {
        val stamp = ModeStamp(1, 2)
        val machine = SentryEventStateMachine()
        machine.arm(stamp)
        machine.watch(stamp)
        assertTrue(machine.trigger(stamp, SentryTriggerType.SUSPECTED_IMPACT, 1_000_000))
        assertEquals(SentrySeverity.SOFT, machine.event!!.severity)
        val id = machine.event!!.id
        machine.riskCleared(stamp, 2_000_000)
        assertEquals(SentryEventPhase.HOLD, machine.phase)
        machine.trigger(stamp, SentryTriggerType.TRUSTED_PHYSICAL, 3_000_000)
        assertEquals(id, machine.event!!.id)
        assertEquals(SentrySeverity.HARD, machine.event!!.severity)
        machine.riskCleared(stamp, 4_000_000)
        machine.tick(stamp, 123_000_000)
        assertEquals(SentryEventPhase.FINALIZING, machine.phase)
        assertFalse(machine.trigger(stamp, SentryTriggerType.MANUAL, 124_000_000))
        assertFalse(machine.finalized(stamp, id + 1, SentryPersistenceState.COMPLETE))
        assertTrue(machine.finalized(stamp, id, SentryPersistenceState.PARTIAL))
        assertEquals(SentryPersistenceState.PARTIAL, machine.lastPersistenceResult)
    }

    @Test fun eventCapAndDisarmRejectStaleWork() {
        val machine = SentryEventStateMachine()
        val stamp = ModeStamp(1, 1)
        machine.arm(stamp)
        machine.trigger(stamp, SentryTriggerType.MANUAL, 0)
        assertFalse(machine.trigger(stamp.copy(transition = 0), SentryTriggerType.TRUSTED_PHYSICAL, 10))
        machine.tick(stamp, 300_000_000)
        assertTrue(machine.event!!.continuationRequired)
        assertEquals(SentryEventPhase.FINALIZING, machine.phase)
        machine.disarm()
        assertFalse(machine.trigger(stamp, SentryTriggerType.MANUAL, 400_000_000))
    }

    @Test fun diagnosticsHaveBoundedRetention() {
        val history = SentryDiagnosticHistory(3)
        repeat(100) { history.record(SentryHealthSnapshot(sampledAtMonotonicMs = it.toLong(), runGeneration = 1, transitionGeneration = 2)) }
        assertEquals(listOf(97L, 98L, 99L), history.snapshot().map { it.sampledAtMonotonicMs })
    }
}
