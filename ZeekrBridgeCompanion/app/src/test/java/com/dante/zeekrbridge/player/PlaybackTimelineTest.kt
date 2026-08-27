package com.dante.zeekrbridge.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackTimelineTest {
    @Test
    fun mapsGlobalPositionAcrossSegmentBoundaries() {
        val timeline = PlaybackTimeline(listOf(60_000L, 30_000L, 90_000L))

        assertEquals(180_000L, timeline.totalDurationMs)
        assertEquals(PlaylistPosition(0, 59_999L), timeline.resolve(59_999L))
        assertEquals(PlaylistPosition(1, 0L), timeline.resolve(60_000L))
        assertEquals(PlaylistPosition(2, 10_000L), timeline.resolve(100_000L))
        assertEquals(100_000L, timeline.globalPosition(2, 10_000L))
    }

    @Test
    fun clampsSeeksAndSkipsZeroDurationEntries() {
        val timeline = PlaybackTimeline(listOf(0L, 60_000L, -1L))

        assertEquals(PlaylistPosition(1, 0L), timeline.resolve(-10L))
        assertEquals(PlaylistPosition(2, 0L), timeline.resolve(60_000L))
        assertEquals(60_000L, timeline.globalPosition(9, 99_000L))
    }
}
