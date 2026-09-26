package com.dante.zeekrcapabilitylab.telemetry

import com.dante.zeekrcapabilitylab.player.PlaybackTimeline
import org.junit.Assert.*
import org.junit.Test

class TelemetryModelTest {
    private fun fix(at: Long = 1_000, speed: Float? = 10f) = TelemetryFix(at, 1_790_000_000_000 + at,
        -34.9, 138.6, 5f, "gps", speed)

    @Test fun absentSpeedIsNotZeroButReportedZeroIsPreserved() {
        val filter = TelemetryFilter(0)
        assertNull(filter.accept(fix(speed = null), 1_000).fix!!.speedMps)
        assertEquals(0f, filter.accept(fix(2_000, 0f), 2_000).fix!!.speedMps)
    }
    @Test fun badSpeedDoesNotDiscardAUsablePosition() {
        for (speed in listOf(-1f, Float.NaN, Float.POSITIVE_INFINITY, 101f)) {
            assertNull(TelemetryFilter(0).accept(fix(speed = speed), 1_000).fix!!.speedMps)
        }
        assertNull(TelemetryFilter(0).accept(fix().copy(speedAccuracyMps = 4f), 1_000).fix!!.speedMps)
        assertNull(TelemetryFilter(0).accept(fix().copy(speedAccuracyMps = Float.NaN), 1_000).fix!!.speedMps)
        assertEquals(10f, TelemetryFilter(0).accept(fix().copy(speedAccuracyMps = 0.5f), 1_000).fix!!.speedMps)
    }
    @Test fun previousSessionAndCachedFixesAreRejected() {
        assertEquals(FixRejection.OLD, TelemetryFilter(1_001).accept(fix(), 1_001).rejected)
        assertEquals(FixRejection.OLD, TelemetryFilter(0).accept(fix(), 6_001).rejected)
        assertEquals(FixRejection.FUTURE, TelemetryFilter(0).accept(fix(), 999).rejected)
        assertNotNull(TelemetryFilter(0).accept(fix(), 6_000).fix)
    }
    @Test fun mockAndInvalidCoordinatesNeverBecomeHistory() {
        assertEquals(FixRejection.MOCK, TelemetryFilter(0).accept(fix(), 1_000, mock = true).rejected)
        listOf(fix().copy(latitude = Double.NaN), fix().copy(longitude = 181.0),
            fix().copy(accuracyM = Float.NaN), fix().copy(accuracyM = -1f), fix().copy(provider = "unknown")).forEach {
            assertEquals(FixRejection.INVALID, TelemetryFilter(0).accept(it, 1_000).rejected)
        }
        assertEquals(FixRejection.INACCURATE, TelemetryFilter(0).accept(fix().copy(accuracyM = 51f), 1_000).rejected)
    }
    @Test fun gpsWinsOverARecentNetworkFixWithoutBreakingThePath() {
        val filter = TelemetryFilter(0)
        val first = filter.accept(fix(), 1_000).fix!!
        assertTrue(first.breakBefore)
        assertEquals(FixRejection.LOWER_QUALITY, filter.accept(fix(2_000).copy(provider = "network"), 2_000).rejected)
        val next = filter.accept(fix(3_000), 3_000).fix!!
        assertTrue(TelemetryPlayback.connected(first, next))
    }
    @Test fun duplicateCallbackCannotAddSamplesOrInventAGap() {
        val filter = TelemetryFilter(0)
        filter.accept(fix(), 1_000)
        assertEquals(FixRejection.DUPLICATE, filter.accept(fix(1_300), 1_300).rejected)
        assertFalse(filter.accept(fix(2_000), 2_000).fix!!.breakBefore)
    }
    @Test fun gpsLossThenNetworkFallbackCreatesABreak() {
        val filter = TelemetryFilter(0)
        filter.accept(fix(), 1_000)
        assertTrue(filter.accept(fix(7_000).copy(provider = "network"), 7_000).fix!!.breakBefore)
        assertTrue(filter.accept(fix(8_000), 8_000).fix!!.breakBefore)
    }
    @Test fun jumpsAndPoorFixesCreateGapsInsteadOfConnectingAcrossThem() {
        val filter = TelemetryFilter(0)
        filter.accept(fix(), 1_000)
        assertEquals(FixRejection.JUMP, filter.accept(fix(2_000).copy(latitude = -35.9), 2_000).rejected)
        assertTrue(filter.accept(fix(3_000), 3_000).fix!!.breakBefore)
        filter.accept(fix(4_000).copy(accuracyM = 99f), 4_000)
        assertTrue(filter.accept(fix(5_000), 5_000).fix!!.breakBefore)
    }
    @Test fun pausesAndLongGapsAreNeverConnected() {
        val filter = TelemetryFilter(0)
        val first = filter.accept(fix(), 1_000).fix!!
        filter.breakTrack()
        val second = filter.accept(fix(2_000), 2_000).fix!!
        assertFalse(TelemetryPlayback.connected(first, second))
        val third = filter.accept(fix(8_000), 8_000).fix!!
        assertFalse(TelemetryPlayback.connected(second, third))
    }
    @Test fun playbackNeverLooksAheadOrHoldsAnOldSpeed() {
        val points = listOf(fix(1_000), fix(9_000, 0f))
        assertNull(TelemetryPlayback.sampleAt(points, 999))
        assertEquals(10f, TelemetryPlayback.sampleAt(points, 3_000)!!.speedMps)
        assertNull(TelemetryPlayback.sampleAt(points, 3_001))
        assertNull(TelemetryPlayback.sampleAt(points, 8_999))
        assertEquals(0f, TelemetryPlayback.sampleAt(points, 9_000)!!.speedMps)
        assertNull(TelemetryPlayback.sampleAt(points, null))
    }
    @Test fun crossSegmentSeekingUsesEachSegmentsMonotonicStartAndRealGaps() {
        val timeline = PlaybackTimeline(2).apply { confirm(0, 60_000); confirm(1, 60_000) }
        val seek = timeline.seek(61_000)!!
        assertEquals(1, seek.index)
        val windows = listOf(TelemetryVideoWindow(100_000, 60_000), TelemetryVideoWindow(168_000, 60_000))
        assertEquals(169_000L, windows[seek.index].at(seek.positionMs))
        assertEquals(168_000L, windows[1].at(timeline.seek(60_000)!!.positionMs))
    }
    @Test fun missingClocksTimeLapseAndOverflowCannotPretendToBeSynchronized() {
        assertNull(TelemetryVideoWindow(null, 60_000).at(100))
        assertNull(TelemetryVideoWindow(1_000, 60_000, true).at(100))
        assertNull(TelemetryVideoWindow(-1, 60_000).at(100))
        assertNull(TelemetryVideoWindow(Long.MAX_VALUE - 5, 60_000).at(100))
        assertNull(TelemetryVideoWindow(0, 60_000).at(60_000))
    }
    @Test fun datelineAndSinglePointProjectionStayFiniteAndPreserveBreaks() {
        val points = listOf(fix().copy(longitude = 179.999, latitude = 1.0),
            fix(2_000).copy(longitude = -179.999, latitude = 1.001),
            fix(9_000).copy(longitude = -179.998, latitude = 1.002, breakBefore = true))
        val trace = TelemetryProjection.project(points)
        assertTrue(trace.all { it.x in 0f..1f && it.y in 0f..1f })
        assertTrue(trace[1].connected)
        assertFalse(trace[2].connected)
        // A dateline crossing is a small local move, not a line across the globe.
        assertTrue(trace[1].x - trace[0].x > 0.3f)
        assertEquals(RoutePoint(0.5f, 0.5f, false), TelemetryProjection.project(listOf(fix())).single())
    }
}
