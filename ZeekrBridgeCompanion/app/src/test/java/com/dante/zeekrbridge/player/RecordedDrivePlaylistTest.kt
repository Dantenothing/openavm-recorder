package com.dante.zeekrbridge.player

import io.github.dantenothing.avmtransfer.protocol.DriveDataBundle
import io.github.dantenothing.avmtransfer.protocol.DriveGpsSample
import io.github.dantenothing.avmtransfer.protocol.DriveVehicleSample
import io.github.dantenothing.avmtransfer.protocol.DriveVehicleValue
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RecordedDrivePlaylistTest {
    private val session = "12345678-1234-1234-1234-123456789abc"
    private fun gps(at: Long, broken: Boolean = false, provider: String = "gps") =
        DriveGpsSample(at, 1_800_000_000_000L + at, -34.9234567, 138.6123456, 5f, provider, 10f, 1f, broken)
    private fun bundle(start: Long, end: Long, points: List<DriveGpsSample>, sessionId: String = session) =
        DriveDataBundle(recordingSessionId = sessionId, segmentStartedElapsedMs = start, segmentEndedElapsedMs = end, gps = points)
    private fun summary(id: String, index: Int, data: DriveDataBundle, budget: Int = 100) = summarizeRecordedDrive(id, index, data, budget)

    @Test fun continuousSegmentsUseVideoOffsetsAndCanJoinWithinSameSession() {
        val a = summary("a", 0, bundle(10_000, 20_000, listOf(gps(10_000), gps(19_000))))
        val b = summary("b", 1, bundle(20_000, 30_000, listOf(gps(20_000), gps(29_000))))
        val route = recordedPlaylistRoute(listOf(a, b), listOf(10_000, 10_000))
        assertEquals(listOf(0L, 9_000L, 10_000L, 19_000L), route.points.map { it.playbackMs })
        assertFalse(route.points[2].breakBefore)
        assertEquals(PlaylistPosition(1, 0), PlaybackTimeline(listOf(10_000, 10_000)).resolve(route.points[2].playbackMs))
    }

    @Test fun missingMiddleAttachmentRetainsItsVideoTimeAndBreaksRoute() {
        val a = summary("a", 0, bundle(10_000, 20_000, listOf(gps(19_000))))
        val missing = RecordedDriveSegment("missing", 1, DriveAttachmentStatus.MISSING)
        val b = summary("b", 2, bundle(30_000, 40_000, listOf(gps(30_000))))
        val route = recordedPlaylistRoute(listOf(a, missing, b), listOf(10_000, 7_000, 10_000))
        assertEquals(listOf(0L, 10_000L, 17_000L), route.offsetsMs)
        assertEquals(17_000L, route.points.last().playbackMs)
        assertTrue(route.points.last().breakBefore)
        assertEquals(1, route.missingSegments)
    }

    @Test fun unknownMiddleDurationBlocksLaterMapPointsAndMarker() {
        val a = summary("a", 0, bundle(10_000, 20_000, listOf(gps(10_000))))
        val bData = bundle(30_000, 40_000, listOf(gps(30_000)))
        val route = recordedPlaylistRoute(listOf(a, RecordedDriveSegment("missing", 1, DriveAttachmentStatus.MISSING), summary("b", 2, bData)),
            listOf(10_000, 0, 10_000))
        assertEquals(listOf(0L, 10_000L, null), route.offsetsMs)
        assertEquals(1, route.points.size)
        assertTrue(route.timingIncomplete)
        assertNull(recordedPlaylistMarker(bData, recordedDriveFrame(bData, 0), route, 2, 10_000))
    }

    @Test fun shorterActualVideoTrimsPointsAndUsesItsActualDurationForTheNextSegment() {
        val a = summary("a", 0, bundle(10_000, 20_000, listOf(gps(10_000), gps(14_000), gps(18_000))))
        val b = summary("b", 1, bundle(20_000, 30_000, listOf(gps(20_000))))
        val route = recordedPlaylistRoute(listOf(a, b), listOf(5_000, 10_000))
        assertEquals(listOf(0L, 4_000L, 5_000L), route.points.map { it.playbackMs })
        assertTrue(route.points.last().breakBefore)
        val original = bundle(10_000, 20_000, listOf(gps(14_000), gps(15_000)))
        assertNotNull(recordedPlaylistFrame(original, 4_000, 5_000).gps)
        assertNull(recordedPlaylistFrame(original, 5_000, 5_000).gps)
        assertNull(recordedPlaylistFrame(original, 0, 0).gps)
    }

    @Test fun changedSessionSkippedSourceAndElapsedDiscontinuityEachForceBreak() {
        val a = summary("a", 0, bundle(10_000, 20_000, listOf(gps(19_000))))
        val differentSession = summary("b", 1, bundle(20_000, 30_000, listOf(gps(20_000)), "22345678-1234-1234-1234-123456789abc"))
        val skippedSource = summary("b", 2, bundle(20_000, 30_000, listOf(gps(20_000))))
        val elapsedGap = summary("b", 1, bundle(20_500, 30_500, listOf(gps(20_500))))
        listOf(differentSession, skippedSource, elapsedGap).forEach { b ->
            assertTrue(recordedPlaylistRoute(listOf(a, b), listOf(10_000, 10_000)).points.last().breakBefore)
        }
    }

    @Test fun pointBudgetPreservesBreaksThatOccurInOmittedSamples() {
        val data = bundle(10_000, 20_000, (0..9).map { gps(10_000L + it * 1_000, broken = it == 3) })
        val summary = summary("a", 0, data, budget = 3)
        assertEquals(3, summary.points.size)
        assertEquals(listOf(10_000L, 14_000L, 19_000L), summary.points.map { it.elapsedMs })
        assertTrue(summary.points[1].breakBefore)
        assertTrue(summary.simplified)
        assertEquals(10, summary.gpsSampleCount)
    }

    @Test fun currentMarkerUsesOriginalFreshSampleEvenWhenOverviewIsSimplified() {
        val data = bundle(10_000, 20_000, (0..9).map { gps(10_000L + it * 1_000) })
        val route = recordedPlaylistRoute(listOf(summary("a", 0, data, 2)), listOf(10_000))
        val marker = recordedPlaylistMarker(data, recordedDriveFrame(data, 4_500), route, 0, 10_000)!!
        assertEquals(4_000L, marker.playbackMs)
        assertFalse(route.points.any { it.playbackMs == marker.playbackMs })
        assertNull(recordedPlaylistMarker(data, recordedDriveFrame(data, 10_000), route, 0, 10_000))
    }

    @Test fun seekingAcrossSegmentsUsesThatSegmentsVehicleReadings() {
        val a = bundle(10_000, 20_000, emptyList()).copy(vehicle = listOf(DriveVehicleSample(10_000, 1_800_000_010_000,
            mapOf("speed" to DriveVehicleValue(10_000, number = 15.0)))))
        val b = bundle(20_000, 30_000, emptyList()).copy(vehicle = listOf(DriveVehicleSample(20_000, 1_800_000_020_000,
            mapOf("speed" to DriveVehicleValue(20_000, number = 45.0)))))
        val seek = PlaybackTimeline(listOf(10_000, 10_000)).resolve(10_500)
        assertEquals(1, seek.mediaItemIndex)
        val frame = recordedDriveFrame(listOf(a, b)[seek.mediaItemIndex], seek.positionMs)
        assertEquals(45.0, recordedDriveValue(frame, "speed")!!.number!!, 0.0)
        assertNull(recordedDriveValue(recordedDriveFrame(b, 3_000), "speed"))
    }

    @Test fun unsupportedAndLimitedSegmentsKeepTimeWithoutInventingGps() {
        val route = recordedPlaylistRoute(listOf(RecordedDriveSegment("a", 0, DriveAttachmentStatus.TIME_LAPSE),
            RecordedDriveSegment("b", 1, DriveAttachmentStatus.ROUTE_LIMIT)), listOf(1_000, 2_000))
        assertTrue(route.points.isEmpty())
        assertEquals(listOf(0L, 1_000L), route.offsetsMs)
        assertEquals(1, route.unsupportedSegments)
        assertEquals(1, route.limitedSegments)
    }

    @Test fun aggregateReportContainsCountsAndMapOutcomeWithoutCoordinatesPathsOrSessionIds() {
        val data = bundle(10_000, 20_000, listOf(gps(10_000)))
        val route = recordedPlaylistRoute(listOf(summary("/private/car-secret.mp4", 0, data)), listOf(10_000))
        val text = recordedPlaybackReport("test-version", route, 1, 0, 500, DriveAttachmentStatus.AVAILABLE,
            recordedDriveFrame(data, 500), mapOf("mode" to "STREET", "loadOutcome" to "RENDERED", "coordinates" to "-34.9234567,138.6123456", "path" to "/secret"))
        val report = Json.parseToJsonElement(text).jsonObject
        assertEquals(1, report.getValue("mappedSegments").jsonPrimitive.int)
        assertEquals(1, report.getValue("mappedPoints").jsonPrimitive.int)
        assertEquals("RENDERED", report.getValue("map").jsonObject.getValue("loadOutcome").jsonPrimitive.content)
        assertFalse(text.contains("-34.9234567")); assertFalse(text.contains("138.6123456"))
        assertFalse(text.contains("/private")); assertFalse(text.contains("/secret")); assertFalse(text.contains(session))
    }
}
