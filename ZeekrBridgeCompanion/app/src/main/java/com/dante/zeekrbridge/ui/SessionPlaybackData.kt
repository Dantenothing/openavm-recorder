package com.dante.zeekrbridge.ui

import android.content.Context
import com.dante.zeekrbridge.core.*
import com.dante.zeekrbridge.player.*
import io.github.dantenothing.avmtransfer.protocol.SessionPlaybackTimeline
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class LoadedSessionPlayback(
    val source: List<IndexedMediaSegment>,
    val entries: Map<String, PlaybackEntry>,
    val timeline: SessionPlaybackTimeline,
    val review: SessionDriveReview,
    val unavailableFiles: Int,
    val limitedFiles: Int,
)

/** Caller runs on IO. Bound file scans, retain only one full attachment at a time, honor cancellation. */
internal suspend fun loadSessionPlayback(context: Context, source: List<IndexedMediaSegment>, loadReview: Boolean = true): LoadedSessionPlayback? {
    val selected = source.take(SessionPlaybackTimeline.MAX_SEGMENTS)
    val entries = linkedMapOf<String, PlaybackEntry>()
    val durations = linkedMapOf<String, Long>()
    for (segment in selected) {
        currentCoroutineContext().ensureActive()
        val entry = segment.playbackEntry()
        if (!entry.readable || ContinuousRasterSupport.error(segment) != null) continue
        val geometry = readDisplayGeometry(context, entry)
        if (entry.raster is io.github.dantenothing.avmtransfer.protocol.RecordingRasterMetadata.Repacked &&
            ContinuousRasterSupport.trackError(entry.raster, entry.layoutKind, geometry?.width ?: 0,
                geometry?.height ?: 0, geometry?.metadataRotationDegrees ?: 0) != null) continue
        val duration = readPlaybackDuration(context, entry)
        currentCoroutineContext().ensureActive()
        if (duration > 0) { entries[entry.id] = entry; durations[entry.id] = duration }
    }
    val timeline = phoneSessionTimeline(selected, durations, entries.keys) ?: return null
    val review = SessionReviewAccumulator(timeline)
    for (window in if (loadReview) timeline.segments else emptyList()) {
        currentCoroutineContext().ensureActive()
        val entry = entries.getValue(window.id)
        val bundle = entry.uri.path?.let { DriveDataFiles.load(java.io.File(it)) }
        currentCoroutineContext().ensureActive()
        if (bundle != null) review.add(window.id, bundle) else review.missing()
    }
    val markers = phoneSessionMarkers(selected, timeline)
    val unavailable = selected.size - timeline.segments.size
    val limited = (source.size - selected.size).coerceAtLeast(0)
    val summary = review.finish(markers)
    return LoadedSessionPlayback(source, entries, timeline,
        summary.copy(route = summary.route.copy(limitedSegments = limited, timingIncomplete = summary.route.timingIncomplete || unavailable > 0),
            summary = summary.summary.copy(incomplete = summary.summary.incomplete || unavailable > 0 || limited > 0)), unavailable, limited)
}
