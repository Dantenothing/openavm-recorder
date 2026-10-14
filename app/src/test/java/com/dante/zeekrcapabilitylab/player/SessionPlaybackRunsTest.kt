package com.dante.zeekrcapabilitylab.player

import io.github.dantenothing.avmtransfer.protocol.SessionVideoSegment
import org.junit.Assert.*
import org.junit.Test

class SessionPlaybackRunsTest {
    private fun clip(id: String, start: Long, duration: Long = 60_000, channel: String = "SURROUND", session: String = "s") =
        SessionVideoSegment(id, channel, session, start, start + duration, duration)

    @Test fun continuousMinutesShareOnePlaylistPerChannel() {
        val clips = (0..11).flatMap { n -> listOf(clip("s$n", n * 60_000L), clip("c$n", n * 60_000L, channel = "FRONT_CABIN")) }
        val runs = sessionPlaybackRuns(clips) { _, _ -> true }
        assertEquals(listOf(12, 12), runs.map { it.size })
        assertEquals(24, runs.flatten().size)
    }
    @Test fun evenASmallRealGapIsNotConcatenatedAway() {
        assertEquals(listOf(1, 1), sessionPlaybackRuns(listOf(clip("a", 0), clip("b", 60_001))) { _, _ -> true }.map { it.size })
    }
    @Test fun overlapIsNotAnAutomaticFileBoundary() {
        assertEquals(2, sessionPlaybackRuns(listOf(clip("a", 0), clip("b", 59_999))) { _, _ -> true }.size)
    }
    @Test fun changedRasterAndDifferentSessionMustRecreateTheDecoder() {
        assertEquals(2, sessionPlaybackRuns(listOf(clip("a", 0), clip("b", 60_000))) { _, _ -> false }.size)
        assertEquals(2, sessionPlaybackRuns(listOf(clip("a", 0), clip("b", 60_000, session = "other"))) { _, _ -> true }.size)
    }
    @Test fun lookupOrderDoesNotChangePlaybackOrder() {
        assertEquals(listOf("a", "b", "c"), sessionPlaybackRuns(listOf(clip("c", 120_000), clip("a", 0), clip("b", 60_000))) { _, _ -> true }.single().map { it.id })
    }
}
