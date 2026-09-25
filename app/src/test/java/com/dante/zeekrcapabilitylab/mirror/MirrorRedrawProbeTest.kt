package com.dante.zeekrcapabilitylab.mirror

import org.junit.Assert.*
import org.junit.Test

class MirrorRedrawProbeTest {
    @Test fun onlyStaleVisibleOutputWithFreshPreviewCaptureGetsOneProbePerSegment() {
        val probe = MirrorRedrawProbe()
        assertFalse(probe.tick(10, 0, true, true, 14_878, 0, 35))
        assertFalse(probe.tick(10, 1_999, true, true, 14_878, 1_999, 35))
        assertTrue(probe.tick(10, 2_000, true, true, 14_878, 2_000, 35))
        assertFalse(probe.tick(10, 6_000, true, true, 14_878, 6_000, 35))
        assertEquals(1, probe.attempts)
    }
    @Test fun stoppedHiddenMissingOrStaleCaptureEvidenceNeverRequestsDisplayWork() {
        for (mode in 0..4) {
            val probe = MirrorRedrawProbe()
            probe.tick(1, 0, true, true, 10, 0, 0)
            assertFalse(probe.tick(1, 3_000, mode != 0, mode != 1, 10,
                if (mode == 2) null else 3_000, when (mode) { 3 -> null; 4 -> 751; else -> 0 }))
            assertEquals(0, probe.attempts)
        }
    }
    @Test fun segmentChangesAndClockRollbackCannotSpendTheBudgetEarly() {
        val probe = MirrorRedrawProbe()
        probe.tick(1, 100, true, true, 10, 0, 0)
        assertFalse(probe.tick(2, 5_000, true, true, 10, 4_900, 0))
        assertFalse(probe.tick(2, 4_999, true, true, 10, 4_899, 0))
        assertFalse(probe.tick(2, 6_999, true, true, 10, 6_899, 0))
        assertTrue(probe.tick(2, 7_000, true, true, 10, 6_900, 0))
    }
    @Test fun threeNewFramesAreEvidenceOfResumptionButDoNotResetTheRunBudget() {
        val probe = MirrorRedrawProbe()
        for (segment in 1..4) {
            val start = segment * 10_000L
            val frames = segment * 100L
            probe.tick(segment, start, true, true, frames, 0, 35)
            assertEquals(segment <= 3, probe.tick(segment, start + 2_000, true, true, frames, 2_000, 35))
            probe.tick(segment, start + 2_100, true, true, frames + 1, 0, 35)
            assertEquals(minOf(segment - 1, 3), probe.framesResumed)
            probe.tick(segment, start + 2_200, true, true, frames + 3, 0, 35)
            assertEquals(minOf(segment, 3), probe.framesResumed)
        }
        assertEquals(3, probe.attempts)
    }
    @Test fun aLaterSegmentOrHiddenWindowCannotBeMisreportedAsRecovery() {
        val probe = MirrorRedrawProbe()
        probe.tick(1, 0, true, true, 10, 0, 0)
        probe.tick(1, 2_000, true, true, 10, 2_000, 0)
        probe.tick(1, 2_300, true, false, 14, 0, 0)
        probe.tick(2, 3_000, true, true, 100, 0, 0)
        assertEquals(0, probe.framesResumed)
    }
}
