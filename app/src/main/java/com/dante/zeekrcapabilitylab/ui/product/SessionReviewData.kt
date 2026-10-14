package com.dante.zeekrcapabilitylab.ui.product

import android.content.Context
import android.media.MediaMetadataRetriever
import com.dante.zeekrcapabilitylab.player.RecordingRasterReader
import com.dante.zeekrcapabilitylab.player.SessionReviewProjection
import com.dante.zeekrcapabilitylab.player.sessionPlaybackRuns
import com.dante.zeekrcapabilitylab.service.recorder.*
import com.dante.zeekrcapabilitylab.telemetry.*
import io.github.dantenothing.avmtransfer.protocol.*
import io.github.dantenothing.drivemap.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

internal data class SessionReviewEntry(val file: File, val sidecar: SegmentSidecar, val segment: SessionVideoSegment,
    val raster: RecordingRasterMetadata? = null)
internal data class SessionReviewLoadResult(val data: SessionReviewData?, val hasUntimedVideo: Boolean = false)
internal data class SessionReviewData(
    val timeline: SessionPlaybackTimeline,
    val entries: Map<String, SessionReviewEntry>,
    val gps: List<TelemetryFix>, val vehicle: List<VehicleTelemetrySample>,
    val route: List<RoadMapPoint>, val events: List<DriveReviewEvent>,
    val summary: DriveCoverageSummary, val skipped: Int,
)

internal fun sessionCameraRuns(data: SessionReviewData): Map<String, List<SessionReviewEntry>> {
    fun layout(id: String): List<Any?> = data.entries.getValue(id).sidecar.let {
        listOf(it.layoutKind, it.rasterLayout, it.profile.size, it.actualTrack?.width, it.actualTrack?.height, it.laneLayout)
    }
    val result = mutableMapOf<String, List<SessionReviewEntry>>()
    sessionPlaybackRuns(data.timeline.segments) { a, b -> layout(a.id) == layout(b.id) }.forEach { run ->
        val entries = run.map { data.entries.getValue(it.id) }
        run.forEach { result[it.id] = entries }
    }
    return result
}

/** Runs on IO, checks cancellation between bounded local files, and never fetches vehicle/network data. */
internal suspend fun loadSessionReview(context: Context, files: List<File>): SessionReviewLoadResult {
    val entries = ArrayList<SessionReviewEntry>()
    var session: String? = null
    var origin: Long? = null
    var hasUntimedVideo = false
    for (file in files.distinctBy { it.absolutePath }.take(SessionPlaybackTimeline.MAX_SEGMENTS)) {
        currentCoroutineContext().ensureActive()
        if (!file.isFile) continue
        val sidecar = SegmentSidecarIO.readForMedia(file) ?: continue
        val id = sidecar.recordingSessionId?.takeIf(TelemetryStore::validId) ?: continue
        if (sidecar.recordingMode != RecordingMode.NORMAL || sidecar.timeLapseMultiplier != 1 || sidecar.provisional) continue
        if (session != null && session != id) continue
        val channel = sidecar.channelId ?: SessionPlaybackTimeline.channelForRole(sidecar.sourceRole.name) ?: continue
        if (channel != SessionPlaybackTimeline.channelForRole(sidecar.sourceRole.name)) continue
        val raster = RecordingRasterReader.read(file)
        if (raster is RecordingRasterMetadata.Rejected) continue
        val start = sidecar.startedAtElapsedRealtimeMs
        val end = sidecar.stoppedAtElapsedRealtimeMs
        if (start == null || end == null || start < 0 || end <= start || sidecar.videoClockAnchor?.valid() == false) {
            hasUntimedVideo = true; continue
        }
        val duration = sidecar.actualTrack?.durationMs?.takeIf { it > 0 } ?: runCatching {
            val reader = MediaMetadataRetriever()
            try { reader.setDataSource(file.absolutePath); reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() }
            finally { reader.release() }
        }.getOrNull() ?: continue
        val segment = SessionVideoSegment(file.absolutePath, channel, id, start, end, duration, sidecar.videoClockAnchor, sidecar.continuousTimeline)
        if (!segment.valid()) continue
        session = id
        sidecar.sessionStartedElapsedMs?.let { origin = minOf(origin ?: it, it) }
        entries += SessionReviewEntry(file, sidecar, segment, raster)
    }
    currentCoroutineContext().ensureActive()
    val sessionId = session ?: return SessionReviewLoadResult(null, hasUntimedVideo)
    val timeline = SessionPlaybackTimeline(sessionId, entries.map { it.segment }, origin)
    if (timeline.durationMs <= 0) return SessionReviewLoadResult(null, hasUntimedVideo)
    val kept = timeline.segments.map { it.id }.toSet()
    val selected = entries.filter { it.segment.id in kept }.associateBy { it.segment.id }
    // Preparing the timeline must not decode thumbnails. The visible rail reuses cached
    // covers, then extracts missing ones only after the active video panes have a frame.
    val gps = SessionReviewProjection.gps(timeline, RecordingTelemetry.store(context).load(sessionId))
    currentCoroutineContext().ensureActive()
    val vehicle = SessionReviewProjection.vehicle(timeline, RecordingVehicleTelemetry.store(context).load(sessionId))
    val observations = SessionReviewProjection.observations(timeline, vehicle)
    val markers = ArrayList<DriveReviewEvent>()
    selected.values.forEach { entry ->
        entry.sidecar.triggerMarkers.take(2_000).forEach { marker ->
            val kind = when (marker.type.uppercase()) {
                "MANUAL", "BOOKMARK", "SAVE_CLIP" -> DriveEventKind.MANUAL
                "EMERGENCY" -> DriveEventKind.EMERGENCY
                else -> null
            }
            val at = timeline.mediaToSession(entry.segment.id, marker.positionMs)
            if (kind != null && at != null && at < timeline.durationMs) markers += DriveReviewEvent(at, kind)
        }
        entry.sidecar.eventRequestedElapsedMs?.let { elapsed ->
            timeline.elapsedToSession(elapsed)?.takeIf { it < timeline.durationMs }?.let { markers += DriveReviewEvent(it, DriveEventKind.MANUAL) }
        }
    }
    timeline.channelIds.forEach { channel ->
        timeline.segments.filter { it.channelId == channel }.zipWithNext().forEach { (a, b) ->
            if (b.mediaStartElapsedMs!! > a.mediaEndElapsedMs!!) markers += DriveReviewEvent(
                b.mediaStartElapsedMs!! - timeline.originElapsedMs, DriveEventKind.RECORDING_INTERRUPTED)
        }
    }
    gps.drop(1).filter { it.breakBefore }.take(2_000).forEach { markers += DriveReviewEvent(it.elapsedMs - timeline.originElapsedMs, DriveEventKind.GPS_GAP) }
    currentCoroutineContext().ensureActive()
    return SessionReviewLoadResult(SessionReviewData(timeline, selected, gps, vehicle,
        gps.map { RoadMapPoint(it.latitude, it.longitude, it.elapsedMs - timeline.originElapsedMs, it.breakBefore) },
        DriveEventTimeline.build(observations, markers),
        DriveSessionSummary.summarize(timeline.durationMs, gps.map { DriveCoveragePoint(it.elapsedMs - timeline.originElapsedMs,
            it.latitude, it.longitude, it.breakBefore) }, observations),
        files.distinctBy { it.absolutePath }.size - selected.size), hasUntimedVideo)
}
