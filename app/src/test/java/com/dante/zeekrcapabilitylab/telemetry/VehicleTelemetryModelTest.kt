package com.dante.zeekrcapabilitylab.telemetry

import org.junit.Assert.*
import org.junit.Test

class VehicleTelemetryModelTest {
    private fun sample(at: Long, speed: Double? = 0.0) = VehicleTelemetrySample(at, 1_790_000_000_000 + at,
        mapOf("speed" to if (speed == null) VehicleTelemetryValue(at, status = "NO_VALUE") else VehicleTelemetryValue(at, number = speed)))

    @Test fun zeroAndOffRemainDifferentFromMissing() {
        val value = sample(1_000).copy(values = mapOf(
            "speed" to VehicleTelemetryValue(1_000, number = 0.0),
            "turn_left" to VehicleTelemetryValue(1_000, number = 0.0),
            "turn_right" to VehicleTelemetryValue(1_000, number = 1.0),
            "brake_pedal" to VehicleTelemetryValue(1_000, status = "UNAVAILABLE")))
        assertEquals(0.0, value.speedKmh!!, 0.0)
        assertEquals(false, value.turnLeft)
        assertEquals(true, value.turnRight)
        assertNull(value.brakePressed)
        assertNull(value.gear)
    }
    @Test fun freshBatchCannotMakeAnEarlierGetterFresh() {
        val value = sample(3_000).copy(values = mapOf(
            "speed" to VehicleTelemetryValue(500, number = 40.0),
            "gear" to VehicleTelemetryValue(2_990, text = "D")))
        val at = VehicleTelemetryPlayback.sampleAt(listOf(value), 3_100)!!
        assertNull(at.speedKmh)
        assertEquals("STALE", at.values.getValue("speed").status)
        assertEquals("D", at.gear)
    }
    @Test fun playbackUsesLastEarlierObservationWithoutInterpolation() {
        val values = listOf(sample(1_000, 10.0), sample(2_000, 40.0))
        assertEquals(10.0, VehicleTelemetryPlayback.sampleAt(values, 1_500)!!.speedKmh!!, 0.0)
        assertNull(VehicleTelemetryPlayback.sampleAt(values, 999))
        assertNull(VehicleTelemetryPlayback.sampleAt(values, null))
        assertNull(VehicleTelemetryPlayback.sampleAt(values, 4_001))
        assertEquals(40.0, VehicleTelemetryPlayback.sampleAt(values, 4_000)!!.speedKmh!!, 0.0)
    }
    @Test fun invalidObservationAndStopMarkerDoNotResurrectEarlierValue() {
        val values = listOf(sample(1_000, 50.0), sample(1_250, null), sample(1_500).copy(values = emptyMap(), breakBefore = true))
        assertNull(VehicleTelemetryPlayback.sampleAt(values, 1_250)!!.speedKmh)
        assertNull(VehicleTelemetryPlayback.sampleAt(values, 1_300))
        assertTrue(VehicleTelemetryPlayback.sampleAt(values, 1_600)!!.values.isEmpty())
    }
    @Test fun forwardSeekAfterLongGapStaysUnknownUntilAnotherObservation() {
        val values = listOf(sample(1_000, 50.0), sample(10_000, 20.0).copy(breakBefore = true))
        assertNull(VehicleTelemetryPlayback.sampleAt(values, 9_999))
        assertEquals(20.0, VehicleTelemetryPlayback.sampleAt(values, 10_000)!!.speedKmh!!, 0.0)
    }
    @Test fun knownUpcomingBreakWithholdsTheWholeUncertainIntervalButKeepsExactEndpoints() {
        val values = listOf(sample(1_000, 10.0), sample(1_500, 30.0).copy(breakBefore = true))
        assertEquals(10.0, VehicleTelemetryPlayback.sampleAt(values, 1_000)!!.speedKmh!!, 0.0)
        assertNull(VehicleTelemetryPlayback.sampleAt(values, 1_001))
        assertNull(VehicleTelemetryPlayback.sampleAt(values, 1_400))
        assertEquals(30.0, VehicleTelemetryPlayback.sampleAt(values, 1_500)!!.speedKmh!!, 0.0)
    }
    @Test fun invalidValuesAreRejectedRatherThanCoerced() {
        for (number in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, 361.0)) {
            assertFalse(VehicleTelemetryPolicy.validStored(sample(1_000, number)))
            assertEquals("INVALID_VALUE", VehicleTelemetryPolicy.observed("speed", 1_000, number, null, true, "OK").status)
        }
        assertFalse(VehicleTelemetryPolicy.validStored(sample(1_000).copy(values = mapOf("gear" to VehicleTelemetryValue(1_000, text = "DRIVE")))))
        assertFalse(VehicleTelemetryPolicy.validStored(sample(1_000).copy(values = mapOf("turn_left" to VehicleTelemetryValue(1_000, number = 2.0)))))
    }
    @Test fun noRawIdsExceptionsOrUnrecognizedFieldsAreStored() {
        val invalid = VehicleTelemetryPolicy.observed("gear", 1_000, 2097700.0, "P", false, "READ_FAILED:SecretException")
        assertNull(invalid.text); assertNull(invalid.number); assertEquals("READ_FAILED", invalid.status)
        val sdkMissing = VehicleTelemetryPolicy.observed("speed", 1_000, null, null, false, "SDK_UNAVAILABLE")
        assertEquals("UNAVAILABLE", sdkMissing.status)
        assertFalse(VehicleTelemetryPolicy.validStored(sample(1_000).copy(values = mapOf("vin" to VehicleTelemetryValue(1_000, text = "private")))))
    }
    @Test fun futureGetterAndInvalidClockAreRejected() {
        assertFalse(VehicleTelemetryPolicy.validStored(sample(1_000).copy(values = mapOf("speed" to VehicleTelemetryValue(1_001, number = 2.0)))))
        assertFalse(VehicleTelemetryPolicy.validStored(sample(-1)))
        assertFalse(VehicleTelemetryPolicy.validStored(sample(1_000).copy(epochMs = 0)))
    }
    @Test fun enumAndBinaryControlsAreIndependent() {
        val value = sample(1_000).copy(values = mapOf(
            "indicator" to VehicleTelemetryValue(1_000, text = "left"),
            "turn_left" to VehicleTelemetryValue(1_000, number = 0.0),
            "stop_lamp" to VehicleTelemetryValue(1_000, number = 1.0),
            "brake_pedal" to VehicleTelemetryValue(1_000, number = 0.0)))
        assertEquals("left", value.indicator)
        assertEquals(false, value.turnLeft)
        assertEquals(false, value.brakePressed)
        assertEquals(true, value.brakeLamp)
    }
}
