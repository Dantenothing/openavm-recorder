package com.dante.zeekrcapabilitylab.telemetry

import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.xml.sax.InputSource

class TelemetryPlaylistRouteTest {
    private val first = "00000000-0000-0000-0000-000000000001"
    private val second = "00000000-0000-0000-0000-000000000002"
    private fun fix(at: Long, breakBefore: Boolean = false) = TelemetryFix(at, 1_790_000_000_000 + at,
        -34.0 - at / 10_000_000.0, 138.0, 3f, "gps", speedMps = 10f, breakBefore = breakBefore)
    private fun track(id: String = first, vararg points: TelemetryFix) = TelemetryTrack(TelemetryHeader(sessionId = id, startedElapsedMs = 0), points.toList())
    private fun window(start: Long, end: Long, duration: Long = end - start, id: String? = first, normal: Boolean = true) =
        TelemetryPlaylistWindow(id, start, end, duration, normal)

    @Test fun partialSelectionStartsAtZeroAndNeverIncludesOtherSessionPoints() {
        val source = track(first, fix(1_000), fix(2_000), fix(3_000), fix(4_000), fix(5_000), fix(6_000), fix(7_000))
        val route = TelemetryPlaylistRoutes.build(listOf(window(3_000, 5_000), window(6_000, 7_000))) { source }
        assertEquals(listOf(3_000L, 4_000L, 6_000L), route.points.map { it.fix.elapsedMs })
        assertEquals(listOf(0L, 1_000L, 2_000L), route.points.map { it.playbackMs })
        assertTrue(route.points.last().fix.breakBefore)
        assertEquals(6_000L, route.sampleAt(2_000)?.fix?.elapsedMs)
        assertNull(route.sampleAt(-1))
        assertNull(route.sampleAt(3_000))
    }

    @Test fun unknownMiddleDurationBlocksAllFollowingOffsetsAndReads() {
        val source = track(first, fix(1_000), fix(3_000))
        val loaded = mutableListOf<String>()
        val route = TelemetryPlaylistRoutes.build(listOf(window(1_000, 2_000), window(2_000, 3_000, 0),
            window(3_000, 4_000, id = second))) { loaded += it; source }
        assertEquals(listOf(0L), route.points.map { it.playbackMs })
        assertEquals(listOf(first), loaded)
        assertTrue(route.incomplete)
        assertNull(route.sampleAt(1_000))
    }

    @Test fun differentSessionsWithResetClocksStaySeparate() {
        val a = track(first, fix(1_000), fix(1_500))
        val b = track(second, fix(100), fix(600))
        val route = TelemetryPlaylistRoutes.build(listOf(window(1_000, 2_000), window(100, 1_100, id = second))) {
            if (it == first) a else b
        }
        assertEquals(listOf(0L, 500L, 1_000L, 1_500L), route.points.map { it.playbackMs })
        assertTrue(route.points[2].fix.breakBefore)
        assertEquals(100L, route.sampleAt(1_000)?.fix?.elapsedMs)
    }

    @Test fun missingTrackAndTimelapseKeepTheirMediaOffsetsWithoutDrawingConnections() {
        val source = track(first, fix(1_000), fix(4_000), fix(5_000))
        val route = TelemetryPlaylistRoutes.build(listOf(window(1_000, 2_000), window(2_000, 3_000, id = second),
            window(3_000, 4_000, normal = false), window(4_000, 6_000))) { if (it == first) source else null }
        assertEquals(listOf(0L, 3_000L, 4_000L), route.points.map { it.playbackMs })
        assertTrue(route.points[1].fix.breakBefore)
        assertNull(route.sampleAt(1_500))
        assertNull(route.sampleAt(2_500))
    }

    @Test fun contiguousNormalSegmentsCanConnect() {
        val source = track(first, fix(1_000), fix(1_500), fix(2_000), fix(2_500))
        val route = TelemetryPlaylistRoutes.build(listOf(window(1_000, 2_000), window(2_000, 3_000))) { source }
        assertTrue(route.points[0].fix.breakBefore)
        assertFalse(route.points[2].fix.breakBefore)
    }

    @Test fun actualShorterMediaClipsExtraSourceFixesAndDoesNotJoinSkippedTime() {
        val source = track(first, fix(1_000), fix(1_500), fix(2_000), fix(2_500), fix(3_000))
        val route = TelemetryPlaylistRoutes.build(listOf(window(1_000, 3_000, 1_000), window(3_000, 4_000))) { source }
        assertEquals(listOf(1_000L, 1_500L, 3_000L), route.points.map { it.fix.elapsedMs })
        assertEquals(1_000L, route.points.last().playbackMs)
        assertTrue(route.points.last().fix.breakBefore)
    }

    @Test fun longerMediaCannotInventDataAfterSourceWindow() {
        val source = track(first, fix(1_000), fix(1_500), fix(2_000))
        val route = TelemetryPlaylistRoutes.build(listOf(window(1_000, 2_000, 3_000))) { source }
        assertEquals(2, route.points.size)
        assertNotNull(route.sampleAt(999))
        assertNull(route.sampleAt(1_000))
        assertNull(route.sampleAt(2_000))
    }

    @Test fun futureStaleAndKnownGapSamplesStayUnknown() {
        val source = track(first, fix(1_000), fix(2_000, true), fix(3_000), fix(10_000))
        val route = TelemetryPlaylistRoutes.build(listOf(window(500, 13_000))) { source }
        assertNull(route.sampleAt(499))
        assertNotNull(route.sampleAt(500))
        assertNull(route.sampleAt(501))
        assertNotNull(route.sampleAt(1_500))
        assertNull(route.sampleAt(2_501))
        assertNotNull(route.sampleAt(11_500))
        assertNull(route.sampleAt(11_501))
    }

    @Test fun invalidRowsBreakLinesAndFreshness() {
        val source = track(first, fix(1_000), fix(1_250).copy(latitude = Double.NaN), fix(1_500))
        val route = TelemetryPlaylistRoutes.build(listOf(window(1_000, 2_000))) { source }
        assertEquals(2, route.points.size)
        assertNull(route.sampleAt(400))
        assertTrue(route.points.last().fix.breakBefore)
    }

    @Test fun invalidSourceIdentityAndMissingMetadataCannotBeDisplayed() {
        val source = track(second, fix(1_000))
        assertTrue(TelemetryPlaylistRoutes.build(listOf(window(1_000, 2_000))) { source }.points.isEmpty())
        assertTrue(TelemetryPlaylistRoutes.build(listOf(window(1_000, 2_000, id = null))) { error("No read") }.points.isEmpty())
        assertTrue(TelemetryPlaylistRoutes.build(listOf(window(1_000, 2_000, id = "../../private"))) { error("No read") }.points.isEmpty())
    }

    @Test fun exportContainsExactlyTheDisplayedClipsAcrossSessions() {
        val a = track(first, fix(500), fix(1_000), fix(1_500), fix(2_000))
        val b = track(second, fix(3_000), fix(3_500), fix(4_000))
        val route = TelemetryPlaylistRoutes.build(listOf(window(1_000, 2_000), window(3_000, 4_000, id = second))) {
            if (it == first) a else b
        }
        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }.newDocumentBuilder().parse(InputSource(StringReader(TelemetryPlaylistRoutes.gpx(route))))
        val elapsed = document.getElementsByTagNameNS("urn:openavm:telemetry:1", "elapsedMs")
        assertEquals(route.points.map { it.fix.elapsedMs }, (0 until elapsed.length).map { elapsed.item(it).textContent.toLong() })
        assertEquals(2, document.getElementsByTagNameNS("http://www.topografix.com/GPX/1/1", "trk").length)
        val selection = TelemetryPlaylistRoutes.selection(route)
        assertFalse(selection.contains("latitude"))
        assertEquals(route.windows, TelemetryPlaylistRoutes.readSelection(selection))
        assertEquals(route.points, TelemetryPlaylistRoutes.build(TelemetryPlaylistRoutes.readSelection(selection)!!) {
            if (it == first) a else b
        }.points)
    }

    @Test fun hostileSelectionAndBoundsRemainBounded() {
        assertNull(TelemetryPlaylistRoutes.readSelection("x".repeat(262_145)))
        assertNull(TelemetryPlaylistRoutes.readSelection("[{\"sessionId\":\"../secret\"}]"))
        val route = TelemetryPlaylistRoutes.build(List(TelemetryPlaylistRoutes.MAX_SEGMENTS + 1) {
            window(1_000, 2_000, id = null)
        }) { error("No read") }
        assertTrue(route.incomplete)
        assertEquals(TelemetryPlaylistRoutes.MAX_SEGMENTS, route.windows.size)
        assertTrue(TelemetryPlaylistRoutes.build(listOf(window(1_000, 2_000, Long.MAX_VALUE))) { error("No read") }.incomplete)
    }
}
