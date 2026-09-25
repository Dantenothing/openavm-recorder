package com.dante.zeekrcheck.core

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class VehicleOverviewTest {
    private val now = Instant.parse("2026-09-19T09:00:00Z")
    private fun report(status: String, outcome: ProbeOutcome = ProbeOutcome.SUCCESS) = Report(false, now, now, "never serialized", emptyList(),
        listOf(Probe(Endpoint.STATUS, outcome, now, Json.parseToJsonElement(status))))
    private val recent = """{"additionalVehicleStatus":{"climateStatus":{"interiorTemp":34,"updateTime":${now.minusSeconds(50).toEpochMilli()},"preClimateActive":true},"electricVehicleStatus":{"chargeLevel":68,"distanceToEmptyOnBatteryOnly":412}}}"""
    @Test fun freshMeasuredTemperatureStaysSeparateFromSavedTargetAndUnknownMode() {
        val state = VehicleOverview(target = 22).updated(report(recent))
        assertEquals("34°C", state.cabin(now)); assertEquals(22, state.target)
        assertEquals("空调已开启", state.climateLabel(now)); assertEquals("车内偏热", state.thermalLabel(now))
        assertNull(state.readings["battery"]?.source)
    }
    @Test fun aSuccessfulFetchCannotMakeOldTemperatureFresh() {
        val state = VehicleOverview().updated(report(recent.replace(now.minusSeconds(50).toEpochMilli().toString(), now.minusSeconds(900).toEpochMilli().toString())))
        assertEquals(now.toEpochMilli(), state.refreshedAt)
        assertEquals("34°C", state.cabin(now)); assertEquals("上次测量 · 非实时", state.thermalLabel(now))
        assertFalse(state.readings.getValue("cabin_temperature").fresh(now))
        assertEquals("运行状态待核实", state.climateLabel(now))
    }
    @Test fun missingOrFutureSourceTimeNeverClaimsCurrentTemperature() {
        val missing = VehicleOverview().updated(report("""{"additionalVehicleStatus":{"climateStatus":{"interiorTemp":20}}}"""))
        assertEquals("20°C", missing.cabin(now))
        assertEquals("来源时间未知", missing.readings["cabin_temperature"]?.timeLabel(now))
        assertFalse(OverviewReading("20 °C", now.plusSeconds(600).toEpochMilli(), now.toEpochMilli()).fresh(now))
    }
    @Test fun failedReadsKeepOldDataAndTimestampButStopCallingItCurrent() {
        val initial = VehicleOverview().updated(report(recent))
        val failed = initial.updated(report("{}", ProbeOutcome.TIMEOUT))
        assertEquals(initial.readings["cabin_temperature"]?.source, failed.readings["cabin_temperature"]?.source)
        assertEquals("34°C", failed.cabin(now)); assertNotNull(failed.message)
    }
    @Test fun aLaterFailedRequestCannotAdvanceLastSuccessfulQueryTime() {
        val initial = VehicleOverview().updated(report(recent))
        val later = now.plusSeconds(900)
        val failed = initial.updated(Report(false, later, later, "", emptyList(), listOf(Probe(Endpoint.STATUS, ProbeOutcome.NETWORK, later))))
        assertEquals(initial.refreshedAt, failed.refreshedAt)
    }
    @Test fun successfulMissingFieldsClearPriorKnownValues() {
        val state = VehicleOverview().updated(report(recent)).updated(report("{}"))
        assertNull(state.readings["cabin_temperature"]); assertNull(state.acOn)
    }
    @Test(expected = IllegalArgumentException::class) fun demoCannotOverwriteRealWidget() {
        VehicleOverview().updated(Demo.report(now))
    }
    @Test fun cacheRoundTripHasNoRawPayloadOrVehicleIdentity() {
        val vehicle = Vehicle("L6T79E2C0PP000001", emptyList())
        val state = VehicleOverview(vehicleKey = VehicleOverview.key(vehicle)).updated(report(recent))
        val encoded = state.encode()
        assertFalse(encoded.contains(vehicle.vin)); assertFalse(encoded.contains("additionalVehicleStatus"))
        assertEquals(state, VehicleOverview.decode(encoded))
    }
    @Test fun unexpectedCacheFieldsAreNotSavedOrLoaded() {
        val state = VehicleOverview(readings = mapOf("raw_credentials" to OverviewReading("secret", null, 0)))
        assertFalse(state.encode().contains("secret"))
        assertTrue(VehicleOverview.decode(state.encode()).readings.isEmpty())
    }
    @Test fun interruptedRefreshDoesNotRemainBusyForever() {
        assertTrue(VehicleOverview(refreshingAt = now.minusSeconds(10).toEpochMilli()).refreshing(now))
        assertFalse(VehicleOverview(refreshingAt = now.minusSeconds(101).toEpochMilli()).refreshing(now))
    }
    @Test fun cancelledEndpointDoesNotChangeOtherEndpointEvidence() {
        val initial = VehicleOverview().updated(report(recent))
        val next = initial.updated(Report(false, now, now, "", emptyList(), listOf(Probe(Endpoint.SENTRY, ProbeOutcome.NETWORK, now))))
        assertEquals("34°C", next.cabin(now)); assertEquals(true, next.acOn)
    }
    @Test fun airflowAndThermalTintRequireFreshReportedEvidence() {
        val initial = VehicleOverview().updated(report(recent.replace("\"preClimateActive\":true", "\"preClimateActive\":true,\"airBlowerActive\":true")))
        assertEquals("hot", initial.thermalState(now)); assertEquals("空调送风中", initial.climateLabel(now))
        assertEquals("neutral", initial.thermalState(now.plusSeconds(600))); assertEquals("运行状态待核实", initial.climateLabel(now.plusSeconds(600)))
        assertEquals(true, VehicleOverview.decode(initial.encode()).blowerActive)
        assertEquals("cold", initial.copy(readings = initial.readings + ("cabin_temperature" to OverviewReading("6 °C", now.toEpochMilli(), now.toEpochMilli()))).thermalState(now))
    }
}
