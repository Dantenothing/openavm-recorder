package com.dante.zeekrcapabilitylab.product

import com.dante.zeekrcapabilitylab.service.recorder.SegmentSidecar
import java.io.File

/**
 * Pure grouping of finalized recording segments into product-facing events.
 *
 * - New Save Clip actions carry an explicit eventId, so their pre/current/post
 *   segments form one exact incident without swallowing a whole continuous drive.
 * - Legacy bookmarks have no eventId; a bounded time window supplies context.
 * - Regular loop segments are grouped into one user-facing Start/Stop recording.
 */
object EventGroups {
    const val INCIDENT_GAP_MS = 5 * 60_000L
    const val LEGACY_CONTEXT_MS = 2 * 60_000L
    const val LEGACY_SESSION_GAP_MS = 30_000L

    data class Segment(
        val file: File,
        val sidecar: SegmentSidecar,
    )

    data class EventGroup(
        val incident: Boolean,
        val startedAtEpochMs: Long,
        val stoppedAtEpochMs: Long,
        val segments: List<Segment>,
        val protectedCount: Int,
    ) {
        val durationMs: Long
            get() = segments.sumOf(::durationOf)

        val totalBytes: Long
            get() = segments.sumOf { it.file.length().coerceAtLeast(0L) }

        val laneCount: Int
            get() = segments.firstOrNull()
                ?.sidecar?.laneLayout?.lanes?.size
                ?: if (isFourLane(segments.firstOrNull()?.sidecar)) 4 else 1

        val transferPending: Boolean
            get() = segments.any { it.sidecar.uploadPinned }

        private fun isFourLane(sidecar: SegmentSidecar?): Boolean =
            sidecar != null && (
                sidecar.profile.size.width.toFloat() / sidecar.profile.size.height >= 3.2f ||
                    sidecar.profile.size.height.toFloat() / sidecar.profile.size.width >= 3.2f
                )
    }

    fun groupIncidents(segments: List<Segment>): List<EventGroup> {
        val protected = segments.filter { it.sidecar.protected }
        val tagged = protected
            .filter { !it.sidecar.eventId.isNullOrBlank() }
            .groupBy { checkNotNull(it.sidecar.eventId) }
            .values
            .map(::eventGroup)

        val sessionProtected = protected
            .filter {
                it.sidecar.eventId.isNullOrBlank() &&
                    !it.sidecar.recordingSessionId.isNullOrBlank()
            }
            .groupBy { checkNotNull(it.sidecar.recordingSessionId) }
            .values
            .map(::eventGroup)

        val legacyCandidates = segments.filter {
            it.sidecar.eventId.isNullOrBlank() && it.sidecar.recordingSessionId.isNullOrBlank()
        }
        val legacy = protected
            .filter {
                it.sidecar.eventId.isNullOrBlank() &&
                    it.sidecar.recordingSessionId.isNullOrBlank()
            }
            .map { anchor ->
                val anchorTime = startOf(anchor)
                legacyCandidates.filter { candidate ->
                    kotlin.math.abs(startOf(candidate) - anchorTime) <= LEGACY_CONTEXT_MS
                }
            }
            .filter { it.isNotEmpty() }
            .distinctBy { group -> group.map { it.file.absolutePath }.sorted() }
            .map(::eventGroup)

        return (tagged + sessionProtected + legacy).sortedByDescending { it.startedAtEpochMs }
    }

    /**
     * Two overlapping incident windows may legitimately begin with the same
     * segment. The complete ordered membership gives each displayed incident
     * a stable identity without relying on a shared first file.
     */
    fun stableIncidentKey(group: EventGroup): String = buildString {
        append("event:")
        group.segments.forEach { segment ->
            val name = segment.file.name
            append(name.length).append(':').append(name).append(';')
        }
    }

    fun groupByDate(segments: List<Segment>): List<Pair<String, EventGroup>> {
        val byDate = segments
            .groupBy { segment ->
                val epoch = segment.sidecar.startedAtEpochMs ?: segment.file.lastModified()
                java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(epoch))
            }
        return byDate.entries
            .sortedByDescending { it.key }
            .map { (date, items) ->
                val sorted = items.sortedBy { it.sidecar.startedAtEpochMs ?: it.file.lastModified() }
                date to EventGroup(
                    incident = false,
                    startedAtEpochMs = sorted.first().sidecar.startedAtEpochMs ?: sorted.first().file.lastModified(),
                    stoppedAtEpochMs = sorted.last().sidecar.stoppedAtEpochMs
                        ?: sorted.last().file.lastModified(),
                    segments = sorted,
                    protectedCount = sorted.count { it.sidecar.protected },
                )
            }
    }

    /**
     * Returns one group for each explicit Start/Stop recording. New sidecars use
     * [SegmentSidecar.recordingSessionId]. For old sidecars, a conservative
     * segment-number/process/time continuity check avoids merging separate starts.
     */
    fun groupRecordings(segments: List<Segment>): List<EventGroup> {
        val tagged = segments
            .filter { !it.sidecar.recordingSessionId.isNullOrBlank() }
            .groupBy { checkNotNull(it.sidecar.recordingSessionId) }
            .values
            .map { recordingGroup(it) }

        val legacy = segments
            .filter { it.sidecar.recordingSessionId.isNullOrBlank() }
            .groupBy { it.sidecar.processStartId }
            .values
            .flatMap { processSegments -> splitLegacySessions(processSegments) }
            .map { recordingGroup(it) }

        return (tagged + legacy).sortedByDescending { it.startedAtEpochMs }
    }

    fun groupRecordingsByDate(segments: List<Segment>): List<Pair<String, List<EventGroup>>> =
        groupRecordings(segments)
            .groupBy { dateOf(it.startedAtEpochMs) }
            .entries
            .sortedByDescending { it.key }
            .map { (date, recordings) -> date to recordings.sortedByDescending { it.startedAtEpochMs } }

    private fun eventGroup(segments: List<Segment>): EventGroup {
        val sorted = segments.sortedBy { it.sidecar.startedAtEpochMs ?: it.file.lastModified() }
        return EventGroup(
            incident = true,
            startedAtEpochMs = sorted.first().sidecar.startedAtEpochMs ?: sorted.first().file.lastModified(),
            stoppedAtEpochMs = sorted.last().sidecar.stoppedAtEpochMs ?: sorted.last().file.lastModified(),
            segments = sorted,
            protectedCount = sorted.count { it.sidecar.protected },
        )
    }

    private fun recordingGroup(segments: List<Segment>): EventGroup {
        val sorted = segments.sortedWith(
            compareBy<Segment> { it.sidecar.segmentNumber }
                .thenBy(::startOf),
        )
        return EventGroup(
            incident = false,
            startedAtEpochMs = startOf(sorted.first()),
            stoppedAtEpochMs = sorted.last().sidecar.stoppedAtEpochMs ?: sorted.last().file.lastModified(),
            segments = sorted,
            protectedCount = sorted.count { it.sidecar.protected },
        )
    }

    private fun splitLegacySessions(segments: List<Segment>): List<List<Segment>> {
        val sorted = segments.sortedBy(::startOf)
        if (sorted.isEmpty()) return emptyList()
        val groups = mutableListOf<MutableList<Segment>>()
        sorted.forEach { segment ->
            val previous = groups.lastOrNull()?.lastOrNull()
            val previousStop = previous?.sidecar?.stoppedAtEpochMs ?: previous?.let(::startOf)
            val continuous = previous != null &&
                segment.sidecar.segmentNumber == previous.sidecar.segmentNumber + 1 &&
                previous.sidecar.recordingMode == segment.sidecar.recordingMode &&
                previous.sidecar.profile == segment.sidecar.profile &&
                startOf(segment) - checkNotNull(previousStop) <= LEGACY_SESSION_GAP_MS
            if (continuous) groups.last() += segment else groups += mutableListOf(segment)
        }
        return groups
    }

    private fun durationOf(segment: Segment): Long =
        segment.sidecar.actualTrack?.durationMs?.takeIf { it >= 0L }
            ?: run {
                val start = segment.sidecar.startedAtEpochMs
                val stop = segment.sidecar.stoppedAtEpochMs
                if (start != null && stop != null) {
                    (stop - start).coerceAtLeast(0L)
                } else {
                    segment.sidecar.segmentSeconds.coerceAtLeast(0) * 1000L
                }
            }

    private fun dateOf(epochMs: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(epochMs))

    private fun startOf(segment: Segment): Long =
        segment.sidecar.startedAtEpochMs ?: segment.file.lastModified()
}
