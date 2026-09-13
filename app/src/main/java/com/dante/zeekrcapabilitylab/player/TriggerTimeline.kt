package com.dante.zeekrcapabilitylab.player

import com.dante.zeekrcapabilitylab.service.recorder.VideoTriggerMarker

data class TriggerTimelineSegment(val durationMs: Long, val markers: List<VideoTriggerMarker>)
data class PlaybackTrigger(val segmentIndex: Int, val localPositionMs: Long, val positionMs: Long, val marker: VideoTriggerMarker)

/** Compose and MediaPlayer both use the sum of actual playable segment durations.
 * Missing segments are absent from this list; gaps must never become invented footage. */
object TriggerTimeline {
    fun assemble(segments: List<TriggerTimelineSegment>): List<PlaybackTrigger> {
        val result = mutableListOf<PlaybackTrigger>()
        var offset = 0L
        for ((index, segment) in segments.withIndex()) {
            // Unknown preceding duration makes all subsequent global positions untrustworthy.
            if (segment.durationMs <= 0L || offset > Long.MAX_VALUE - segment.durationMs) break
            segment.markers.filter { it.positionMs >= 0 && it.positionMs < segment.durationMs }
                .distinctBy { Triple(it.positionMs, it.type, it.epochMs) }
                .take(64).forEach { result += PlaybackTrigger(index, it.positionMs, offset + it.positionMs, it) }
            offset += segment.durationMs
        }
        return result.sortedBy { it.positionMs }
    }

    fun active(markers: List<PlaybackTrigger>, positionMs: Long): PlaybackTrigger? =
        markers.filter { positionMs >= it.positionMs && positionMs - it.positionMs <= 5_000 }
            .maxByOrNull { it.positionMs }
}
