package io.github.dantenothing.drivemap

import kotlin.math.*

/**
 * A measured point at a caller-owned playback time, never a phone location or a routed estimate.
 * [breakBefore] is authoritative: callers mark real source gaps before simplifying the overview.
 * Spacing between retained playback points does not establish a GPS outage.
 */
data class RoadMapPoint(
    val latitude: Double,
    val longitude: Double,
    val playbackMs: Long,
    val breakBefore: Boolean = false,
)

internal data class WorldPoint(val point: RoadMapPoint, val x: Double, val y: Double)
internal data class RoadGeometry(val strokes: List<List<WorldPoint>>, val truncated: Boolean) {
    val points: List<WorldPoint> = strokes.flatten()
}

internal object RoadMapGeometry {
    const val MAX_POINTS = 60_000
    private const val MAX_MERCATOR_LATITUDE = 85.05112878

    fun valid(point: RoadMapPoint) = point.latitude.isFinite() && point.longitude.isFinite() &&
        point.latitude in -90.0..90.0 && point.longitude in -180.0..180.0 && point.playbackMs >= 0

    fun x(longitude: Double) = (longitude + 180.0) / 360.0
    fun y(latitude: Double): Double {
        val sine = sin(Math.toRadians(latitude.coerceIn(-MAX_MERCATOR_LATITUDE, MAX_MERCATOR_LATITUDE)))
        return (0.5 - ln((1 + sine) / (1 - sine)) / (4 * PI)).coerceIn(0.0, 1.0)
    }
    fun latitude(y: Double) = Math.toDegrees(atan(sinh(PI * (1 - 2 * y))))
    fun longitude(x: Double) = ((x * 360.0) % 360.0 + 360.0) % 360.0 - 180.0
    fun wrappedDelta(delta: Double) = delta - floor(delta + 0.5)

    /** Rejects malformed or repeated times and breaks both sides of a rejected sample. */
    fun prepare(input: List<RoadMapPoint>): RoadGeometry {
        val strokes = mutableListOf<MutableList<WorldPoint>>()
        var previous: RoadMapPoint? = null
        var latestTime = -1L
        var lastX: Double? = null
        var forceBreak = true
        input.take(MAX_POINTS).forEach { point ->
            if (!valid(point) || point.playbackMs <= latestTime) {
                forceBreak = true
                previous = null
                return@forEach
            }
            val prior = previous
            val rawX = x(point.longitude)
            val unwrappedX = lastX?.let { it + wrappedDelta(rawX - it) } ?: rawX
            if (forceBreak || point.breakBefore || prior == null) {
                strokes += mutableListOf<WorldPoint>()
            }
            strokes.last().add(WorldPoint(point, unwrappedX, y(point.latitude)))
            previous = point
            latestTime = point.playbackMs
            lastX = unwrappedX
            forceBreak = false
        }
        return RoadGeometry(strokes, input.size > MAX_POINTS)
    }

    /** Screen-space selection returns only an existing sample; it never invents a point in a gap. */
    fun nearest(points: List<WorldPoint>, tapX: Double, tapY: Double, radius: Double,
                project: (WorldPoint) -> Pair<Double, Double>): RoadMapPoint? {
        if (!tapX.isFinite() || !tapY.isFinite() || !radius.isFinite() || radius <= 0) return null
        var best: RoadMapPoint? = null
        var distance = radius * radius
        points.forEach { point ->
            val (px, py) = project(point)
            val d = (px - tapX).pow(2) + (py - tapY).pow(2)
            if (d.isFinite() && d < distance) { distance = d; best = point.point }
        }
        return best
    }

    fun fit(points: List<WorldPoint>, width: Double, height: Double): Triple<Double, Double, Double>? {
        if (points.isEmpty() || width <= 0 || height <= 0) return null
        val west = points.minOf { it.x }; val east = points.maxOf { it.x }
        val north = points.minOf { it.y }; val south = points.maxOf { it.y }
        val worldScale = min(width / max(east - west, 1e-8), height / max(south - north, 1e-8))
        return Triple((west + east) / 2, (north + south) / 2, log2(worldScale / 512.0).coerceIn(1.0, 17.0))
    }
}
