package io.github.dantenothing.drivemap

import org.junit.Assert.*
import org.junit.Test

class RoadMapGeometryTest {
    private fun point(ms: Long, lat: Double = -34.9, lon: Double = 138.6, gap: Boolean = false) =
        RoadMapPoint(lat, lon, ms, gap)

    @Test fun explicitSourceGapsAreNeverConnected() {
        val route = RoadMapGeometry.prepare(listOf(point(0), point(1_000), point(2_000, gap = true), point(7_001, gap = true)))
        assertEquals(listOf(2, 1, 1), route.strokes.map { it.size })
    }

    @Test fun sparseOverviewKeepsVerifiedContinuityAndExplicitGaps() {
        val route = RoadMapGeometry.prepare(listOf(point(0), point(20_000), point(40_000), point(60_000, gap = true), point(80_000)))
        assertEquals(listOf(3, 2), route.strokes.map { it.size })
        assertEquals(listOf(0L, 20_000L, 40_000L, 60_000L, 80_000L), route.points.map { it.point.playbackMs })
    }

    @Test fun rejectedPointsBreakBothSidesAndDoNotResetMonotonicClock() {
        val route = RoadMapGeometry.prepare(listOf(point(1_000), point(2_000, lat = Double.NaN), point(900), point(3_000), point(3_000), point(4_000)))
        assertEquals(listOf(1, 1, 1), route.strokes.map { it.size })
        assertEquals(listOf(1_000L, 3_000L, 4_000L), route.points.map { it.point.playbackMs })
    }

    @Test fun invalidCoordinatesAndNegativeTimesAreRejected() {
        val route = RoadMapGeometry.prepare(listOf(point(-1), point(1, lat = 91.0), point(2, lon = 181.0),
            point(3, lon = Double.POSITIVE_INFINITY), point(4)))
        assertEquals(listOf(4L), route.points.map { it.point.playbackMs })
    }

    @Test fun antimeridianFitsLocalAreaInsteadOfWholeWorld() {
        val route = RoadMapGeometry.prepare(listOf(point(0, lon = 179.999), point(1_000, lon = -179.999)))
        assertTrue(kotlin.math.abs(route.points[1].x - route.points[0].x) < 0.001)
        val fit = RoadMapGeometry.fit(route.points, 500.0, 300.0)!!
        assertTrue(fit.third > 15.0)
        assertEquals(-180.0, RoadMapGeometry.longitude(fit.first), 0.00001)
    }

    @Test fun mercatorInverseRoundTripsAndPolesRemainFinite() {
        for (lat in listOf(-80.0, -34.9, 0.0, 45.0, 80.0)) {
            assertEquals(lat, RoadMapGeometry.latitude(RoadMapGeometry.y(lat)), 0.000001)
        }
        for (lon in listOf(-179.0, -1.0, 0.0, 138.6, 179.0)) {
            assertEquals(lon, RoadMapGeometry.longitude(RoadMapGeometry.x(lon)), 0.000001)
        }
        assertTrue(RoadMapGeometry.y(90.0).isFinite())
        assertTrue(RoadMapGeometry.y(-90.0).isFinite())
    }

    @Test fun tapOnlySeeksMeasuredPointWithinScreenRadius() {
        val route = RoadMapGeometry.prepare(listOf(point(0), point(1_000), point(2_000)))
        val projection: (WorldPoint) -> Pair<Double, Double> = { it.point.playbackMs / 10.0 to 0.0 }
        assertNull(RoadMapGeometry.nearest(route.points, 50.0, 0.0, 20.0, projection))
        assertEquals(1_000L, RoadMapGeometry.nearest(route.points, 105.0, 4.0, 20.0, projection)?.playbackMs)
        assertNull(RoadMapGeometry.nearest(route.points, 100.0, 40.0, 20.0, projection))
    }

    @Test fun emptyAndMalformedSelectionIsSafe() {
        assertNull(RoadMapGeometry.fit(emptyList(), 200.0, 200.0))
        assertNull(RoadMapGeometry.fit(RoadMapGeometry.prepare(listOf(point(0))).points, 0.0, 200.0))
        assertNull(RoadMapGeometry.nearest(emptyList(), 0.0, 0.0, 20.0) { 0.0 to 0.0 })
        assertNull(RoadMapGeometry.nearest(RoadMapGeometry.prepare(listOf(point(0))).points, Double.NaN, 0.0, 20.0) { 0.0 to 0.0 })
    }

    @Test fun oversizedInputsAreBoundedAndLabelled() {
        val route = RoadMapGeometry.prepare(List(RoadMapGeometry.MAX_POINTS + 1) { point(it.toLong()) })
        assertEquals(RoadMapGeometry.MAX_POINTS, route.points.size)
        assertTrue(route.truncated)
    }

    @Test fun diagnosticsNeverRetainArbitraryErrorText() {
        RoadMapDiagnostics.failed("https://example.test/?lat=12.345&lon=67.89")
        val snapshot = RoadMapDiagnostics.snapshot()
        assertEquals("LOAD_ERROR", snapshot["errorCategory"])
        assertFalse(snapshot.values.any { it.contains("12.345") || it.contains("example.test") })
        assertFalse(snapshot.keys.any { it.contains("lat", ignoreCase = true) || it.contains("url", ignoreCase = true) })
    }
}
