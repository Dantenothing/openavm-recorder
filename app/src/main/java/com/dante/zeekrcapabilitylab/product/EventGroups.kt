package com.dante.zeekrcapabilitylab.product

import com.dante.zeekrcapabilitylab.service.recorder.SegmentSidecar
import java.io.File

/**
 * Pure grouping of finalized recording segments into product-facing events.
 *
 * - New Save Clip actions carry an explicit eventId, so their pre/current/post
 *   segments form one exact incident without swallowing a whole continuous drive.
 * - Legacy bookmarks have no eventId; a bounded time window supplies context.
 * - Regular loop recordings are grouped by local date for collapsed browsing.
 */
object EventGroups {
    const val INCIDENT_GAP_MS = 5 * 60_000L
    const val LEGACY_CONTEXT_MS = 2 * 60_000L

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
            get() = (stoppedAtEpochMs - startedAtEpochMs).coerceAtLeast(0L)

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

        val legacyCandidates = segments.filter { it.sidecar.eventId.isNullOrBlank() }
        val legacy = protected
            .filter { it.sidecar.eventId.isNullOrBlank() }
            .map { anchor ->
                val anchorTime = startOf(anchor)
                legacyCandidates.filter { candidate ->
                    kotlin.math.abs(startOf(candidate) - anchorTime) <= LEGACY_CONTEXT_MS
                }
            }
            .filter { it.isNotEmpty() }
            .distinctBy { group -> group.map { it.file.absolutePath }.sorted() }
            .map(::eventGroup)

        return (tagged + legacy).sortedByDescending { it.startedAtEpochMs }
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

    private fun startOf(segment: Segment): Long =
        segment.sidecar.startedAtEpochMs ?: segment.file.lastModified()
}
