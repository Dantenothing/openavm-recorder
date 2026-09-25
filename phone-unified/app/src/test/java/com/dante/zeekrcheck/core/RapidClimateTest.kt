package com.dante.zeekrcheck.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class RapidClimateTest {
    private val now = Instant.parse("2026-09-21T01:00:00Z")
    private val start = now.toEpochMilli()
    private val elapsed = 1_000L
    private fun params(body: String) = (Json.parseToJsonElement(body).at("setting.serviceParameters") as JsonArray)
        .associate { it.at("key").text() to it.at("value").text() }
    private fun snapshot(wheel: Boolean? = false, temp: Double = 4.0, seconds: Long = 0) = ClimateSnapshot(
        acOn = true, cabinTemperature = temp, steeringHeat = wheel, sourceTime = now.plusSeconds(seconds), fetchedAt = now.plusSeconds(seconds))
    private fun session(departure: Long? = null, temp: Double = 4.0): PreparationSession {
        val p = ComfortPreferences(steeringHeat = true)
        val plan = ComfortPolicy.start(p, snapshot(temp = temp), start, elapsed, departure, wheelHeatingVerified = true)
        return PreparationSession("synthetic", start, start + 1_800_000, p, plan.command!!.targets.map { it.channel },
            initialTemperature = temp, thermal = plan.thermal)
    }
    private fun observe(s: PreparationSession, seconds: Long, wheel: Boolean?, temp: Double = 22.0) =
        ComfortPolicy.observe(s, snapshot(wheel, temp, seconds), start + seconds * 1000)
    private fun evaluate(s: PreparationSession, seconds: Long, wheel: Boolean? = false) =
        ComfortPolicy.evaluate(s, snapshot(wheel, seconds = seconds), start + seconds * 1000, elapsed + seconds * 1000, true)

    @Test fun pairedRapidModesHaveExactOfficialWireValuesAndKeepComfortTarget() {
        for ((mode, wire) in listOf(AcMode.LO to "15.5", AcMode.HI to "28.5")) {
            val target = ClimateTarget(ClimateChannel.AC, 22, 5, mode)
            val expected = mapOf("AC" to "true", "AC.temp" to wire, "AC.duration" to "5", "operation" to "4")
            assertEquals(expected, params(target.body()))
            assertEquals(expected, params(VehicleCommand.Comfort(listOf(target)).body()))
            assertEquals(22, target.value)
            assertTrue(target.label.contains(mode.name)); assertFalse(target.label.contains(wire))
            assertFalse(snapshot(temp = wire.toDouble()).confirms(target, now, now))
        }
        assertEquals("22", params(ClimateTarget(ClimateChannel.AC, 22).body())["AC.temp"])
    }
    @Test fun rapidModeCannotLeakToOffSeatsOrLongUnattendedRequests() {
        assertThrows(IllegalArgumentException::class.java) { ClimateTarget(ClimateChannel.AC, 0, 5, AcMode.LO) }
        assertThrows(IllegalArgumentException::class.java) { ClimateTarget(ClimateChannel.FRONT_LEFT, 3, 5, AcMode.HI) }
        assertThrows(IllegalArgumentException::class.java) { ClimateTarget(ClimateChannel.AC, 22, 30, AcMode.HI) }
        for (mode in listOf(AcMode.LO, AcMode.HI)) {
            val off = ClimateTarget(ClimateChannel.AC, 22, 5, mode).copy(value = 0, acMode = AcMode.TARGET)
            assertEquals(mapOf("AC" to "false", "operation" to "4"), params(off.body()))
        }
    }
    @Test fun wheelIsBooleanWithBoundedDurationAndOffHasNoLevelOrTimer() {
        val on = ClimateTarget(ClimateChannel.STEERING, 1)
        assertEquals(mapOf("SW" to "true", "SW.level" to "3", "SW.duration" to "8", "operation" to "4"), params(on.body()))
        assertFalse(on.label.contains("档"))
        assertEquals(mapOf("SW" to "false", "operation" to "4"), params(on.copy(value = 0).body()))
        assertThrows(IllegalArgumentException::class.java) { on.copy(value = 3) }
        assertThrows(IllegalArgumentException::class.java) { on.copy(minutes = 30) }
        assertThrows(IllegalArgumentException::class.java) { ClimateTarget(ClimateChannel.AC, 22, 8) }
    }
    @Test fun wheelEnumsAndSourceAgeMustMatchBeforeConfirming() {
        fun parse(raw: String) = ClimateSnapshot.parse(Probe(Endpoint.STATUS, ProbeOutcome.SUCCESS, now,
            Json.parseToJsonElement("""{"additionalVehicleStatus":{"climateStatus":{"steerWhlHeatingSts":$raw}}}""")))
        assertEquals(true, parse("1").steeringHeat); assertEquals(false, parse("\"2\"").steeringHeat)
        for (raw in listOf("null", "0", "3", "true", "\"unknown\"")) assertNull(parse(raw).steeringHeat)
        val target = ClimateTarget(ClimateChannel.STEERING, 1)
        assertFalse(parse("1").confirms(target, now, now))
        assertFalse(parse("1").copy(sourceTime = now.minusSeconds(1)).confirms(target, now, now))
        assertFalse(parse("1").copy(sourceTime = now.plusSeconds(31)).confirms(target, now, now))
        assertTrue(parse("1").copy(sourceTime = now).confirms(target, now, now))
        assertTrue(parse("2").copy(sourceTime = now).confirms(target.copy(value = 0), now, now))
        assertFalse(parse("2").copy(sourceTime = now).confirms(target, now, now))
    }
    @Test fun rapidModeChangesCoalesceThenStopWaitsForSentRequestWithoutReplay() = runTest {
        val gate = CompletableDeferred<ClimateResult>()
        val sent = mutableListOf<ClimateTarget>()
        val queue = ClimateQueue(this) { t, accepted -> sent += t; accepted(); if (sent.size == 1) gate.await() else ClimateResult.MATCHED }
        val lo = ClimateTarget(ClimateChannel.AC, 22, 5, AcMode.LO)
        val hi = lo.copy(acMode = AcMode.HI)
        queue.submit(lo); queue.submit(hi); advanceTimeBy(601); runCurrent()
        assertEquals(listOf(hi), sent)
        queue.submit(lo); val off = ClimateTarget(ClimateChannel.AC, 0); queue.submit(off)
        assertEquals(listOf(hi), sent)
        gate.complete(ClimateResult.NEEDS_CHECK); advanceUntilIdle()
        assertEquals(listOf(hi, off), sent)
        assertFalse(queue.state.value.halted)
    }
    @Test fun automaticPreparationNeverUsesRapidUntilNativeReversionIsVerified() {
        for (temp in listOf(-5.0, 4.0, 40.0, 60.0)) {
            val plan = ComfortPolicy.start(ComfortPreferences(), snapshot(temp = temp), start, elapsed)
            assertEquals(listOf(ClimateTarget(ClimateChannel.AC, 22, 30)), plan.command!!.targets)
        }
        val wheel = ComfortPolicy.start(ComfortPreferences(steeringHeat = true), snapshot(), start, elapsed)
        assertEquals(listOf(ClimateChannel.AC), wheel.command!!.targets.map { it.channel })
        assertEquals(SurfacePhase.SKIPPED, wheel.thermal.tasks.single().phase)
    }
    @Test fun wheelRespectsPermissionTemperatureExistingStateAndFreshness() {
        val p = ComfortPreferences(steeringHeat = true)
        val enabled = ComfortPolicy.start(p, snapshot(), start, elapsed, wheelHeatingVerified = true)
        assertEquals(ClimateTarget(ClimateChannel.STEERING, 1, 8), enabled.command!!.targets.last())
        for (s in listOf(snapshot(wheel = true), snapshot(wheel = null), snapshot(temp = 20.0), snapshot(seconds = -301))) {
            val plan = ComfortPolicy.start(p, s, start, elapsed, wheelHeatingVerified = true)
            assertFalse(plan.command!!.targets.any { it.channel == ClimateChannel.STEERING })
        }
        assertEquals(listOf(ClimateChannel.AC), ComfortPolicy.start(p.copy(steeringHeat = false), snapshot(), start, elapsed, wheelHeatingVerified = true).command!!.targets.map { it.channel })
        assertEquals(5, ComfortPolicy.start(p, snapshot(temp = 14.0), start, elapsed, wheelHeatingVerified = true).command!!.targets.last().minutes)
    }
    @Test fun coldWheelTaskSurvivesCabinWarmingAndStartsNearDepartureWithinRemainingBudget() {
        var s = session(start + 900_000)
        assertEquals(listOf(ClimateChannel.AC), s.channels)
        assertEquals(start + 420_000, s.thermal!!.tasks.single().notBefore)
        s = observe(s, 60, false)
        assertNull(evaluate(s, 60).command)
        val due = evaluate(observe(s, 420, false), 420)
        assertEquals(listOf(ClimateTarget(ClimateChannel.STEERING, 1, 8)), due.command!!.targets)
        assertEquals(start + 900_000, due.session.thermal!!.tasks.single().leaseEnd)
        val delayed = evaluate(observe(s, 430, false), 430)
        assertEquals(5, delayed.command!!.targets.single().minutes)
        val late = evaluate(observe(s, 610, false), 610)
        assertNull(late.command); assertEquals(SurfacePhase.SKIPPED, late.session.thermal!!.tasks.single().phase)
        assertEquals(s.deadline, due.session.deadline)
    }
    @Test fun stoppingPreparationWaitsForFreshWheelOffAndDoesNotConfuseCabinOffWithSuccess() {
        val s = observe(session(), 10, true).copy(stopRequested = true, stopSentAt = start + 20_000)
        fun stopped(seconds: Long, wheel: Boolean?) = ComfortPolicy.observe(s,
            snapshot(wheel, seconds = seconds).copy(acOn = false), start + seconds * 1000)
        assertFalse(stopped(15, false).finished)
        assertFalse(stopped(30, null).finished); assertFalse(stopped(30, true).finished)
        assertEquals(PreparationPhase.STOPPED, stopped(30, false).phase)
    }
    @Test fun manualWheelOffHandsBackControlWithoutRestartingOrStoppingDriverClimate() {
        val s = observe(observe(session(), 10, true), 20, false)
        assertTrue(s.finished); assertEquals(PreparationPhase.HANDED_OVER, s.phase)
        assertNull(evaluate(s, 21).command); assertFalse(evaluate(s, 21).end)
    }
    @Test fun unknownWheelExecutionSuspendsWithoutRepeatingAndNativeExpiryReleasesOwnership() {
        val unknown = evaluate(observe(session(), 130, null), 130, null)
        assertTrue(unknown.session.thermal!!.suspended); assertNull(unknown.command)
        var running = observe(session(), 10, true)
        running = observe(running, 480, false)
        assertFalse(ClimateChannel.STEERING in running.channels)
        assertEquals(SurfacePhase.LIMITED, running.thermal!!.tasks.single().phase)
        assertEquals(0L, running.thermal.tasks.single().confirmedMs)
        assertNull(evaluate(running, 481, true).command)
        assertEquals(running, AssistantState.parse(AssistantState(activePreparation = running).encode()).activePreparation)
    }
}
