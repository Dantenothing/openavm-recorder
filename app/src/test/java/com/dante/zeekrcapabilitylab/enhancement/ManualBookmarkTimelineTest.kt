package com.dante.zeekrcapabilitylab.enhancement
import com.dante.zeekrcapabilitylab.player.ManualBookmarkTimeline
import org.junit.Assert.*
import org.junit.Test
class ManualBookmarkTimelineTest {
    @Test fun neverPlacesEventsInMissingFootageOrUnknownDurations() {
        assertTrue(ManualBookmarkTimeline.markers(null, 7000, listOf(301000)).isEmpty())
        assertTrue(ManualBookmarkTimeline.markers(1000, null, listOf(301000)).isEmpty())
        assertTrue(ManualBookmarkTimeline.markers(1000, 7000, listOf(301000)).isEmpty())
        val result = ManualBookmarkTimeline.markers(1000, 7000, listOf(999, 1000, 4500, 4500, 8000))
        assertEquals(listOf(0L, 3500L), result.map { it.positionMs })
    }
}
