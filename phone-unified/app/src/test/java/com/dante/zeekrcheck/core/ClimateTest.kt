package com.dante.zeekrcheck.core

import kotlinx.serialization.json.*
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ClimateTest {
    private val now = Instant.parse("2026-09-19T10:00:00Z")
    private val target = ClimateTarget(ClimateChannel.FRONT_LEFT, 3)
    private fun request(body: String = target.body()) = Request.Builder().url(ClimateRequestPolicy.URL)
        .header("X-VIN", "synthetic-encrypted-vin").header("authorization", "synthetic-bearer")
        .post(body.toRequestBody("application/json".toMediaType())).build()
    private fun snapshot(fields: String) = ClimateSnapshot.parse(Probe(Endpoint.STATUS, ProbeOutcome.SUCCESS, now,
        Json.parseToJsonElement("""{"additionalVehicleStatus":{"climateStatus":{$fields}}}""")))

    @Test fun climateAllowlistRejectsOtherActionsBodyChangesAndOrigins() {
        val original = request()
        assertTrue(ClimateRequestPolicy.allowed(original, target))
        assertFalse(RequestPolicy.allowed(original.method, original.url))
        assertFalse(ClimateRequestPolicy.allowed(request(target.body().replace("SV.11", "SH.11")), target))
        assertFalse(ClimateRequestPolicy.allowed(request(target.body().replace("ZAF", "RDU")), target))
        assertFalse(ClimateRequestPolicy.allowed(original.newBuilder().url(ClimateRequestPolicy.URL + "?extra=1").build(), target))
        assertFalse(ClimateRequestPolicy.allowed(original.newBuilder().url("https://evil.invalid/").build(), target))
        assertFalse(ClimateRequestPolicy.allowed(original.newBuilder().removeHeader("authorization").build(), target))
        assertFalse(ClimateRequestPolicy.allowed(original.newBuilder().get().build(), target))
        assertThrows(IllegalArgumentException::class.java) { ClimateTarget(ClimateChannel.FRONT_LEFT, 4) }
        assertThrows(IllegalArgumentException::class.java) { ClimateTarget(ClimateChannel.AC, 35) }
        assertThrows(IllegalArgumentException::class.java) { ClimateTarget(ClimateChannel.AC, 22, 999) }
    }
    @Test fun closingOneSeatDoesNotSetOtherSeatOrAC() {
        val body = Json.parseToJsonElement(ClimateTarget(ClimateChannel.FRONT_RIGHT, 0).body())
        val params = body.at("setting.serviceParameters") as JsonArray
        assertEquals(listOf("SV.19", "operation"), params.map { it.at("key").text() })
        assertEquals("false", params[0].at("value").text())
    }
    @Test fun missingOrContradictorySeatFieldsAreUnknownNotOff() {
        assertNull(snapshot("").frontLeft)
        assertNull(snapshot("\"drvVentSts\":1").frontLeft)
        assertNull(snapshot("\"drvVentSts\":2,\"drvVentDetail\":3").frontLeft)
        assertNull(snapshot("\"drvVentSts\":1,\"drvVentDetail\":7").frontLeft)
        assertEquals(0, snapshot("\"drvVentSts\":2,\"drvVentDetail\":7").frontLeft)
        assertEquals(3, snapshot("\"drvVentSts\":1,\"drvVentDetail\":3").frontLeft)
    }
    @Test fun cachedUnknownAndFutureTimestampsCannotConfirmEvenMatchingLevels() {
        val base = ClimateSnapshot(frontLeft = 3, fetchedAt = now)
        assertFalse(base.confirms(target, now, now))
        assertFalse(base.copy(sourceTime = now.minusSeconds(1)).confirms(target, now, now))
        assertFalse(base.copy(sourceTime = now.plusSeconds(90)).confirms(target, now, now))
        assertTrue(base.copy(sourceTime = now).confirms(target, now, now))
        assertFalse(base.copy(frontLeft = 2, sourceTime = now).confirms(target, now, now))
    }
    @Test fun cabinTemperatureAndPowerCannotConfirmTheACSetpoint() {
        val read = ClimateSnapshot(acOn = true, cabinTemperature = 22.0, sourceTime = now, fetchedAt = now)
        assertFalse(read.confirms(ClimateTarget(ClimateChannel.AC, 22), now, now))
        assertTrue(read.copy(acOn = false).confirms(ClimateTarget(ClimateChannel.AC, 0), now, now))
    }
    @Test fun wheelOnUsesOfficialLevelThreeInIndividualAndCombinedRequests() {
        val wheel = ClimateTarget(ClimateChannel.STEERING, 1, 5)
        for (raw in listOf(wheel.body(), VehicleCommand.Comfort(listOf(wheel)).body())) {
            val params = (Json.parseToJsonElement(raw).at("setting.serviceParameters") as JsonArray)
                .associate { it.at("key").text() to it.at("value").text() }
            assertEquals("3", params["SW.level"])
            assertEquals("true", params["SW"])
        }
    }
    @Test fun numericBlowerStatesAreNotLost() {
        assertEquals(true, snapshot("\"airBlowerActive\":1").blowerActive)
        assertEquals(false, snapshot("\"airBlowerActive\":\"0\"").blowerActive)
        assertNull(snapshot("\"airBlowerActive\":7").blowerActive)
    }
}
