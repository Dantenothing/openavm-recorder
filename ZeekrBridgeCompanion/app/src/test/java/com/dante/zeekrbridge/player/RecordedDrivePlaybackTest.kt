package com.dante.zeekrbridge.player

import io.github.dantenothing.avmtransfer.protocol.DriveDataBundle
import io.github.dantenothing.avmtransfer.protocol.DriveGpsSample
import io.github.dantenothing.avmtransfer.protocol.DriveVehicleSample
import io.github.dantenothing.avmtransfer.protocol.DriveVehicleValue
import org.junit.Assert.*
import org.junit.Test

class RecordedDrivePlaybackTest {
    private fun gps(at: Long, longitude: Double = 138.6, provider: String = "gps", breakBefore: Boolean = false) =
        DriveGpsSample(at, 1_800_000_000_000L + at, -34.9, longitude, 5f, provider, 10f, 1f, breakBefore)

    private fun vehicle(at: Long, values: Map<String, DriveVehicleValue>, breakBefore: Boolean = false) =
        DriveVehicleSample(at, 1_800_000_000_000L + at, values, breakBefore)

    private fun bundle(gps: List<DriveGpsSample> = emptyList(), vehicle: List<DriveVehicleSample> = emptyList()) =
        DriveDataBundle(recordingSessionId = "12345678-1234-1234-1234-123456789abc", segmentStartedElapsedMs = 10_000,
            segmentEndedElapsedMs = 30_000, gps = gps, vehicle = vehicle)

    @Test fun onlySegmentRelativePositionsWithinHalfOpenWindowAreMapped() {
        val data = bundle()
        assertNull(recordedDriveElapsed(data, null))
        assertNull(recordedDriveElapsed(data, -1))
        assertEquals(10_000L, recordedDriveElapsed(data, 0))
        assertEquals(29_999L, recordedDriveElapsed(data, 19_999))
        assertNull(recordedDriveElapsed(data, 20_000))
        assertNull(recordedDriveElapsed(data, Long.MAX_VALUE))
        assertNull(recordedDriveElapsed(data.copy(alignment = "UNKNOWN"), 0))
    }

    @Test fun positionUsesRecordedElapsedTimeAndNeverBorrowsFutureReadings() {
        val data = bundle(gps = listOf(gps(11_000), gps(12_000)), vehicle = listOf(
            vehicle(11_000, mapOf("speed" to DriveVehicleValue(11_000, number = 45.0))),
            vehicle(12_000, mapOf("speed" to DriveVehicleValue(12_000, number = 60.0))),
        ))
        val before = recordedDriveFrame(data, 500)
        assertNull(before.gps); assertNull(before.vehicle)
        val frame = recordedDriveFrame(data, 1_500)
        assertEquals(11_500L, frame.elapsedMs)
        assertEquals(11_000L, frame.gps!!.elapsedMs)
        assertEquals(45.0, recordedDriveValue(frame, "speed")!!.number!!, 0.0)
    }

    @Test fun perFieldStaleAndUnknownValuesAreNotDisplayedAsZero() {
        val data = bundle(vehicle = listOf(vehicle(12_000, mapOf(
            "speed" to DriveVehicleValue(10_000, number = 0.0),
            "gear" to DriveVehicleValue(12_000, status = "UNKNOWN_ENUM"),
            "brake_pedal" to DriveVehicleValue(12_000, number = 0.0),
        ))))
        val frame = recordedDriveFrame(data, 2_001)
        assertEquals("STALE", frame.vehicle!!.values.getValue("speed").status)
        assertNull(recordedDriveValue(frame, "speed"))
        assertNull(recordedDriveValue(frame, "gear"))
        assertEquals(0.0, recordedDriveValue(frame, "brake_pedal")!!.number!!, 0.0)
        assertNull(recordedDriveFrame(data, 4_001).vehicle)
    }

    @Test fun directIndicatorWinsAndPartialFallbackDoesNotInventAState() {
        fun frame(values: Map<String, DriveVehicleValue>) = recordedDriveFrame(bundle(vehicle = listOf(vehicle(10_000, values))), 0)
        fun flag(value: Double) = DriveVehicleValue(10_000, number = value)
        assertNull(recordedDriveTurn(frame(mapOf("turn_left" to flag(0.0)))))
        assertEquals("left", recordedDriveTurn(frame(mapOf("turn_left" to flag(1.0), "turn_right" to flag(0.0)))))
        assertEquals("hazard", recordedDriveTurn(frame(mapOf("turn_left" to flag(1.0), "turn_right" to flag(1.0)))))
        assertEquals("right", recordedDriveTurn(frame(mapOf("indicator" to DriveVehicleValue(10_000, text = "right"),
            "turn_left" to flag(1.0), "turn_right" to flag(1.0)))))
    }

    @Test fun traceStopsAtPlayheadAndRemovesMarkerAcrossGap() {
        val data = bundle(gps = listOf(gps(10_000), gps(11_000, 138.601), gps(12_000, 138.602, breakBefore = true), gps(13_000, 138.603)))
        val frame = recordedDriveFrame(data, 1_500)
        assertNull(frame.gps)
        val trace = recordedDriveTrace(data.gps, frame)
        assertEquals(2, trace.pointCount)
        assertEquals(1, trace.strokes.size)
        assertNull(trace.current)
        assertTrue(trace.strokes.flatten().all { it.elapsedMs <= 11_500 })
        val resumed = recordedDriveTrace(data.gps, recordedDriveFrame(data, 2_000))
        assertEquals(2, resumed.strokes.size)
        assertEquals(12_000L, resumed.current!!.elapsedMs)
    }

    @Test fun traceSeparatesProviderChangesAndLongIntervals() {
        val data = bundle(gps = listOf(gps(10_000), gps(11_000, provider = "network"), gps(18_000, provider = "network")))
        val trace = recordedDriveTrace(data.gps, recordedDriveFrame(data, 8_000))
        assertEquals(listOf(1, 1, 1), trace.strokes.map { it.size })
        assertEquals(18_000L, trace.current!!.elapsedMs)
    }

    @Test fun staleMarkerDisappearsButHistoricalTraceRemains() {
        val data = bundle(gps = listOf(gps(10_000), gps(11_000)))
        val trace = recordedDriveTrace(data.gps, recordedDriveFrame(data, 3_001))
        assertEquals(2, trace.pointCount)
        assertNull(trace.current)
        val outside = recordedDriveTrace(data.gps, recordedDriveFrame(data, 20_000))
        assertEquals(0, outside.pointCount)
        assertNull(outside.current)
    }

    @Test fun antimeridianCrossingDoesNotDrawAcrossTheWorld() {
        val data = bundle(gps = listOf(gps(10_000, 179.999), gps(11_000, -179.999)))
        val trace = recordedDriveTrace(data.gps, recordedDriveFrame(data, 1_000))
        val points = trace.strokes.single()
        assertTrue(points[1].eastM - points[0].eastM in 100.0..250.0)
        assertTrue(points.all { it.eastM.isFinite() && it.northM.isFinite() })
    }
}
