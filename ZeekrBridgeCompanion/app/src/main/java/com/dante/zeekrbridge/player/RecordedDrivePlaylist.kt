package com.dante.zeekrbridge.player

import io.github.dantenothing.avmtransfer.protocol.DriveDataBundle
import io.github.dantenothing.avmtransfer.protocol.DriveGpsSample

internal const val MAX_DRIVE_ROUTE_POINTS = 60_000
internal const val MAX_DRIVE_ROUTE_SEGMENTS = 2_000

internal enum class DriveAttachmentStatus { LOADING, AVAILABLE, MISSING, TIME_LAPSE, NOT_LOCAL, ROUTE_LIMIT }

internal data class RecordedRouteSample(
    val latitude: Double, val longitude: Double, val elapsedMs: Long, val provider: String, val breakBefore: Boolean,
)

/** Compact route-only projection; full GPS and vehicle rows stay in the current segment's bundle. */
internal data class RecordedDriveSegment(
    val mediaId: String,
    val sourceIndex: Int,
    val status: DriveAttachmentStatus,
    val sessionId: String? = null,
    val startedElapsedMs: Long = 0L,
    val endedElapsedMs: Long = 0L,
    val points: List<RecordedRouteSample> = emptyList(),
    val simplified: Boolean = false,
    val gpsSampleCount: Int = 0,
    val vehicleSampleCount: Int = 0,
)

internal data class RecordedRoutePoint(val latitude: Double, val longitude: Double, val playbackMs: Long, val breakBefore: Boolean)
internal data class RecordedDriveRoute(
    val points: List<RecordedRoutePoint>,
    val offsetsMs: List<Long?>,
    val missingSegments: Int,
    val unsupportedSegments: Int,
    val limitedSegments: Int,
    val timingIncomplete: Boolean,
    val simplified: Boolean,
    val mappedSegments: Int,
    val gpsSampleCount: Int,
    val vehicleSampleCount: Int,
)

internal fun recordedPlaylistFrame(bundle: DriveDataBundle?, positionMs: Long?, durationMs: Long): RecordedDriveFrame =
    if (bundle == null || positionMs == null || durationMs <= 0L || positionMs !in 0 until durationMs)
        RecordedDriveFrame(null, null, null)
    else recordedDriveFrame(bundle, positionMs)

/** Uniformly retain actual samples; every omitted gap still breaks the next retained point. */
internal fun summarizeRecordedDrive(mediaId: String, sourceIndex: Int, bundle: DriveDataBundle, pointBudget: Int): RecordedDriveSegment {
    val samples = bundle.gps
    val budget = pointBudget.coerceAtLeast(1)
    val keep = if (samples.size <= budget) samples.indices.toSet() else if (budget == 1) setOf(0)
        else (0 until budget).map { (it.toLong() * samples.lastIndex / (budget - 1)).toInt() }.toSet()
    val points = ArrayList<RecordedRouteSample>(minOf(samples.size, budget))
    var previous: DriveGpsSample? = null
    var pendingBreak = false
    samples.forEachIndexed { index, sample ->
        val prior = previous
        if (sample.breakBefore || prior != null && (sample.provider != prior.provider || sample.elapsedMs - prior.elapsedMs > 5_000L)) pendingBreak = true
        if (index in keep) {
            points += RecordedRouteSample(sample.latitude, sample.longitude, sample.elapsedMs, sample.provider, pendingBreak)
            pendingBreak = false
        }
        previous = sample
    }
    return RecordedDriveSegment(mediaId, sourceIndex, DriveAttachmentStatus.AVAILABLE, bundle.recordingSessionId,
        bundle.segmentStartedElapsedMs, bundle.segmentEndedElapsedMs, points, samples.size > points.size, samples.size, bundle.vehicle.size)
}

/** Preserve all video time, including entries without attachments; never close a missing-data gap. */
internal fun recordedPlaylistRoute(segments: List<RecordedDriveSegment>, durationsMs: List<Long>): RecordedDriveRoute {
    val offsets = ArrayList<Long?>(segments.size)
    val points = ArrayList<RecordedRoutePoint>()
    var offset: Long? = 0L
    var previousSegment: RecordedDriveSegment? = null
    var previousPoint: RecordedRouteSample? = null
    var mappedSegments = 0
    segments.forEachIndexed { index, segment ->
        val start = offset
        val duration = durationsMs.getOrNull(index)?.takeIf { it > 0L && start != null && start <= Long.MAX_VALUE - it }
        offsets += start
        val retained = if (start != null && duration != null && segment.status == DriveAttachmentStatus.AVAILABLE)
            segment.points.filter { it.elapsedMs >= segment.startedElapsedMs && it.elapsedMs < segment.endedElapsedMs &&
                it.elapsedMs - segment.startedElapsedMs < duration }
        else emptyList()
        val priorSegment = previousSegment
        val priorPoint = previousPoint
        val first = retained.firstOrNull()
        if (first != null) mappedSegments++
        val joinsPrevious = priorSegment != null && priorPoint != null && first != null &&
            priorSegment.status == DriveAttachmentStatus.AVAILABLE && segment.sessionId != null && segment.sessionId == priorSegment.sessionId &&
            segment.sourceIndex == priorSegment.sourceIndex + 1 && priorSegment.endedElapsedMs == segment.startedElapsedMs &&
            priorSegment.endedElapsedMs - priorSegment.startedElapsedMs == durationsMs.getOrNull(index - 1) &&
            first.provider == priorPoint.provider && first.elapsedMs - priorPoint.elapsedMs in 1..5_000L
        retained.forEachIndexed { pointIndex, sample ->
            points += RecordedRoutePoint(sample.latitude, sample.longitude, start!! + (sample.elapsedMs - segment.startedElapsedMs),
                sample.breakBefore || pointIndex == 0 && !joinsPrevious)
        }
        previousSegment = segment.takeIf { retained.isNotEmpty() }
        previousPoint = retained.lastOrNull()
        offset = if (start != null && duration != null && start <= Long.MAX_VALUE - duration) start + duration else null
    }
    return RecordedDriveRoute(points, offsets,
        segments.count { it.status == DriveAttachmentStatus.MISSING || it.status == DriveAttachmentStatus.NOT_LOCAL },
        segments.count { it.status == DriveAttachmentStatus.TIME_LAPSE }, segments.count { it.status == DriveAttachmentStatus.ROUTE_LIMIT },
        offset == null, segments.any { it.simplified }, mappedSegments, segments.sumOf { it.gpsSampleCount }, segments.sumOf { it.vehicleSampleCount })
}

/** Marker is looked up independently from the simplified overview, so it never snaps to a future route point. */
internal fun recordedPlaylistMarker(
    bundle: DriveDataBundle?, frame: RecordedDriveFrame, route: RecordedDriveRoute, mediaIndex: Int, durationMs: Long,
): RecordedRoutePoint? {
    val sample = frame.gps ?: return null
    val data = bundle ?: return null
    val offset = route.offsetsMs.getOrNull(mediaIndex) ?: return null
    val local = sample.elapsedMs - data.segmentStartedElapsedMs
    if (local < 0L || local >= durationMs || sample.elapsedMs >= data.segmentEndedElapsedMs || offset > Long.MAX_VALUE - local) return null
    return RecordedRoutePoint(sample.latitude, sample.longitude, offset + local, sample.breakBefore)
}
