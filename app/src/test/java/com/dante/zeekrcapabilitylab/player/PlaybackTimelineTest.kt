package com.dante.zeekrcapabilitylab.player

import org.junit.Assert.*
import org.junit.Test

class PlaybackTimelineTest {
    @Test fun seekAtMinuteBoundarySelectsNextItemWithoutOpeningANewPlaylist() {
        val timeline = PlaybackTimeline(3)
        listOf(60_000L, 60_000L, 7_000L).forEachIndexed(timeline::hint)
        assertEquals(PlaybackSeek(0, 59_999), timeline.seek(59_999))
        assertEquals(PlaybackSeek(1, 0), timeline.seek(60_000))
        assertEquals(PlaybackSeek(2, 4_000), timeline.seek(124_000))
        assertEquals(124_000L, timeline.position(2, 4_000))
    }
    @Test fun lateMetadataCannotMoveConfirmedTrackBoundariesBackwards() {
        val timeline = PlaybackTimeline(2)
        timeline.hint(0, 60_000); timeline.hint(1, 60_000)
        timeline.confirm(0, 59_500); timeline.hint(0, 60_000)
        assertEquals(119_500L, timeline.total())
        assertEquals(PlaybackSeek(1, 500), timeline.seek(60_000))
    }
    @Test fun unknownOrMissingItemsAreNotSilentlySkippedDuringGlobalSeek() {
        val timeline = PlaybackTimeline(3)
        timeline.hint(0, 60_000); timeline.hint(2, 60_000)
        timeline.confirm(1, -9223372036854775807L)
        assertEquals(PlaybackSeek(1, 0), timeline.seek(80_000))
        timeline.confirm(1, 5_000)
        assertEquals(PlaybackSeek(2, 15_000), timeline.seek(80_000))
    }
    @Test fun endAndNegativeSeekClampAndEmptyPlaylistIsSafe() {
        val timeline = PlaybackTimeline(1)
        timeline.confirm(0, 1_250)
        assertEquals(PlaybackSeek(0, 0), timeline.seek(-100))
        assertEquals(PlaybackSeek(0, 1_250), timeline.seek(Long.MAX_VALUE))
        assertNull(PlaybackTimeline(0).seek(1))
    }
    @Test fun timeLapseUsesTrackDurationInsteadOfTheWallClockDuration() {
        val timeline = PlaybackTimeline(2)
        timeline.hint(0, 2_000); timeline.hint(1, 2_000)
        timeline.confirm(0, 2_100)
        assertEquals(PlaybackSeek(1, 100), timeline.seek(2_200))
        assertEquals(listOf(2_100L, 2_000L), timeline.durations())
    }
}
