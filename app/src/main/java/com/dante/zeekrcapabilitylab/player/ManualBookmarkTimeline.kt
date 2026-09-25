package com.dante.zeekrcapabilitylab.player

import com.dante.zeekrcapabilitylab.service.recorder.*

/** Approximate wall-time bookmark only inside known playable normal footage; never bridge a missing interval. */
object ManualBookmarkTimeline {
    fun markers(start: Long?, duration: Long?, events: List<Long>): List<VideoTriggerMarker> {
        if (start == null || start <= 0 || duration == null || duration <= 0) return emptyList()
        return events.distinct().filter { it >= start && it - start < duration }
            .map { VideoTriggerMarker(it - start, "MANUAL", it) }
    }
    fun from(sidecar: SegmentSidecar?, events: List<Long> = emptyList()): List<VideoTriggerMarker> {
        if (sidecar == null || sidecar.recordingMode != RecordingMode.NORMAL) return emptyList()
        return markers(sidecar.startedAtEpochMs, sidecar.actualTrack?.durationMs,
            events + listOfNotNull(sidecar.eventRequestedAtEpochMs))
    }
}
