package com.dante.zeekrcheck.core

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import kotlinx.serialization.json.*

class WidgetFeedbackRegressionTest {
    private val now = Instant.parse("2026-09-21T08:40:00Z")

    @Test fun successfulCloudQueryDoesNotClaimThatParkedCarProducedNewData() {
        val old = OverviewReading("21.9 °C", now.minusSeconds(1260).toEpochMilli(), now.toEpochMilli())
        val overview = VehicleOverview(readings = mapOf("cabin_temperature" to old), refreshedAt = now.toEpochMilli())
        assertEquals("已查询云端 · 车温暂无新上报", overview.refreshResult(old, now))
        assertFalse(old.fresh(now))
        assertEquals(now.minusSeconds(1260).toEpochMilli(), old.source)
    }

    private fun status(brake: String = "1", speed: String = "0", mode: String = "2", safetyTime: String = "") = Probe(
        Endpoint.STATUS, ProbeOutcome.SUCCESS, now, Json.parseToJsonElement("""{
          "updateTime":${now.minusSeconds(600).toEpochMilli()},
          "basicVehicleStatus":{"speed":"$speed","usageMode":"$mode"},
          "additionalVehicleStatus":{"drivingSafetyStatus":{"electricParkBrakeStatus":"$brake"$safetyTime}}
        }"""))
    private fun overview(p: Probe) = VehicleOverview().updated(Report(false, now, now, "", emptyList(), listOf(p)))

    @Test fun motionDisplayUsesVehicleModeAndSourceTimeIndependentlyOfCabinTemperature() {
        val parked = overview(status())
        assertTrue(parked.motionLabel(now).startsWith("Parked · "))
        assertEquals(now.minusSeconds(600).toEpochMilli(), parked.motion?.source)
        assertTrue(parked.motion!!.aggregate)
        assertEquals(parked, VehicleOverview.decode(parked.encode()))
        assertTrue(overview(status("0", "0", "13")).motionLabel(now).startsWith("Driving · "))
        assertTrue(overview(status("0", "40", "33")).motionLabel(now).startsWith("Driving · "))
    }
    @Test fun conflictingMotionNeverDefaultsToParkedAndMissingFieldsDoNotRetainOldState() {
        for (p in listOf(status("1", "20"), status("1", "0", "13"), status("9")))
            assertEquals("—", overview(p).motion?.state)
        val old = overview(status())
        val missing = Probe(Endpoint.STATUS, ProbeOutcome.SUCCESS, now, Json.parseToJsonElement("{}"))
        assertEquals("—", old.updated(Report(false, now, now, "", emptyList(), listOf(missing))).motion?.state)
        val failure = Probe(Endpoint.STATUS, ProbeOutcome.NETWORK, now.plusSeconds(900))
        assertEquals(old.motion, old.updated(Report(false, now, now, "", emptyList(), listOf(failure))).motion)
    }
    @Test fun motionRootFallbackDoesNotHideAnExplicitBadOrOlderFieldTimestamp() {
        assertNull(overview(status(safetyTime = """, "updateTime":"bad"""")).motion?.source)
        val earlier = now.minusSeconds(900).toEpochMilli()
        assertEquals(earlier, overview(status(safetyTime = """, "updateTime":$earlier""")).motion?.source)
    }
    @Test fun unknownHornDoesNotGateOtherOperationsButAnUnknownLockStillDoes() {
        val horn = VehicleCommand.Body(BodyAction.HORN)
        assertFalse(OperationFeedback.requiresAcknowledgement(horn, CommandResult.UNKNOWN))
        assertTrue(OperationFeedback.requiresAcknowledgement(VehicleCommand.Body(BodyAction.LOCK), CommandResult.UNKNOWN))
        assertTrue(OperationFeedback.message(horn, CommandResult.UNKNOWN).contains("未确认"))
        assertFalse(OperationFeedback.message(horn, CommandResult.ACCEPTED).contains("成功"))
        assertFalse(OperationFeedback.message(horn, CommandResult.ACCEPTED).contains("待核实"))
    }
    @Test fun interruptedHornRecoversWithoutReplayOrManualAcknowledgement() {
        val pending = AssistantState(operationPending = true, pendingBodyAction = BodyAction.HORN)
        val restored = AssistantState.parse(pending.encode())
        val recovered = OperationFeedback.recover(restored)
        assertFalse(recovered.operationPending); assertNull(recovered.pendingBodyAction)
        assertTrue(recovered.operationMessage.contains("不会自动重发"))
        assertTrue(recovered.history.isEmpty())
        val lock = pending.copy(pendingBodyAction = BodyAction.LOCK)
        assertEquals(lock, OperationFeedback.recover(lock))
    }
    @Test fun legacyRecoveryNeverUsesStaleHornHistoryToClearAnUnrelatedUnknownCommand() {
        val legacy = AssistantState(operationPending = true, operationMessage = "找车鸣笛一次 · ${CommandResult.UNKNOWN.label}")
        assertFalse(OperationFeedback.recover(legacy).operationPending)
        val unknown = legacy.copy(operationMessage = "上次操作中断，车辆结果待核实；不会重新发送",
            history = listOf(OperationEntry(1, BodyAction.HORN.title, CommandResult.UNKNOWN.label)))
        assertEquals(unknown, OperationFeedback.recover(unknown))
    }
    private fun sentry(on: Boolean, at: Long) = Probe(Endpoint.SENTRY, ProbeOutcome.SUCCESS, Instant.ofEpochMilli(at),
        buildJsonObject { put("vstdModeState", if (on) "1" else "0") })

    @Test fun ownHomeOffDoesNotCreateAManualOrObservedPause() {
        val time = now.toEpochMilli()
        val on = ParkingGuard("car", phase = ParkingPhase.PARKED).observe(sentry(true, time), time)
        val off = on.observe(sentry(false, time + 20_000), time + 20_000, automaticOffAt = time + 10_000)
        assertFalse(off.paused); assertFalse(off.manualPaused); assertFalse(off.observedOn)
        assertFalse(off.observe(sentry(false, time + 40_000), time + 40_000).paused)
    }
    @Test fun manualOffAlwaysSurvivesHomeAcknowledgementAndExternalOffRemainsProtected() {
        val time = now.toEpochMilli()
        val on = ParkingGuard("car", phase = ParkingPhase.PARKED).observe(sentry(true, time), time)
        val manual = on.pause(time + 12_000).observe(sentry(false, time + 20_000), time + 20_000, time + 10_000)
        assertTrue(manual.manualPaused)
        val external = on.observe(sentry(false, time + 20_000), time + 20_000)
        assertTrue(external.paused); assertFalse(external.manualPaused)
        assertEquals("哨兵已关闭 · 本次不自动重开", external.decision(null, null, null, 100, false, time + 20_000).reason)
        assertFalse(external.afterAutomaticOff(time + 10_000).paused)
        assertTrue(external.afterAutomaticOff(time + 30_000).paused)
        assertTrue(manual.afterAutomaticOff(time + 10_000).paused)
    }
    @Test fun vehicleAddressCacheUsesTheGeocodedAnchorAndRejectsLateMovedCarResults() {
        val a = CarLocation(-34.9, 138.6, now.toEpochMilli(), verified = true)
        val label = VehicleAddress.label("12", "Cross Road", "Glenunga")!!
        val resolved = VehicleAddress.resolved(a, a, label)!!
        assertEquals(resolved, CarLocation.parse(resolved.json()))
        val near = a.copy(latitude = a.latitude + 0.0002)
        val near2 = a.copy(latitude = a.latitude + 0.0004)
        val kept = VehicleAddress.updated(near, resolved)
        assertEquals(label, kept.address)
        assertTrue(VehicleAddress.updated(near2, kept).address.isBlank())
        assertEquals(near2, VehicleAddress.resolved(near2, a, label))
        assertEquals(a.source, resolved.source)
        assertEquals("家", HomeZone.label(resolved, a, 100, now.toEpochMilli()))
        assertTrue(HomeZone.label(resolved, a.copy(latitude = -34.8), 100, now.toEpochMilli()).contains("Cross Road"))
    }
    @Test fun missingRoadDoesNotInventAnExactStreetAddress() {
        assertNull(VehicleAddress.label(null, null, null))
        assertEquals("Glenunga 附近", VehicleAddress.label("12", null, "Glenunga"))
        val point = CarLocation(-34.9, 138.6, null)
        assertTrue(HomeZone.label(point, null, 100, now.toEpochMilli()).contains("道路名称待解析"))
    }
}
