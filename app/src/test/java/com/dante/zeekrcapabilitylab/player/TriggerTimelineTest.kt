package com.dante.zeekrcapabilitylab.player

import com.dante.zeekrcapabilitylab.service.recorder.VideoTriggerMarker
import org.junit.Assert.*
import org.junit.Test

class TriggerTimelineTest {
    private fun marker(ms: Long, type: String = "VISUAL_RISK") = VideoTriggerMarker(ms, type)

    @Test fun unequalPlayableChunksMapToTheSameLocationsAsPlayerSeeking() {
        val points = TriggerTimeline.assemble(listOf(
            TriggerTimelineSegment(17_125, listOf(marker(4_000))),
            TriggerTimelineSegment(61_030, listOf(marker(12_040), marker(59_000, "MANUAL"))),
            TriggerTimelineSegment(25_000, listOf(marker(100))),
        ))
        assertEquals(listOf(4_000L, 29_165L, 76_125L, 78_255L), points.map { it.positionMs })
        assertEquals(listOf(0, 1, 1, 2), points.map { it.segmentIndex })
        assertEquals(listOf(4_000L, 12_040L, 59_000L, 100L), points.map { it.localPositionMs })
    }

    @Test fun unknownPrecedingDurationDoesNotInventLaterGlobalPositions() {
        val points = TriggerTimeline.assemble(listOf(
            TriggerTimelineSegment(17_000, listOf(marker(5_000))),
            TriggerTimelineSegment(0, listOf(marker(3_000))),
            TriggerTimelineSegment(60_000, listOf(marker(4_000))),
        ))
        assertEquals(listOf(5_000L), points.map { it.positionMs })
    }

    @Test fun invalidAndDuplicateMarkersAreOmittedButDistinctTriggerTypesRemain() {
        val points = TriggerTimeline.assemble(listOf(TriggerTimelineSegment(60_000,
            listOf(marker(-1), marker(60_000), marker(Long.MAX_VALUE), marker(5_000),
                marker(5_000), marker(5_000, "MANUAL"), marker(0)))))
        assertEquals(listOf(0L, 5_000L, 5_000L), points.map { it.positionMs })
        assertEquals(listOf("VISUAL_RISK", "VISUAL_RISK", "MANUAL"), points.map { it.marker.type })
    }

    @Test fun highlightFollowsMostRecentTriggerAndClearsWhenSeekingAway() {
        val points = TriggerTimeline.assemble(listOf(
            TriggerTimelineSegment(60_000, listOf(marker(5_000), marker(8_000, "MANUAL")))))
        assertNull(TriggerTimeline.active(points, 4_999))
        assertEquals(points.first(), TriggerTimeline.active(points, 5_000))
        assertEquals(points.last(), TriggerTimeline.active(points, 9_000))
        assertEquals(points.last(), TriggerTimeline.active(points, 13_000))
        assertNull(TriggerTimeline.active(points, 13_001))
        assertNull(TriggerTimeline.active(points, 0))
    }

    @Test fun corruptCumulativeDurationCannotOverflowIntoAnotherSegment() {
        val points = TriggerTimeline.assemble(listOf(
            TriggerTimelineSegment(Long.MAX_VALUE, listOf(marker(1))),
            TriggerTimelineSegment(20, listOf(marker(1)))))
        assertEquals(listOf(1L), points.map { it.positionMs })
    }
}
