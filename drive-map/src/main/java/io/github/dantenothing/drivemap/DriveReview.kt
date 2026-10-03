package io.github.dantenothing.drivemap

import kotlin.math.*

data class DriveEventObservation(
    val sessionMs: Long,
    val turn: String? = null,
    val brakePressed: Boolean? = null,
    val breakBefore: Boolean = false,
    val available: Boolean = turn != null || brakePressed != null,
)

enum class DriveEventKind { TURN, BRAKE, VEHICLE_GAP, GPS_GAP, MANUAL, EMERGENCY, RECORDING_INTERRUPTED }
data class DriveReviewEvent(val sessionMs: Long, val kind: DriveEventKind, val value: String? = null)

/** Transitions are observations, not accident/harsh-driving classifications. All times are caller-mapped. */
object DriveEventTimeline {
    const val MAX_OBSERVATIONS = 120_000
    const val MAX_EVENTS = 2_000
    private val turns = setOf("off", "left", "right", "hazard")

    fun build(observations: List<DriveEventObservation>, markers: List<DriveReviewEvent> = emptyList()): List<DriveReviewEvent> {
        val events = ArrayList<DriveReviewEvent>()
        var previous: DriveEventObservation? = null
        var lastTime = -1L
        observations.take(MAX_OBSERVATIONS).forEach { sample ->
            if (sample.sessionMs < 0 || sample.sessionMs <= lastTime) { previous = null; return@forEach }
            val current = sample.copy(turn = sample.turn?.takeIf { it in turns })
            val prior = previous
            if (prior != null) {
                val broken = current.breakBefore || current.sessionMs - prior.sessionMs > DriveHudModel.MAX_AGE_MS
                if ((broken || !current.available) && prior.available && events.size < MAX_EVENTS) {
                    events += DriveReviewEvent(current.sessionMs, DriveEventKind.VEHICLE_GAP)
                }
                if (!broken && current.available && prior.available) {
                    if (current.turn != null && prior.turn != null && current.turn != prior.turn && events.size < MAX_EVENTS) {
                        events += DriveReviewEvent(current.sessionMs, DriveEventKind.TURN, current.turn)
                    }
                    if (current.brakePressed != null && prior.brakePressed != null && current.brakePressed != prior.brakePressed && events.size < MAX_EVENTS) {
                        events += DriveReviewEvent(current.sessionMs, DriveEventKind.BRAKE, if (current.brakePressed) "pressed" else "released")
                    }
                }
            }
            lastTime = current.sessionMs
            previous = current
        }
        // Caller-supplied markers refer to recorded manual/emergency/camera/GPS evidence only.
        markers.take(MAX_EVENTS).filter { it.sessionMs >= 0 && it.kind !in setOf(DriveEventKind.TURN, DriveEventKind.BRAKE) }
            .forEach { events += it.copy(value = null) }
        return events.distinct().sortedWith(compareBy<DriveReviewEvent> { it.sessionMs }.thenBy { it.kind.ordinal }).take(MAX_EVENTS)
    }
}

data class DriveCoveragePoint(val sessionMs: Long, val latitude: Double, val longitude: Double, val breakBefore: Boolean = false)
data class DriveCoverageSummary(
    val durationMs: Long,
    val distanceM: Double,
    val gpsCoveredMs: Long,
    val vehicleCoveredMs: Long,
    val gpsPointCount: Int,
    val vehicleObservationCount: Int,
    val incomplete: Boolean,
)

/**
 * GPS distance is the sum of retained measured legs, excluding source gaps; no road snapping.
 * Coverage is bounded freshness time, not an assertion that every sensor measured continuously.
 */
object DriveSessionSummary {
    const val MAX_POINTS = 120_000
    private const val MAX_DURATION_MS = 7L * 24 * 60 * 60 * 1_000

    fun summarize(durationMs: Long, gps: List<DriveCoveragePoint>, vehicle: List<DriveEventObservation>): DriveCoverageSummary {
        val duration = durationMs.coerceIn(0, MAX_DURATION_MS)
        var incomplete = duration != durationMs || gps.size > MAX_POINTS || vehicle.size > MAX_POINTS
        val gpsRows = ArrayList<DriveCoveragePoint>()
        var last = -1L
        var forceBreak = false
        gps.take(MAX_POINTS).forEach { point ->
            if (point.sessionMs !in 0 until duration || point.sessionMs <= last || !point.latitude.isFinite() || !point.longitude.isFinite() ||
                point.latitude !in -90.0..90.0 || point.longitude !in -180.0..180.0) {
                forceBreak = true; incomplete = true
            } else {
                gpsRows += point.copy(breakBefore = point.breakBefore || forceBreak)
                last = point.sessionMs; forceBreak = false
            }
        }
        var distanceMeters = 0.0
        gpsRows.zipWithNext().forEach { (a, b) ->
            if (!b.breakBefore && b.sessionMs - a.sessionMs in 1..5_000L) distanceMeters += distance(a, b)
        }
        val vehicleRows = ArrayList<DriveEventObservation>()
        last = -1L; forceBreak = false
        vehicle.take(MAX_POINTS).forEach { sample ->
            if (sample.sessionMs !in 0 until duration || sample.sessionMs <= last) {
                forceBreak = true; incomplete = true
            } else {
                vehicleRows += sample.copy(breakBefore = sample.breakBefore || forceBreak)
                last = sample.sessionMs; forceBreak = false
            }
        }
        val gpsCoverage = covered(duration, gpsRows.map { CoverageRow(it.sessionMs, true, it.breakBefore) }, 5_000L)
        val vehicleCoverage = covered(duration, vehicleRows.map { CoverageRow(it.sessionMs, it.available, it.breakBefore) }, DriveHudModel.MAX_AGE_MS)
        return DriveCoverageSummary(duration, distanceMeters, gpsCoverage, vehicleCoverage, gpsRows.size,
            vehicleRows.count { it.available }, incomplete || gpsCoverage < duration || vehicleCoverage < duration)
    }

    private data class CoverageRow(val at: Long, val available: Boolean, val broken: Boolean)
    private fun covered(duration: Long, rows: List<CoverageRow>, gapMs: Long): Long = rows.mapIndexed { index, point ->
        if (!point.available) return@mapIndexed 0L
        val next = rows.getOrNull(index + 1)
        // A known outage makes the interval uncertain, rather than carrying a last value into it.
        if (next != null && (next.broken || next.at - point.at > gapMs)) return@mapIndexed 0L
        minOf(DriveHudModel.MAX_AGE_MS, duration - point.at, (next?.at ?: duration) - point.at).coerceAtLeast(0)
    }.sum()

    private fun distance(a: DriveCoveragePoint, b: DriveCoveragePoint): Double {
        val lat1 = Math.toRadians(a.latitude); val lat2 = Math.toRadians(b.latitude)
        val lat = lat2 - lat1
        val lon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(lat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(lon / 2).pow(2)
        return 6_371_000.0 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
}
