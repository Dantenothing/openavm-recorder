package com.dante.zeekrbridge.player

import io.github.dantenothing.avmtransfer.protocol.DriveDataBundle
import io.github.dantenothing.avmtransfer.protocol.DriveDataCodec
import io.github.dantenothing.avmtransfer.protocol.DriveDataPlayback
import io.github.dantenothing.avmtransfer.protocol.DriveGpsSample
import io.github.dantenothing.avmtransfer.protocol.DriveVehicleSample
import io.github.dantenothing.avmtransfer.protocol.DriveVehicleValue
import kotlin.math.cos

internal data class RecordedDriveFrame(
    val elapsedMs: Long?,
    val gps: DriveGpsSample?,
    val vehicle: DriveVehicleSample?,
)

/** The receiver has already verified NORMAL mode and the attachment's exact segment binding. */
internal fun recordedDriveElapsed(bundle: DriveDataBundle, positionMs: Long?): Long? {
    val start = bundle.segmentStartedElapsedMs
    val end = bundle.segmentEndedElapsedMs
    if (start < 0L || end <= start || positionMs == null || positionMs < 0L) return null
    val elapsed = when (bundle.alignment) {
        DriveDataCodec.ALIGNMENT -> if (positionMs < end - start) start + positionMs else return null
        DriveDataCodec.ANCHORED_ALIGNMENT -> bundle.videoClockAnchor?.elapsedAtMediaMs(positionMs) ?: return null
        else -> return null
    }
    return elapsed.takeIf { it in start until end }
}

internal fun recordedDriveFrame(bundle: DriveDataBundle, positionMs: Long?): RecordedDriveFrame {
    val at = recordedDriveElapsed(bundle, positionMs)
    return RecordedDriveFrame(at, DriveDataPlayback.gpsAt(bundle.gps, at), DriveDataPlayback.vehicleAt(bundle.vehicle, at))
}

internal fun recordedDriveValue(frame: RecordedDriveFrame, key: String): DriveVehicleValue? =
    frame.vehicle?.values?.get(key)?.takeIf { it.status == "OK" }

/** Both individual turn signals must be known before inferring off/left/right/hazard. */
internal fun recordedDriveTurn(frame: RecordedDriveFrame): String? {
    recordedDriveValue(frame, "indicator")?.text?.takeIf { it in setOf("off", "left", "right", "hazard") }?.let { return it }
    val left = recordedDriveValue(frame, "turn_left")?.number?.takeIf { it == 0.0 || it == 1.0 } ?: return null
    val right = recordedDriveValue(frame, "turn_right")?.number?.takeIf { it == 0.0 || it == 1.0 } ?: return null
    return when {
        left == 1.0 && right == 1.0 -> "hazard"
        left == 1.0 -> "left"
        right == 1.0 -> "right"
        else -> "off"
    }
}

internal data class RecordedTracePoint(val elapsedMs: Long, val eastM: Double, val northM: Double)
internal data class RecordedTrace(val strokes: List<List<RecordedTracePoint>>, val current: RecordedTracePoint?) {
    val pointCount: Int get() = strokes.sumOf { it.size }
}

/** Local geographic trace through the playhead only; no street map, phone location, or gap interpolation. */
internal fun recordedDriveTrace(samples: List<DriveGpsSample>, frame: RecordedDriveFrame): RecordedTrace {
    val at = frame.elapsedMs ?: return RecordedTrace(emptyList(), null)
    val past = samples.takeWhile { it.elapsedMs <= at }
    val origin = past.firstOrNull() ?: return RecordedTrace(emptyList(), null)
    val longitudeScale = cos(Math.toRadians(origin.latitude)).coerceAtLeast(0.01)
    val strokes = mutableListOf<MutableList<RecordedTracePoint>>()
    var previous: DriveGpsSample? = null
    var unwrappedLongitude = origin.longitude
    var marker: RecordedTracePoint? = null
    past.forEach { sample ->
        // The persisted bundle was validated on load; retain a finite-coordinate guard for projection.
        if (!sample.latitude.isFinite() || !sample.longitude.isFinite()) { previous = null; return@forEach }
        val prior = previous
        if (prior != null) {
            val delta = ((sample.longitude - prior.longitude + 540.0) % 360.0) - 180.0
            unwrappedLongitude += delta
        } else unwrappedLongitude = sample.longitude
        if (prior == null || sample.breakBefore || sample.provider != prior.provider || sample.elapsedMs - prior.elapsedMs > 5_000L) {
            strokes += mutableListOf<RecordedTracePoint>()
        }
        val point = RecordedTracePoint(sample.elapsedMs,
            Math.toRadians(unwrappedLongitude - origin.longitude) * 6_371_000.0 * longitudeScale,
            Math.toRadians(sample.latitude - origin.latitude) * 6_371_000.0)
        strokes.last().add(point)
        if (sample.elapsedMs == frame.gps?.elapsedMs) marker = point
        previous = sample
    }
    return RecordedTrace(strokes, marker)
}
