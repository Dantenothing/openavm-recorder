package com.dante.zeekrcapabilitylab.player

import io.github.dantenothing.avmtransfer.protocol.SessionPlaybackTimeline
import io.github.dantenothing.avmtransfer.protocol.SessionVideoSegment

internal data class SessionSelectionWindow(val fromMs: Long, val toMs: Long)

/** Whole original files are selected by overlap. GPS/HUD are independently clipped to these windows. */
internal object SessionSelection {
    fun clips(timeline: SessionPlaybackTimeline): List<SessionVideoSegment> {
        val primary = timeline.channelIds.firstOrNull() ?: return emptyList()
        val primaryFiles = timeline.segments.filter { it.channelId == primary }
        val extras = timeline.segments.filter { candidate -> candidate.channelId != primary && primaryFiles.none {
            it.mediaStartElapsedMs!! < candidate.mediaEndElapsedMs!! && it.mediaEndElapsedMs!! > candidate.mediaStartElapsedMs!!
        } }.distinctBy { it.mediaStartElapsedMs to it.mediaEndElapsedMs }
        return (primaryFiles + extras).sortedBy { it.mediaStartElapsedMs }
    }
    fun windows(timeline: SessionPlaybackTimeline, ids: Set<String>): List<SessionSelectionWindow> {
        val raw = clips(timeline).filter { it.id in ids }.map {
            SessionSelectionWindow(it.mediaStartElapsedMs!! - timeline.originElapsedMs, it.mediaEndElapsedMs!! - timeline.originElapsedMs)
        }.sortedBy { it.fromMs }
        return raw.fold(mutableListOf()) { list, next ->
            val last = list.lastOrNull()
            if (last != null && next.fromMs <= last.toMs) list[list.lastIndex] = last.copy(toMs = maxOf(last.toMs, next.toMs))
            else list += next
            list
        }
    }
    fun files(timeline: SessionPlaybackTimeline, windows: List<SessionSelectionWindow>, channels: Set<String>): List<String> =
        timeline.segments.filter { segment -> segment.channelId in channels && windows.any {
            segment.mediaStartElapsedMs!! < timeline.originElapsedMs + it.toMs && segment.mediaEndElapsedMs!! > timeline.originElapsedMs + it.fromMs
        } }.map { it.id }.distinct()
}
