package com.dante.zeekrcapabilitylab.telemetry

import kotlinx.serialization.Serializable
import kotlin.math.*

/** Local recording telemetry. Only an explicit per-segment transfer may copy it into DriveDataBundle. */
@Serializable
data class TelemetryFix(
    val elapsedMs: Long,
    val epochMs: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Float,
    val provider: String,
    val speedMps: Float? = null,
    val speedAccuracyMps: Float? = null,
    val breakBefore: Boolean = false,
)

enum class FixRejection { OLD, FUTURE, INVALID, MOCK, INACCURATE, DUPLICATE, LOWER_QUALITY, JUMP }
data class FixDecision(val fix: TelemetryFix? = null, val rejected: FixRejection? = null)

/** Pure quality gate: missing speed stays null, and rejected gaps never become straight lines. */
class TelemetryFilter(private val startedElapsedMs: Long) {
    private var last: TelemetryFix? = null
    private var broken = true
    fun breakTrack() { broken = true }

    fun accept(input: TelemetryFix, nowMs: Long, mock: Boolean = false): FixDecision {
        fun reject(reason: FixRejection, breakPath: Boolean = true): FixDecision {
            if (breakPath) broken = true
            return FixDecision(rejected = reason)
        }
        if (mock) return reject(FixRejection.MOCK)
        if (!TelemetryPolicy.validPosition(input) || input.epochMs <= 0 || input.elapsedMs < 0)
            return reject(FixRejection.INVALID)
        if (input.elapsedMs > nowMs) return reject(FixRejection.FUTURE)
        if (input.elapsedMs < startedElapsedMs || nowMs - input.elapsedMs > TelemetryPolicy.FRESH_MS)
            return reject(FixRejection.OLD)
        if (input.accuracyM > TelemetryPolicy.MAX_ACCURACY_M) return reject(FixRejection.INACCURATE)
        val previous = last
        if (previous != null) {
            val dt = input.elapsedMs - previous.elapsedMs
            if (dt < 500) return reject(FixRejection.DUPLICATE, false)
            if (previous.provider == "gps" && input.provider != "gps" && dt <= TelemetryPolicy.FRESH_MS)
                return reject(FixRejection.LOWER_QUALITY, false)
            val possibleDistance = dt / 1_000.0 * 100.0 + previous.accuracyM + input.accuracyM
            if (dt <= TelemetryPolicy.MAX_GAP_MS && distanceM(previous, input) > possibleDistance)
                return reject(FixRejection.JUMP)
        }
        val speedAccuracy = input.speedAccuracyMps?.takeIf { it.isFinite() && it >= 0 }
        val speed = input.speedMps?.takeIf { it.isFinite() && it in 0f..100f &&
            (input.speedAccuracyMps == null || (speedAccuracy != null && speedAccuracy <= 3f)) }
        val accepted = input.copy(speedMps = speed, speedAccuracyMps = if (speed != null) speedAccuracy else null,
            breakBefore = broken || previous == null || input.provider != previous.provider ||
                input.elapsedMs - previous.elapsedMs > TelemetryPolicy.MAX_GAP_MS)
        last = accepted
        broken = false
        return FixDecision(fix = accepted)
    }
}

object TelemetryPolicy {
    const val FRESH_MS = 5_000L
    const val MAX_GAP_MS = 5_000L
    const val PLAYBACK_AGE_MS = 2_000L
    const val MAX_ACCURACY_M = 50f
    fun validPosition(fix: TelemetryFix): Boolean = fix.latitude.isFinite() && fix.longitude.isFinite() &&
        fix.latitude in -90.0..90.0 && fix.longitude in -180.0..180.0 &&
        fix.accuracyM.isFinite() && fix.accuracyM >= 0 && fix.provider in setOf("gps", "network", "fused", "passive")
    fun validStored(fix: TelemetryFix): Boolean = validPosition(fix) && fix.accuracyM <= MAX_ACCURACY_M &&
        fix.elapsedMs >= 0 && fix.epochMs > 0 &&
        (fix.speedMps == null || (fix.speedMps.isFinite() && fix.speedMps in 0f..100f)) &&
        (fix.speedAccuracyMps == null || (fix.speedAccuracyMps.isFinite() && fix.speedAccuracyMps in 0f..3f))
}

/** Same-boot monotonic mapping only. We do not infer timestamps from filenames or wall-clock time. */
data class TelemetryVideoWindow(val startedElapsedMs: Long?, val durationMs: Long, val timeLapse: Boolean = false) {
    fun at(positionMs: Long): Long? {
        val start = startedElapsedMs ?: return null
        if (timeLapse || start < 0 || durationMs <= 0 || positionMs !in 0 until durationMs ||
            start > Long.MAX_VALUE - positionMs) return null
        return start + positionMs
    }
}

object TelemetryPlayback {
    fun sampleAt(points: List<TelemetryFix>, atMs: Long?): TelemetryFix? {
        if (atMs == null) return null
        val index = points.binarySearchBy(atMs) { it.elapsedMs }.let { if (it >= 0) it else -it - 2 }
        val point = points.getOrNull(index) ?: return null
        if (!TelemetryPolicy.validStored(point) || atMs - point.elapsedMs !in 0..TelemetryPolicy.PLAYBACK_AGE_MS) return null
        val next = points.getOrNull(index + 1)
        if (atMs > point.elapsedMs && next != null && !connected(point, next)) return null
        return point
    }
    fun connected(a: TelemetryFix, b: TelemetryFix): Boolean = !b.breakBefore && a.provider == b.provider &&
        b.elapsedMs - a.elapsedMs in 1..TelemetryPolicy.MAX_GAP_MS
}

fun distanceM(a: TelemetryFix, b: TelemetryFix): Double {
    val lat = Math.toRadians(b.latitude - a.latitude)
    val lon = Math.toRadians(b.longitude - a.longitude)
    val h = sin(lat / 2).pow(2) + cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(lon / 2).pow(2)
    return 6_371_000.0 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
}
