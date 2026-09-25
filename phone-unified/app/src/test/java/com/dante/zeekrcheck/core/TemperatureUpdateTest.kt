package com.dante.zeekrcheck.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class TemperatureUpdateTest {
    private val base = 1_800_000_000_000L
    private fun status(at: Long, on: Boolean = false, temp: Double? = 30.2, mode: Int = 0, speed: Int = 0) =
        Probe(Endpoint.STATUS, ProbeOutcome.SUCCESS, Instant.ofEpochMilli(at), Json.parseToJsonElement(
            """{"updateTime":$at,"basicVehicleStatus":{"speed":$speed,"usageMode":$mode},
            "additionalVehicleStatus":{"drivingSafetyStatus":{"electricParkBrakeStatus":"1"},
            "climateStatus":{"updateTime":$at,"preClimateActive":$on,"airBlowerActive":$on,"interiorTemp":$temp}}}"""))

    @Test fun explicitTemperatureUpdateStartsOnceThenStopsOnlyAfterNewRunningReport() = runTest {
        var state = TemperatureUpdate("test-car", base, 22)
        val commands = mutableListOf<ClimateTarget>()
        TemperatureUpdateFlow(state, { state }, { state = it }, read = { status(base) },
            send = { target, dispatched, observed ->
                commands += target; dispatched()
                delay(1_000)
                observed(status(base + testScheduler.currentTime, target.value > 0))
                CommandResult.ACCEPTED
            }, now = { base + testScheduler.currentTime }).run()
        assertEquals(listOf(22, 0), commands.map { it.value })
        assertTrue(commands.all { it.channel == ClimateChannel.AC && it.minutes == 5 })
        assertEquals(TemperaturePhase.DONE, state.phase)
        assertEquals(30.2, state.temperature!!, 0.001)
        assertFalse(state.ownsAc)
    }

    @Test fun alreadyRunningClimateIsObservedAndNeverTurnedOff() = runTest {
        var state = TemperatureUpdate("car", base, 22)
        TemperatureUpdateFlow(state, { state }, { state = it }, read = { status(base + testScheduler.currentTime, true) },
            send = { _, _, _ -> fail("Borrowed HVAC must not be controlled"); CommandResult.REJECTED },
            now = { base + testScheduler.currentTime }).run()
        assertEquals(TemperaturePhase.DONE, state.phase)
        assertFalse(state.ownsAc)
        assertTrue(state.message.contains("保留原空调"))
    }

    @Test fun staleParkedDataAfterOnlineCheckCannotAuthorizeClimateStart() = runTest {
        var state = TemperatureUpdate("car", base, 22); var evidenceCalls = 0
        TemperatureUpdateFlow(state, { state }, { state = it }, read = { status(base - 600_000) },
            refreshEvidence = { evidenceCalls++; it },
            send = { _, _, _ -> fail("Stale state"); CommandResult.REJECTED }, now = { base }).run()
        assertEquals(1, evidenceCalls)
        assertEquals(TemperaturePhase.FAILED, state.phase)
        assertEquals(0L, state.startSent)
        assertFalse(state.ownsAc)
    }

    @Test fun freshDrivingModePreventsStart() = runTest {
        var state = TemperatureUpdate("car", base, 22)
        TemperatureUpdateFlow(state, { state }, { state = it }, read = { status(base, mode = 13) },
            send = { _, _, _ -> fail("Driving"); CommandResult.REJECTED }, now = { base }).run()
        assertEquals(TemperaturePhase.HANDED_OVER, state.phase)
        assertFalse(state.ownsAc)
    }

    @Test fun stopReceiptWithoutOffReportLeavesSeparateSampleAndCleanupResults() = runTest {
        var state = TemperatureUpdate("car", base, 22); val writes = mutableListOf<Int>()
        TemperatureUpdateFlow(state, { state }, { state = it }, read = { status(base + testScheduler.currentTime, writes.isNotEmpty()) },
            send = { target, dispatched, observed ->
                writes += target.value; dispatched(); delay(1_000)
                observed(status(base + testScheduler.currentTime, true)); CommandResult.ACCEPTED
            }, now = { base + testScheduler.currentTime }).run()
        assertEquals(listOf(22, 0), writes)
        assertEquals(TemperaturePhase.NEEDS_STOP, state.phase)
        assertTrue(state.ownsAc)
        assertNotNull(state.temperature)
        assertEquals("车温已更新 · 临时空调停止待确认", state.message)
    }

    @Test fun queuedPreparationWaitsUntilThisTaskHasConfirmedItsOwnStop() = runTest {
        var state = TemperatureUpdate("car", base, 22); val writes = mutableListOf<Int>()
        val work = async {
            TemperatureUpdateFlow(state, { state }, { state = it }, read = { status(base + testScheduler.currentTime) },
                send = { target, dispatched, observed ->
                    writes += target.value; dispatched(); delay(5_000)
                    observed(status(base + testScheduler.currentTime, target.value > 0)); CommandResult.ACCEPTED
                }, now = { base + testScheduler.currentTime }).run()
        }
        runCurrent(); advanceTimeBy(1_000)
        state = state.copy(cancelRequested = true, prepareAfter = 0)
        work.await()
        assertEquals(listOf(22, 0), writes)
        assertEquals(0, state.prepareAfter)
        assertFalse(state.ownsAc)
        assertFalse(state.active)
    }

    @Test fun ambiguousStartIsReadBackThenStoppedWithoutRepeatingStart() = runTest {
        var state = TemperatureUpdate("car", base, 22); val writes = mutableListOf<Int>()
        TemperatureUpdateFlow(state, { state }, { state = it }, read = { status(base + testScheduler.currentTime, writes == listOf(22)) },
            send = { target, dispatched, observed ->
                writes += target.value; dispatched(); delay(1_000)
                if (target.value > 0) throw java.io.IOException("synthetic lost reply")
                observed(status(base + testScheduler.currentTime)); CommandResult.ACCEPTED
            }, now = { base + testScheduler.currentTime }).run()
        assertEquals(listOf(22, 0), writes)
        assertFalse(state.ownsAc)
        assertNotNull(state.temperature)
    }

    @Test fun lossOfConnectivityLeavesDurableStopWarningWithoutRepeatedWrites() = runTest {
        var state = TemperatureUpdate("car", base, 22); var starts = 0
        TemperatureUpdateFlow(state, { state }, { state = it }, read = {
            if (starts > 0) throw java.io.IOException("offline"); status(base)
        }, send = { _, dispatched, _ ->
            starts++; dispatched(); throw java.io.IOException("lost start reply")
        }, now = { base }).run()
        assertEquals(1, starts)
        assertTrue(state.needsStop)
        val restored = TemperatureUpdate.parse(state.json())!!
        assertTrue(restored.needsStop)
        assertEquals(state, restored)
    }

    @Test fun cancellingInflightStartStillAttemptsOwnedCleanupOnce() = runTest {
        var state = TemperatureUpdate("car", base, 22); val writes = mutableListOf<Int>()
        val work = launch {
            TemperatureUpdateFlow(state, { state }, { state = it }, read = { status(base + testScheduler.currentTime, writes == listOf(22)) },
                send = { target, dispatched, observed ->
                    writes += target.value; dispatched()
                    if (target.value > 0) awaitCancellation()
                    delay(1_000); observed(status(base + testScheduler.currentTime)); CommandResult.ACCEPTED
                }, now = { base + testScheduler.currentTime }).run()
        }
        runCurrent(); advanceTimeBy(1_000); work.cancelAndJoin()
        assertEquals(listOf(22, 0), writes)
        assertFalse(state.ownsAc)
    }

    @Test fun driverTakeoverForbidsCleanupWrite() = runTest {
        var state = TemperatureUpdate("car", base, 22); val writes = mutableListOf<Int>()
        TemperatureUpdateFlow(state, { state }, { state = it }, read = { status(base) },
            send = { target, dispatched, observed ->
                writes += target.value; dispatched(); delay(1_000)
                observed(status(base + testScheduler.currentTime, true, mode = 13)); CommandResult.ACCEPTED
            }, now = { base + testScheduler.currentTime }).run()
        assertEquals(listOf(22), writes)
        assertEquals(TemperaturePhase.HANDED_OVER, state.phase)
        assertFalse(state.ownsAc)
    }

    @Test fun missingTemperatureStillStopsObservedTemporaryClimateAtDeadline() = runTest {
        var state = TemperatureUpdate("car", base, 22); val writes = mutableListOf<Int>()
        TemperatureUpdateFlow(state, { state }, { state = it }, read = { status(base + testScheduler.currentTime, writes == listOf(22), null) },
            send = { target, dispatched, observed ->
                writes += target.value; dispatched(); delay(1_000)
                observed(status(base + testScheduler.currentTime, target.value > 0, null)); CommandResult.ACCEPTED
            }, now = { base + testScheduler.currentTime }).run()
        assertEquals(listOf(22, 0), writes)
        assertNull(state.temperature)
        assertFalse(state.ownsAc)
        assertTrue(testScheduler.currentTime < 60_000)
    }

    @Test fun explicitRecoveryOnlySendsOffAndRestartNeverReplaysStart() = runTest {
        var state = TemperatureUpdate("car", base - 10_000, 22, phase = TemperaturePhase.STARTING,
            ownsAc = true, startSent = base - 10_000).interrupted(base)
        val writes = mutableListOf<Int>()
        assertTrue(state.needsStop)
        TemperatureUpdateFlow(state, { state }, { state = it }, read = { status(base + testScheduler.currentTime, true) },
            send = { target, dispatched, observed ->
                writes += target.value; dispatched(); delay(1_000)
                observed(status(base + testScheduler.currentTime)); CommandResult.ACCEPTED
            }, now = { base + testScheduler.currentTime }).run(stopOnly = true)
        assertEquals(listOf(0), writes)
        assertFalse(state.ownsAc)
        val saved = AssistantState(temperatureUpdate = state)
        assertEquals(state, AssistantState.parse(saved.encode()).temperatureUpdate)
    }
    @Test fun changedAccountOrVehiclePreventsAllCleanupCommands() = runTest {
        var state = TemperatureUpdate("car", base, 22); var bound = true
        val writes = mutableListOf<Int>()
        TemperatureUpdateFlow(state, { state }, { state = it }, read = { status(base) },
            send = { target, dispatched, _ ->
                writes += target.value; dispatched(); bound = false; CommandResult.UNKNOWN
            }, current = { bound }, now = { base }).run()
        assertEquals(listOf(22), writes)
        assertTrue(state.ownsAc)
    }

    @Test fun freshOffReadbackCanResolveStopWarningButOldOffAndReceiptCannot() {
        val pending = TemperatureUpdate("car", base - 10_000, 22, phase = TemperaturePhase.NEEDS_STOP,
            ownsAc = true, startSent = base - 10_000, stopSent = base - 2_000, temperature = 30.2)
        assertEquals(pending, pending.observeFinished(status(base - 3_000), base))
        assertEquals(pending, pending.observeFinished(status(base, true), base))
        val resolved = pending.observeFinished(status(base), base)
        assertFalse(resolved.ownsAc)
        assertEquals(TemperaturePhase.DONE, resolved.phase)
    }

    @Test fun restartRetiresWithoutReplayingAndKeepsUnknownStopVisible() {
        val interrupted = TemperatureUpdate("car", base, 22, phase = TemperaturePhase.STARTING,
            startSent = base, ownsAc = true, prepareAfter = 0).interrupted(base + 1_000)
        assertTrue(interrupted.needsStop)
        assertNull(interrupted.prepareAfter)
        assertTrue(interrupted.visible(base + 900_000))
        assertTrue(interrupted.expiredOwnership(base + 900_000))
        assertEquals(interrupted, interrupted.observeFinished(status(base + 1_000), base + 1_000))
    }

    @Test fun missingTemperatureDuringTaskKeepsLastValueAndSourceWithoutPretendingItIsFresh() {
        val previous = OverviewReading("19.9 °C", base - 1_000, base - 1_000)
        val old = VehicleOverview(readings = mapOf("cabin_temperature" to previous))
        val probe = status(base, on = true, temp = null)
        val next = old.updated(Report(false, probe.fetchedAt, probe.fetchedAt, "", emptyList(), listOf(probe)), retainTemperature = true)
        val reading = next.readings["cabin_temperature"]!!
        assertEquals(previous.value, reading.value)
        assertEquals(previous.source, reading.source)
        assertFalse(reading.fresh(Instant.ofEpochMilli(base)))
        assertEquals(true, next.acOn)
    }
}
