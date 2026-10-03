package com.dante.zeekrbridge.player

import com.dante.zeekrbridge.core.IndexedMediaSegment
import com.dante.zeekrbridge.core.IndexedRecordingMode
import io.github.dantenothing.avmtransfer.protocol.SessionPlaybackTimeline
import io.github.dantenothing.avmtransfer.protocol.SessionVideoSegment
import io.github.dantenothing.drivemap.DriveReviewEvent
import io.github.dantenothing.drivemap.DriveEventKind

/** Measured container durations only. Unreadable or unclocked files remain holes on this axis. */
internal fun phoneSessionTimeline(
    segments: List<IndexedMediaSegment>, durations: Map<String, Long>, availableIds: Set<String>,
): SessionPlaybackTimeline? {
    val sessionId = segments.mapNotNull { it.recordingSessionId }.distinct().singleOrNull() ?: return null
    val windows = segments.mapNotNull { segment ->
        if (segment.recordingSessionId != sessionId || segment.id !in availableIds || segment.recordingMode != IndexedRecordingMode.NORMAL) return@mapNotNull null
        val channel = segment.playbackChannelId ?: return@mapNotNull null
        if (channel != SessionPlaybackTimeline.channelForRole(segment.sourceRole.name)) return@mapNotNull null
        SessionVideoSegment(segment.id, channel, sessionId,
            segment.startedElapsedMs ?: return@mapNotNull null, segment.stoppedElapsedMs ?: return@mapNotNull null,
            durations[segment.id] ?: 0L, segment.videoClockAnchor, segment.continuousTimeline)
    }
    return SessionPlaybackTimeline(sessionId, windows, segments.mapNotNull { it.sessionStartedElapsedMs }.minOrNull())
}

internal fun supportsSessionPlayback(segments: List<IndexedMediaSegment>): Boolean {
    if (segments.isEmpty() || segments.any { it.recordingMode != IndexedRecordingMode.NORMAL }) return false
    // Explicit parallel-format files must never fall back to playing cameras in series, even
    // when their session identity/clock is damaged. The session loader shows that failure.
    if (segments.any { it.channelId != null }) return true
    val session = segments.map { it.recordingSessionId }.distinct().singleOrNull()?.takeIf { it.isNotBlank() } ?: return false
    // Legacy files remain playable through the original player when timing is incomplete.
    return segments.all { item ->
        val channel = item.playbackChannelId ?: return@all false
        val start = item.startedElapsedMs ?: return@all false
        val end = item.stoppedElapsedMs ?: return@all false
        SessionVideoSegment(item.id, channel, session, start, end, 1, item.videoClockAnchor, item.continuousTimeline).valid()
    }
}

/** A selected channel's missing interval never resolves to footage from another channel. */
internal fun nextSessionVideoPosition(timeline: SessionPlaybackTimeline, channel: String, afterMs: Long): Long? =
    timeline.segments.asSequence().filter { it.channelId == channel }
        .mapNotNull { timeline.mediaToSession(it.id, 0) }.filter { it > afterMs }.minOrNull()

/** Used only on initial open; deliberate later seeks into a gap retain the chosen position. */
internal fun initialPhoneSessionPosition(timeline: SessionPlaybackTimeline, channel: String): Long =
    timeline.segments.firstOrNull { it.channelId == channel }?.let { timeline.mediaToSession(it.id, 0) } ?: 0L

internal fun phoneSessionMarkers(segments: List<IndexedMediaSegment>, timeline: SessionPlaybackTimeline): List<DriveReviewEvent> {
    val windows = timeline.segments.associateBy { it.id }
    return segments.filter { it.recordingSessionId == timeline.sessionId }.flatMap { segment ->
        val window = windows[segment.id]
        val physical = segment.triggerMarkers.mapNotNull { marker ->
            if (window == null || marker.positionMs !in 0 until window.mediaDurationMs) return@mapNotNull null
            val kind = when (marker.type) { "MANUAL" -> DriveEventKind.MANUAL; "EMERGENCY" -> DriveEventKind.EMERGENCY; else -> return@mapNotNull null }
            timeline.mediaToSession(segment.id, marker.positionMs)?.takeIf { it < timeline.durationMs }?.let { DriveReviewEvent(it, kind) }
        }
        physical + listOfNotNull(segment.eventRequestedElapsedMs?.let(timeline::elapsedToSession)?.takeIf { it < timeline.durationMs }
            ?.let { DriveReviewEvent(it, DriveEventKind.MANUAL) })
    }.distinct()
}
