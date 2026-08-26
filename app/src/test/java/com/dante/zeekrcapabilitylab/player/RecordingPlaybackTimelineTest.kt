package com.dante.zeekrcapabilitylab.player

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingPlaybackTimelineTest {
    @Test
    fun mapsInternalSegmentsOntoOneTimeline() {
        val timeline = RecordingPlaybackTimeline(listOf(60_000L, 60_000L, 28_000L))

        assertEquals(148_000L, timeline.totalDurationMs)
        assertEquals(RecordingPlaybackPosition(0, 59_999L), timeline.locate(59_999L))
        assertEquals(RecordingPlaybackPosition(1, 0L), timeline.locate(60_000L))
        assertEquals(RecordingPlaybackPosition(2, 28_000L), timeline.locate(148_000L))
        assertEquals(65_000L, timeline.globalPosition(1, 5_000L))
    }

    @Test
    fun clampsInvalidDurationsAndPositions() {
        val timeline = RecordingPlaybackTimeline(listOf(-1L, 10_000L))

        assertEquals(10_000L, timeline.totalDurationMs)
        assertEquals(RecordingPlaybackPosition(1, 10_000L), timeline.locate(99_000L))
        assertEquals(0L, timeline.globalPosition(0, 5_000L))
    }
}
