package com.dante.zeekrcapabilitylab.recorder

import com.dante.zeekrcapabilitylab.service.recorder.EncodedFileClock
import com.dante.zeekrcapabilitylab.service.recorder.ObservedCodecClock
import io.github.dantenothing.avmtransfer.protocol.VideoClockAnchor
import org.junit.Assert.*
import org.junit.Test

class ObservedCodecClockTest {
    @Test fun codecCallbackDelayAndWallClockJumpNeverReanchorLaterSegments() {
        val clock = ObservedCodecClock("run")
        val first = clock.observe(9_000_000, 11_500_000_123, 2_000_000)
        val delayed = clock.observe(10_000_000, 13_900_000_000, 9_000_000)
        assertEquals(12_500_000_123, delayed.elapsedNs)
        assertEquals(first.epochMs + 1_000, delayed.epochMs)
        assertEquals(123L, delayed.elapsedNs % 1_000)
    }

    @Test fun rotationEndsAtNextKeyframeAndPreservesNanosecondContinuity() {
        val clock = ObservedCodecClock("one-run")
        val before = EncodedFileClock(clock.runId)
        before.written(clock.observe(9_000_000, 11_500_000_123, 2_000_000))
        before.written(clock.observe(9_033_333, 11_560_000_000, 2_000_060))
        val next = clock.observe(9_066_667, 11_800_000_000, 2_000_300)
        val previous = requireNotNull(before.coverage(next.ptsUs))
        val after = EncodedFileClock(clock.runId).also { it.written(next) }
        val following = requireNotNull(after.coverage())
        assertEquals(66_667L, previous.durationUs)
        assertEquals(next.ptsUs, previous.timeline.endExclusivePtsUs)
        assertEquals(previous.timeline.endExclusivePtsUs, following.timeline.firstPtsUs)
        assertEquals(previous.endElapsedNs, following.anchor.elapsedRealtimeNs)
        assertEquals(previous.timeline.runId, following.timeline.runId)
        assertNull(previous.anchor.uncertaintyMs)
        assertTrue(previous.timeline.validate().isEmpty())
    }

    @Test fun terminalDurationUsesObservedThirtyFpsEvenIfEncoderRequestedFifteen() {
        val clock = ObservedCodecClock("run")
        val file = EncodedFileClock(clock.runId)
        listOf(0L, 33_333L, 66_667L, 100_000L).forEach { pts ->
            file.written(clock.observe(pts, 10_000_000_000 + pts * 1_000, 1_000))
        }
        val result = requireNotNull(file.coverage())
        assertEquals(133_333L, result.timeline.endExclusivePtsUs)
        assertEquals(133_333L, result.durationUs)
        assertEquals(10_133_333_000L, result.endElapsedNs)
    }

    @Test fun terminalCadenceWindowIsBoundedAndIgnoresLongSourceInterruption() {
        val clock = ObservedCodecClock("run")
        val file = EncodedFileClock(clock.runId)
        val pts = (0..20).map { it * 33_333L } + 5_000_000L
        pts.forEach { file.written(clock.observe(it, 10_000_000_000 + it * 1_000, 1_000)) }
        assertEquals(5_033_333L, file.coverage()!!.timeline.endExclusivePtsUs)
        assertEquals(4_333_340L, file.maximumGapUs)
    }

    @Test fun oneFrameUsesExplicitMinimalDurationAndEmptyFileHasNoClock() {
        val file = EncodedFileClock("run")
        assertNull(file.coverage())
        file.written(ObservedCodecClock("run").observe(10_000, 1_000_000_000, 100))
        assertEquals(1_000L, file.coverage()!!.durationUs)
        assertEquals(1L, file.frames)
    }

    @Test fun invalidClockAndBoundaryFailBeforePublishingInventedTiming() {
        val clock = ObservedCodecClock("run")
        val file = EncodedFileClock(clock.runId)
        file.written(clock.observe(10_000, 1_000_000_000, 100))
        assertTrue(runCatching { clock.observe(10_000, 2_000_000_000, 200) }.isFailure)
        assertTrue(runCatching { file.coverage(10_000) }.isFailure)
        assertTrue(runCatching { ObservedCodecClock().observe(-1, 0, 1) }.isFailure)
        assertTrue(runCatching { ObservedCodecClock().observe(0, VideoClockAnchor.MAX_TIME_NS + 1, 1) }.isFailure)
    }

    @Test fun differentEncoderOwnersNeverShareContinuityProof() {
        assertNotEquals(ObservedCodecClock().runId, ObservedCodecClock().runId)
    }
}
