package io.github.dantenothing.drivemap

import org.junit.Assert.*
import org.junit.Test

class DriveHudModelTest {
    private fun number(at: Long, value: Double, status: String = "OK") = DriveHudObservation(at, number = value, status = status)

    @Test fun vehicleZeroIsRealAndWinsOverGpsFallback() {
        val frame = DriveHudModel.frame(1_000, mapOf("speed" to number(1_000, 0.0)), 10.0, 1_000)
        assertEquals(0.0, frame.speedKmh!!, 0.0)
        assertEquals(DriveHudSpeedSource.VEHICLE, frame.speedSource)
        assertEquals(36.0, frame.gpsSpeedKmh!!, 0.00001)
    }

    @Test fun unknownAndStaleDoNotBecomeZeroOrOff() {
        val frame = DriveHudModel.frame(3_001, mapOf("speed" to number(1_000, 10.0),
            "gear" to DriveHudObservation(1_000, text = "D"), "brake_pedal" to number(1_000, 0.0), "turn_left" to number(1_000, 0.0)))
        assertNull(frame.speedKmh); assertNull(frame.gear); assertNull(frame.brakePressed); assertNull(frame.turn)
        assertEquals(DriveHudSpeedSource.UNAVAILABLE, frame.speedSource)
    }

    @Test fun futureMissingAndInvalidClockCannotExposeValues() {
        val values = mapOf("speed" to number(1_001, 12.0))
        for (at in listOf(null, -1L, 1_000L)) assertNull(DriveHudModel.frame(at, values, 10.0, 1_001).speedKmh)
    }

    @Test fun gpsFallbackIsLabelledAndBounded() {
        val frame = DriveHudModel.frame(2_000, gpsSpeedMps = 0.0, gpsElapsedMs = 1_000)
        assertEquals(DriveHudSpeedSource.GPS, frame.speedSource); assertEquals(0.0, frame.speedKmh!!, 0.0)
        assertNull(DriveHudModel.frame(3_001, gpsSpeedMps = 10.0, gpsElapsedMs = 1_000).speedKmh)
        assertNull(DriveHudModel.frame(1_000, gpsSpeedMps = Double.NaN, gpsElapsedMs = 1_000).speedKmh)
    }

    @Test fun pedalDepthIsRawAndCannotBecomeBinaryBrakeOrPercentage() {
        val values = mapOf("brake_depth" to number(1_000, 42.0, "PROVISIONAL_RAW"),
            "throttle_depth" to number(1_000, 0.0, "PROVISIONAL_RAW"))
        val frame = DriveHudModel.frame(1_000, values)
        assertNull(frame.brakePressed)
        assertEquals(42.0, frame.brakeDepth.raw!!, 0.0)
        assertEquals("PROVISIONAL_RAW", frame.brakeDepth.status)
        assertEquals(0.0, frame.acceleratorDepth.raw!!, 0.0)
        assertNull(DriveHudModel.frame(3_001, values).brakeDepth.raw)
        assertNull(DriveHudModel.frame(1_000, mapOf("brake_depth" to number(1_000, 42.0))).brakeDepth.raw)
    }

    @Test fun indicatorNeedsTwoKnownLampStatesToInferOff() {
        assertNull(DriveHudModel.frame(1_000, mapOf("turn_left" to number(1_000, 0.0))).turn)
        val values = mapOf("turn_left" to number(1_000, 1.0), "turn_right" to number(1_000, 1.0))
        assertEquals("hazard", DriveHudModel.frame(1_000, values).turn)
        assertEquals("left", DriveHudModel.frame(1_000, values + ("indicator" to DriveHudObservation(1_000, text = "left"))).turn)
    }

    @Test fun pedalEvidenceHasNoRawValuesOrArbitraryErrors() {
        val report = DriveHudModel.pedalEvidence(listOf(mapOf("brake_depth" to number(1_000, 123.456, "PROVISIONAL_RAW")),
            mapOf("throttle_depth" to DriveHudObservation(1_000, text = "private-path", status = "private-error"))))
        assertEquals("1", report["brake_depth_rawAvailable"])
        assertFalse(report.toString().contains("123.456")); assertFalse(report.toString().contains("private"))
    }
}
