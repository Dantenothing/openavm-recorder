package com.dante.zeekrcapabilitylab.enhancement.field

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class FieldEvidenceTest {
    private fun evidence(coords: Boolean = false) = FieldEvidence("sample", 10_000, 1_790_000_000_000, 1800, coords, true)
    private fun gps() = FieldGpsSample("gps", 10_000, 1_790_000_000_000, -34.9, 138.6, 5.0, 10.0, 0.5, false, false)
    private fun reading(key: String, raw: Double, valid: Boolean = true) =
        VehicleSignalReading(key, raw, if (valid) raw else null, null, valid, if (valid) "OK" else "UNKNOWN_ENUM", true)
    private fun signal(report: JsonObject, key: String) = report.getValue("signals").jsonArray
        .map { it.jsonObject }.first { it["key"]!!.jsonPrimitive.content == key }

    @Test fun zeroIsValidButMissingAndStaleSpeedAreNotZero() {
        val e = evidence()
        assertNull(e.speed(10_000))
        e.signal(reading("speed", 0.0), 10_000, 1)
        assertEquals(0.0, e.speed(12_000)!!, 0.0)
        assertNull(e.speed(12_001))
        e.signal(VehicleSignalDecode.unavailable(VehicleSignalCatalog.signals.first { it.key == "speed" }, "SDK_UNAVAILABLE"), 12_010, 1)
        assertNull(e.speed(12_010))
    }

    @Test fun coordinatesNeverEnterDefaultJsonIncludingTimeline() {
        val e = evidence(); e.gps(gps(), 10_000); e.tick(10_000, false)
        val json = e.json(10_000).toString()
        assertFalse(json.contains("latitude")); assertFalse(json.contains("longitude")); assertFalse(json.contains("138.6"))
        assertTrue(json.contains("accuracyM")); assertEquals(36.0, e.gpsSpeed(10_000)!!, 0.0)
    }

    @Test fun optInCoordinatesStillRequireValidLiveFix() {
        val e = evidence(true); e.gps(gps().copy(cached = true), 10_000)
        assertFalse(e.json(10_000).toString().contains("latitude"))
        e.gps(gps(), 10_000)
        assertTrue(e.json(10_000).toString().contains("latitude"))
        assertFalse(e.json(15_001).toString().contains("latitude"))
    }

    @Test fun networkPositionWithoutSpeedDoesNotEraseFreshGpsSpeed() {
        val e = evidence(); e.gps(gps(), 10_000)
        e.gps(gps().copy(provider = "network", elapsedMs = 11_000, speedMps = null), 11_000)
        assertEquals(36.0, e.gpsSpeed(11_000)!!, 0.0)
        assertNull(e.gpsSpeed(15_001))
    }

    @Test fun providerFailureIsVisibleEvenWhenFixExists() {
        val e = evidence(); e.gps(gps(), 10_000); e.gpsStatus = "NO_PERMISSION"
        assertEquals("NO_PERMISSION", e.ui(10_500, false).gpsStatus)
        assertNull(e.gpsSpeed(10_500))
        assertEquals("NO_PERMISSION", e.json(10_500)["gps"]!!.jsonObject["latest"]!!.jsonObject["providerStatus"]!!.jsonPrimitive.content)
    }

    @Test fun noFixHasExplicitTimeout() {
        val e = evidence(); e.gpsStatus = "SUBSCRIBED:gps"
        assertEquals("GPS_TIMEOUT", e.ui(40_001, false).gpsStatus)
    }

    @Test fun cachedOrInvalidCallbacksDoNotSuppressNoLiveFixTimeout() {
        for (sample in listOf(gps().copy(cached = true), gps().copy(mock = true), gps().copy(accuracyM = 500.0))) {
            val e = evidence(); e.gps(sample, 10_000)
            assertEquals("GPS_TIMEOUT", e.ui(40_001, false).gpsStatus)
            assertEquals(0, e.json(40_001)["gps"]!!.jsonObject["validPositions"]!!.jsonPrimitive.int)
        }
    }

    @Test fun changingUnknownEnumsRetainRawEvidenceWithoutClaimingDecodedValidity() {
        val e = evidence(); e.signal(reading("gear", 122.0, false), 10_000, 1)
        e.signal(reading("gear", 123.0, false), 10_250, 1)
        val s = signal(e.json(10_250), "gear")
        assertEquals(1, s["changes"]!!.jsonPrimitive.int)
        assertEquals(0, s["decodedChanges"]!!.jsonPrimitive.int)
        assertEquals(0, s["validReadings"]!!.jsonPrimitive.int)
        assertEquals(2, s["getterReturned"]!!.jsonPrimitive.int)
    }

    @Test fun fastAnalogChangesCannotCrowdOutLaterDiscreteTransitions() {
        val e = evidence()
        repeat(1000) { e.signal(reading("speed", it.toDouble()), 10_000L + it * 250, 1) }
        e.signal(reading("gear", 1.0), 270_000, 1); e.signal(reading("gear", 2.0), 280_000, 1)
        val json = e.json(280_000)
        val changes = json["changes"]!!.jsonArray
        assertEquals(11, changes.size)
        assertEquals("gear", changes.last().jsonObject["key"]!!.jsonPrimitive.content)
        assertEquals(989, signal(json, "speed")["omittedChanges"]!!.jsonPrimitive.int)
    }

    @Test fun completedRunRejectsLateSdkGpsMarkersAndTicks() {
        val e = evidence(); e.signal(reading("speed", 10.0), 10_000, 1); e.finish(11_000, "USER_STOP")
        val before = e.json(11_000)
        e.signal(reading("speed", 20.0), 11_000, 1); e.gps(gps(), 11_000)
        e.mark("late", 11_000); e.tick(11_000, true); e.finish(12_000, "TIME_LIMIT")
        assertEquals(before, e.json(11_000)); assertNull(e.ui(11_000, false).vehicleSpeedKmh)
    }

    @Test fun fullHalfHourTimelineAndMarkerMemoryAreBounded() {
        val e = evidence()
        repeat(20_000) { e.tick(10_000L + it * 250, false); e.mark("m", 10_000L + it * 250) }
        val json = e.json(5_000_000)
        assertTrue(json["timeline"]!!.jsonArray.size <= 361)
        assertEquals(24, json["markers"]!!.jsonArray.size)
    }

    @Test fun reportBudgetKeepsParseableSummaryAndCameraEvidence() {
        val huge = buildJsonObject { put("signals", JsonArray(emptyList())); put("timeline", "x".repeat(400_000)); put("changes", "y".repeat(400_000)) }
        val camera = buildJsonObject { put("run", "camera1"); put("cleanup", "CONFIRMED") }
        val result = FieldReportBudget.combine(buildJsonObject { put("appVersion", "test") }, listOf(huge), listOf(camera))
        assertTrue(result.toByteArray().size <= FieldReportBudget.MAX_BYTES)
        val parsed = Json.parseToJsonElement(result).jsonObject
        assertEquals(2, parsed["trimLevel"]!!.jsonPrimitive.int)
        assertEquals(1, parsed["cameraTests"]!!.jsonArray.size)
        assertTrue(parsed["vehicleTests"]!!.jsonArray.first().jsonObject.containsKey("signals"))
    }

    @Test fun exportBudgetCanDecimateTimelineWithoutDiscardingFirstAndLast() {
        val points = JsonArray((0..360).map { buildJsonObject { put("ms", it * 5000); put("details", "x".repeat(1000)) } })
        val report = buildJsonObject { put("timeline", points); put("signals", JsonArray(emptyList())) }
        val result = Json.parseToJsonElement(FieldReportBudget.combine(JsonObject(emptyMap()), listOf(report), emptyList())).jsonObject
        assertEquals(1, result["trimLevel"]!!.jsonPrimitive.int)
        val saved = result["vehicleTests"]!!.jsonArray.first().jsonObject["timeline"]!!.jsonArray
        assertEquals(61, saved.size); assertEquals(points.first(), saved.first()); assertEquals(points.last(), saved.last())
    }
}
