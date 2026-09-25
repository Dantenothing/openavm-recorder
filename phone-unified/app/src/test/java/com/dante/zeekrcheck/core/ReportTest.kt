package com.dante.zeekrcheck.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ReportTest {
    private val now = Instant.parse("2026-09-19T06:00:00Z")
    @Test fun freshHttpFetchDoesNotMakeYesterdayTemperatureFresh() {
        val report = Demo.report(now)
        val temperature = report.capabilities.first { it.id == "cabin_temperature" }
        assertEquals(ReadEvidence.FOUND, temperature.read)
        assertEquals(AgeLabel.STALE, temperature.age(now))
        assertEquals(now.minusSeconds(86_400), temperature.sourceTime)
        assertEquals(now, report.probes.first().fetchedAt)
    }
    @Test fun missingSourceTimeIsNotReplacedByFetchTime() {
        val sentry = Demo.report(now).capabilities.first { it.id == "sentry" }
        assertEquals("开启", sentry.value)
        assertEquals(AgeLabel.UNKNOWN, sentry.age(now))
        assertNull(sentry.sourceTime)
    }
    @Test fun futureTimeIsInvalidAndAgeChangesWithoutAnotherFetch() {
        val battery = Demo.report(now).capabilities.first { it.id == "battery" }
        assertEquals(AgeLabel.RECENT, battery.age(now))
        assertEquals(AgeLabel.STALE, battery.age(now.plusSeconds(400)))
        assertEquals(AgeLabel.INVALID, battery.copy(sourceTime = now.plusSeconds(500)).age(now))
    }
    @Test fun zeroTemperatureBatteryAndNonzeroEquatorCoordinatesAreValid() {
        val data = Json.parseToJsonElement("""{"additionalVehicleStatus":{"electricVehicleStatus":{"chargeLevel":0},"climateStatus":{"interiorTemp":0}},"basicVehicleStatus":{"position":{"latitude":0,"longitude":120}}}""")
        val rows = Capabilities.parse(listOf(Probe(Endpoint.STATUS, ProbeOutcome.SUCCESS, now, data)))
        assertEquals("0 %", rows.first { it.id == "battery" }.value)
        assertEquals("0 °C", rows.first { it.id == "cabin_temperature" }.value)
        assertEquals(ReadEvidence.FOUND, rows.first { it.id == "vehicle_location" }.read)
    }
    @Test fun zeroLocationSentinelIsNotAnActualCarPosition() {
        assertEquals(ReadEvidence.MISSING, Demo.report(now).capabilities.first { it.id == "vehicle_location" }.read)
    }
    @Test fun exportedControlCountIncludesBodyAndComfortCommands() {
        val report = Demo.report(now).copy(demo = false)
        val exported = Json.parseToJsonElement(report.export(now, buildJsonObject { put("requestAttempts", 2); put("vehicleRequestAttempts", 3) }))
        assertEquals("5", exported.at("controlRequestsSent").text())
        assertEquals("LIVE_WITH_VEHICLE_CONTROLS", exported.at("mode").text())
        assertEquals(com.dante.zeekrcheck.BuildConfig.VERSION_NAME, exported.at("toolVersion").text())
    }
    @Test fun missingUnknownAndOutOfRangeValuesDoNotBecomeFalseOrZero() {
        val data = Json.parseToJsonElement("""{"additionalVehicleStatus":{"electricVehicleStatus":{"chargeLevel":101},"climateStatus":{"interiorTemp":"NaN"},"drivingSafetyStatus":{"centralLockingStatus":"7"}}}""")
        val rows = Capabilities.parse(listOf(Probe(Endpoint.STATUS, ProbeOutcome.SUCCESS, now, data),
            Probe(Endpoint.SENTRY, ProbeOutcome.SUCCESS, now, Json.parseToJsonElement("""{"vstdModeState":"7"}"""))))
        listOf("battery", "cabin_temperature", "lock", "sentry").forEach { id -> assertEquals(ReadEvidence.MISSING, rows.first { it.id == id }.read) }
    }
    @Test fun emptyPlanProvesReadEndpointOnlyNotWriteOrUnsupported() {
        val report = Demo.report(now)
        val row = report.capabilities.first { it.id == "travel_plan" }
        assertEquals(ReadEvidence.EMPTY, row.read)
        val exported = Json.parseToJsonElement(report.export(now))
        val plan = exported.at("capabilities")!!.jsonArray.first { it.at("id").text() == "travel_plan" }
        assertEquals("UNTESTED", plan.at("write").text())
        assertEquals("UNTESTED", plan.at("background").text())
    }
    @Test fun scheduledTimeIsNotUsedAsAnObservationTimestamp() {
        val probe = Probe(Endpoint.TRAVEL_PLAN, ProbeOutcome.SUCCESS, now, Json.parseToJsonElement("""{"scheduledTime":${now.toEpochMilli()}}"""))
        assertNull(Capabilities.parse(listOf(probe)).first { it.id == "travel_plan" }.sourceTime)
        assertNull(Capabilities.sourceTime(JsonPrimitive(now.epochSecond)))
    }
    @Test fun exportDropsRawIdentifiersCoordinatesSecretsAndUnknownStrings() {
        val secret = "DO_NOT_EXPORT_THIS_PRIVATE_VALUE"
        val data = Json.parseToJsonElement("""{"vin":"L6T00000000000001","token":"$secret","message":"$secret","additionalVehicleStatus":{"climateStatus":{"interiorTemp":"$secret"}},"basicVehicleStatus":{"position":{"latitude":-34.9285,"longitude":138.6007}}}""")
        val report = Report(false, now, now, "车辆 VIN L6T00000000000001", emptyList(), listOf(Probe(Endpoint.STATUS, ProbeOutcome.SUCCESS, now, data)))
        val text = report.export(now)
        listOf(secret, "L6T00000000000001", "-34.9285", "138.6007").forEach { assertFalse(it, text.contains(it)) }
        assertTrue(text.contains("UNTESTED"))
    }
    @Test fun sharedMetadataDoesNotAuthorizeCommands() {
        val vehicles = Vehicle.parseList(Json.parseToJsonElement("""[{"vin":"L6T00000000000001","role":"owner","permissions":"all","secret":"private"}]"""))
        assertEquals(listOf("role", "permissions"), vehicles.single().roleFieldNames)
        assertFalse(vehicles.single().toString().contains("L6T"))
        assertTrue(Demo.report(now).controls.all { it.thisApp.contains("尚未验证") })
    }
    @Test fun demoCannotExportAsARealCarReport() {
        assertTrue(Demo.report(now).export(now).contains("DEMO_SYNTHETIC_NOT_A_REAL_CAR_TEST"))
    }
    @Test fun skippedEndpointsDoNotClaimAFetchOrACloudPermissionRejection() {
        val report = Report(false, now, now, "vehicle", emptyList(), listOf(
            Probe(Endpoint.SENTRY, ProbeOutcome.AUTH_REQUIRED, now, attempted = false)))
        val json = Json.parseToJsonElement(report.export(now))
        val endpoint = json.at("endpoints")!!.jsonArray.single()
        assertEquals(JsonPrimitive(false), endpoint.at("attempted"))
        assertEquals(JsonNull, endpoint.at("fetchedAt"))
        assertTrue(report.capabilities.first { it.id == "sentry" }.detail.startsWith("未执行："))
    }
}
