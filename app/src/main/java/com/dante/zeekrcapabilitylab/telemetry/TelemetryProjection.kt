package com.dante.zeekrcapabilitylab.telemetry

import kotlin.math.cos
import kotlin.math.max

data class RoutePoint(val x: Float, val y: Float, val connected: Boolean)

/** Local equirectangular trace, not a street map. Unwrap longitude at the date line. */
object TelemetryProjection {
    fun project(points: List<TelemetryFix>): List<RoutePoint> {
        if (points.isEmpty()) return emptyList()
        val reference = points.first()
        val scale = cos(Math.toRadians(reference.latitude)).coerceAtLeast(0.01)
        var longitude = reference.longitude
        var previousLongitude = longitude
        val raw = points.map {
            val delta = ((it.longitude - previousLongitude + 540) % 360) - 180
            longitude += delta; previousLongitude = it.longitude
            (longitude - reference.longitude) * scale to -(it.latitude - reference.latitude)
        }
        val minX = raw.minOf { it.first }; val maxX = raw.maxOf { it.first }
        val minY = raw.minOf { it.second }; val maxY = raw.maxOf { it.second }
        val span = max(maxX - minX, maxY - minY).coerceAtLeast(0.0001)
        val centerX = (minX + maxX) / 2; val centerY = (minY + maxY) / 2
        return raw.mapIndexed { index, (x, y) -> RoutePoint(
            ((x - centerX) / span + 0.5).toFloat(), ((y - centerY) / span + 0.5).toFloat(),
            index > 0 && TelemetryPlayback.connected(points[index - 1], points[index])) }
    }
}
