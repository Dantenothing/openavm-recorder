package com.dante.zeekrcapabilitylab.player

import io.github.dantenothing.avmtransfer.protocol.*
import org.junit.Assert.*
import org.junit.Test

class SessionSelectionTest {
    private val session = "00000000-0000-4000-8000-000000000001"
    private fun segment(id: String, channel: String, start: Long, end: Long) = SessionVideoSegment(id, channel, session, start, end, end - start)
    private val timeline = SessionPlaybackTimeline(session, listOf(
        segment("a", "SURROUND", 1_000, 31_000), segment("b", "SURROUND", 31_000, 61_000),
        segment("c", "SURROUND", 61_000, 91_000), segment("cabin-a", "FRONT_CABIN", 1_150, 31_150),
        segment("cabin-b", "FRONT_CABIN", 31_150, 61_150), segment("ir", "REAR_CABIN", 1_200, 31_200)), 1_000)
    @Test fun clipRailDoesNotTripleCountParallelFiles() {
        assertEquals(listOf("a", "b", "c"), SessionSelection.clips(timeline).map { it.id })
    }
    @Test fun disjointSelectionsStayDisjoint() {
        assertEquals(listOf(SessionSelectionWindow(0, 30_000), SessionSelectionWindow(60_000, 90_000)), SessionSelection.windows(timeline, setOf("a", "c")))
    }
    @Test fun touchingSelectionsMergeButUnknownIdsAreIgnored() {
        assertEquals(listOf(SessionSelectionWindow(0, 60_000)), SessionSelection.windows(timeline, setOf("a", "b", "unknown")))
    }
    @Test fun standardSelectionCannotIncludeCabinOrNextSurroundFile() {
        val windows = SessionSelection.windows(timeline, setOf("a"))
        assertEquals(listOf("a"), SessionSelection.files(timeline, windows, setOf("SURROUND")))
        assertEquals(setOf("a", "cabin-a", "ir"), SessionSelection.files(timeline, windows, timeline.channelIds.toSet()).toSet())
    }
    @Test fun cabinBoundaryOverlapIsIncludedAsAnOriginalFileNotSilentlyTrimmed() {
        val windows = SessionSelection.windows(timeline, setOf("b"))
        assertEquals(setOf("cabin-a", "cabin-b"), SessionSelection.files(timeline, windows, setOf("FRONT_CABIN")).toSet())
    }
    @Test fun emptySelectionNeverMeansSendAll() {
        assertTrue(SessionSelection.files(timeline, emptyList(), timeline.channelIds.toSet()).isEmpty())
    }
}
