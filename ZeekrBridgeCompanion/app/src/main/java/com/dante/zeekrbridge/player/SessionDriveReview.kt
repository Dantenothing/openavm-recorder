package com.dante.zeekrbridge.player

import io.github.dantenothing.avmtransfer.protocol.*
import io.github.dantenothing.drivemap.*
import java.util.TreeMap

internal data class SessionDriveReview(
    val route: RecordedDriveRoute,
    val events: List<DriveReviewEvent>,
    val summary: DriveCoverageSummary,
    val observationsLimited: Boolean,
    val recorderSnapshot: DriveRecorderReport? = null,
)

/** Retain compact observations, never all full attachments. Parallel copies share one timestamp. */
internal class SessionReviewAccumulator(private val timeline: SessionPlaybackTimeline) {
    private val gps = TreeMap<Long, DriveGpsSample>()
    private val vehicle = TreeMap<Long, DriveEventObservation>()
    private val windows = timeline.segments.associateBy { it.id }
    private var limited = false
    private var loaded = 0
    private var missing = 0
    private var recorderSnapshot: DriveRecorderReport? = null
    private val coveredWindows = mutableListOf<LongRange>()

    fun missing() { missing++ }

    fun add(segmentId: String, bundle: DriveDataBundle) {
        val window = windows[segmentId] ?: return
        if (bundle.recordingSessionId != timeline.sessionId) return
        bundle.recorderReport?.takeIf { it.valid() && it.sessionCorrelation == DriveRecorderReport.correlation(timeline.sessionId) }?.let { candidate ->
            val before = recorderSnapshot
            // Keep one actual snapshot, never sum duplicated reports carried by parallel files.
            if (before == null || compareValuesBy(candidate, before,
                    { it.channels.sumOf { channel -> channel.finalizedFiles + channel.failedFiles + channel.provisionalFiles } },
                    { it.runtimeChannels.size }, { it.runtimeChannels.sumOf { channel -> channel.writtenFrames } }) > 0) {
                recorderSnapshot = candidate
            }
        }
        loaded++
        val coveredStart = maxOf(window.mediaStartElapsedMs!!, bundle.segmentStartedElapsedMs)
        val coveredEnd = minOf(window.mediaEndElapsedMs!!, bundle.segmentEndedElapsedMs)
        if (coveredEnd > coveredStart) coveredWindows += coveredStart until coveredEnd
        fun contains(at: Long) = at >= window.mediaStartElapsedMs!! && at < window.mediaEndElapsedMs!! &&
            at >= bundle.segmentStartedElapsedMs && at < bundle.segmentEndedElapsedMs
        bundle.gps.forEach { sample ->
            if (contains(sample.elapsedMs)) {
                if (gps.size < MAX_ROWS || sample.elapsedMs in gps) gps.putIfAbsent(sample.elapsedMs, sample) else limited = true
            }
        }
        bundle.vehicle.forEach { sample ->
            if (contains(sample.elapsedMs)) {
                val at = sample.elapsedMs - timeline.originElapsedMs
                if (vehicle.size < MAX_ROWS || at in vehicle) {
                    val fresh = DriveDataPlayback.vehicleAt(bundle.vehicle, sample.elapsedMs)
                    val hud = DriveHudModel.frame(sample.elapsedMs, fresh?.values?.mapValues { (_, value) ->
                        DriveHudObservation(value.readElapsedMs, value.number, value.text, value.status)
                    }.orEmpty())
                    val available = fresh?.values?.values?.any { it.status in setOf("OK", "PROVISIONAL_RAW") &&
                        (it.number != null || it.text != null) } == true
                    val previous = vehicle[at]
                    // Parallel attachments can clip different fields at a segment edge.
                    // Retain observed fields at this exact timestamp without inventing a transition.
                    vehicle[at] = DriveEventObservation(at, previous?.turn ?: hud.turn,
                        previous?.brakePressed ?: hud.brakePressed, sample.breakBefore || previous?.breakBefore == true,
                        available || previous?.available == true)
                } else limited = true
            }
        }
    }

    fun finish(markers: List<DriveReviewEvent> = emptyList()): SessionDriveReview {
        val coverage = mutableListOf<LongRange>()
        coveredWindows.sortedBy { it.first }.forEach { range ->
            val previous = coverage.lastOrNull()
            if (previous != null && range.first <= previous.last + 1) coverage[coverage.lastIndex] = previous.first..maxOf(previous.last, range.last)
            else coverage += range
        }
        var interval = 0
        var previousInterval = -1
        var previous: DriveGpsSample? = null
        val originals = gps.values.map { point ->
            while (interval < coverage.lastIndex && point.elapsedMs > coverage[interval].last) interval++
            val before = previous
            previous = point
            val gap = interval != previousInterval
            previousInterval = interval
            DriveCoveragePoint(point.elapsedMs - timeline.originElapsedMs, point.latitude, point.longitude,
                point.breakBefore || gap || before == null || point.provider != before.provider || point.elapsedMs - before.elapsedMs > 5_000L)
        }
        val keep = if (originals.size <= MAX_DRIVE_ROUTE_POINTS) originals.indices.toSet() else
            (0 until MAX_DRIVE_ROUTE_POINTS).map { (it.toLong() * originals.lastIndex / (MAX_DRIVE_ROUTE_POINTS - 1)).toInt() }.toSet()
        val routePoints = ArrayList<RecordedRoutePoint>()
        var pendingBreak = false
        originals.forEachIndexed { index, point ->
            pendingBreak = pendingBreak || point.breakBefore
            if (index in keep) {
                routePoints += RecordedRoutePoint(point.latitude, point.longitude, point.sessionMs, pendingBreak)
                pendingBreak = false
            }
        }
        interval = 0; previousInterval = -1
        val rows = vehicle.values.map { row ->
            val at = row.sessionMs + timeline.originElapsedMs
            while (interval < coverage.lastIndex && at > coverage[interval].last) interval++
            val gap = interval != previousInterval
            previousInterval = interval
            row.copy(breakBefore = row.breakBefore || gap)
        }
        val gaps = originals.drop(1).filter { it.breakBefore }.map { DriveReviewEvent(it.sessionMs, DriveEventKind.GPS_GAP) }
        val interruptions = timeline.segments.groupBy { it.channelId }.values.flatMap { channel ->
            channel.zipWithNext().mapNotNull { (before, after) ->
                if (after.mediaStartElapsedMs!! > before.mediaEndElapsedMs!!) DriveReviewEvent(
                    before.mediaEndElapsedMs!! - timeline.originElapsedMs, DriveEventKind.RECORDING_INTERRUPTED) else null
            }
        }
        val summary = DriveSessionSummary.summarize(timeline.durationMs, originals, rows)
        return SessionDriveReview(
            RecordedDriveRoute(routePoints, emptyList(), missing, 0, 0, timeline.rejectedSegments > 0,
                limited || originals.size > routePoints.size, loaded, gps.size, vehicle.size),
            DriveEventTimeline.build(rows, markers + gaps + interruptions), summary.copy(incomplete = summary.incomplete || limited), limited,
            recorderSnapshot)
    }

    companion object { const val MAX_ROWS = 120_000 }
}
