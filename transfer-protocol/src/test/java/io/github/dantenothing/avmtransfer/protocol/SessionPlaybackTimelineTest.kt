package io.github.dantenothing.avmtransfer.protocol

import org.junit.Assert.*
import org.junit.Test

class SessionPlaybackTimelineTest {
    private fun segment(id: String, channel: String = "SURROUND", start: Long = 1_000, duration: Long = 1_000,
        anchor: VideoClockAnchor? = null) = SessionVideoSegment(id, channel, "s", start, start + duration, duration, anchor)

    @Test fun parallelChannelsDoNotTripleSessionDuration() {
        val timeline = SessionPlaybackTimeline("s", listOf(segment("s"), segment("f", "FRONT_CABIN"), segment("r", "REAR_CABIN")))
        assertEquals(1_000L, timeline.durationMs)
        assertEquals(3, timeline.channelIds.size)
        assertEquals("f", timeline.seek("FRONT_CABIN", 400)!!.segmentId)
        assertEquals(400L, timeline.seek("REAR_CABIN", 400)!!.mediaPositionMs)
    }

    @Test fun anchorsPreserveFirstFrameDelayAndCrossChannelPosition() {
        val anchor = VideoClockAnchor(elapsedRealtimeNs = 1_250_000_000, source = "CAMERA_REALTIME")
        val timeline = SessionPlaybackTimeline("s", listOf(segment("s"), segment("f", "FRONT_CABIN", anchor = anchor)), 1_000)
        assertNull(timeline.seek("FRONT_CABIN", 249))
        assertEquals(0L, timeline.seek("FRONT_CABIN", 250)!!.mediaPositionMs)
        assertEquals(650L, timeline.mediaToSession("f", 400))
        assertEquals(650L, timeline.seek("SURROUND", 650)!!.mediaPositionMs)
        assertEquals(1_250L, timeline.durationMs)
    }

    @Test fun keyframeRotationBoundarySelectsFollowingFile() {
        val timeline = SessionPlaybackTimeline("s", listOf(segment("a"), segment("b", start = 2_000)))
        assertEquals("a", timeline.seek("SURROUND", 999)!!.segmentId)
        assertEquals(SessionVideoSeek("b", 0, 1_000), timeline.seek("SURROUND", 1_000))
        assertEquals(listOf(SessionVideoSlice("a", 500, 1_000), SessionVideoSlice("b", 0, 500)),
            timeline.range("SURROUND", 500, 1_500))
        assertNull(timeline.seek("SURROUND", 2_000))
    }

    @Test fun gapsAndOverlapsNeverBorrowOrSilentlyDropVideo() {
        val gap = SessionPlaybackTimeline("s", listOf(segment("a"), segment("b", start = 2_500)))
        assertNull(gap.seek("SURROUND", 1_200))
        assertNull(gap.range("SURROUND", 500, 2_000))
        val overlap = SessionPlaybackTimeline("s", listOf(segment("a"), segment("b", start = 1_500)))
        assertNull(overlap.seek("SURROUND", 700))
        assertNull(overlap.range("SURROUND", 200, 1_100))
        assertNull(overlap.seek("FRONT_CABIN", 0))
    }

    @Test fun mixedSessionsClockResetsDuplicateIdsAndUnknownDurationsAreExcluded() {
        val timeline = SessionPlaybackTimeline("s", listOf(segment("a"), segment("b").copy(recordingSessionId = "other"),
            segment("c").copy(endElapsedMs = 0), segment("d", duration = 0), segment("e"), segment("e")))
        assertEquals(listOf("a"), timeline.segments.map { it.id })
        assertEquals(5, timeline.rejectedSegments)
        assertNull(timeline.mediaToSession("a", 1_001))
    }

    @Test fun outOfDomainAndLargeAnchorsCannotOverflow() {
        assertNull(VideoClockAnchor(elapsedRealtimeNs = Long.MAX_VALUE, source = "CAMERA_REALTIME").elapsedAtMediaMs(1))
        assertNull(VideoClockAnchor(elapsedRealtimeNs = 10, source = "UNKNOWN").elapsedAtMediaMs(1))
        val future = VideoClockAnchor(elapsedRealtimeNs = 99_000_000_000, source = "CALLBACK_OBSERVED")
        assertFalse(segment("a", anchor = future).valid())
        assertFalse(segment("a", anchor = future.copy(uncertaintyMs = -1)).valid())
    }

    @Test fun sourcePtsAnchorCanReferToNonzeroLocalPositionWithoutWallClock() {
        val anchor = VideoClockAnchor(mediaPositionUs = 250_000, elapsedRealtimeNs = 1_250_000_000, source = "CODEC_OUTPUT_OBSERVED")
        assertEquals(1_000L, anchor.elapsedAtMediaMs(0))
        assertEquals(2_000L, anchor.elapsedAtMediaMs(1_000))
        assertNull(anchor.elapsedAtMediaMs(-1))
        val timeline = SessionPlaybackTimeline("s", listOf(segment("a", anchor = anchor)))
        assertEquals(1_000L, timeline.originElapsedMs)
        assertNull(anchor.uncertaintyMs)
    }

    private fun continuous(first: Long, end: Long, run: String = "encoder-run") =
        ContinuousSegmentTimeline(runId = run, firstPtsUs = first, lastPtsUs = end - 33_333,
            endExclusivePtsUs = end, frames = 30, startsWithKeyFrame = true)

    private fun proven(id: String, start: Long, duration: Long, pts: ContinuousSegmentTimeline,
        channel: String = "SURROUND", source: String = "CALLBACK_OBSERVED") =
        segment(id, channel, start, duration, VideoClockAnchor(elapsedRealtimeNs = start * 1_000_000, source = source))
            .copy(continuousTimeline = pts)

    @Test fun sourcePtsFractionalCarryRepairsOneMillisecondGapWithoutChangingContainerFacts() {
        val a = proven("a", 1_000, 1_000, continuous(500, 1_001_000))
        val b = proven("b", 2_001, 1_000, continuous(1_001_000, 2_001_000))
        val timeline = SessionPlaybackTimeline("s", listOf(a, b))
        assertEquals(1, timeline.quantizedBoundaries)
        assertEquals(1_000L, timeline.segments.first().mediaDurationMs)
        assertEquals(a.anchor, timeline.segments.first().anchor)
        assertEquals(2_000L, a.mediaEndElapsedMs) // Caller-owned original was not mutated.
        assertEquals(1_001L, timeline.mediaToSession("a", 1_000))
        assertEquals("a", timeline.seek("SURROUND", 1_000)!!.segmentId)
        assertEquals(999L, timeline.seek("SURROUND", 1_000)!!.mediaPositionMs)
        assertEquals("b", timeline.seek("SURROUND", 1_001)!!.segmentId)
        assertEquals(listOf(SessionVideoSlice("a", 500, 1_000), SessionVideoSlice("b", 0, 500)),
            timeline.range("SURROUND", 500, 1_501))
        assertNull(timeline.range("SURROUND", 1_000, 1_001))
    }

    @Test fun containerCeilingOverlapUsesProvenCutAndRetainsWholeMediaSlice() {
        val a = proven("a", 1_000, 1_001, continuous(0, 1_000_500))
        val b = proven("b", 2_000, 1_000, continuous(1_000_500, 2_000_500))
        val timeline = SessionPlaybackTimeline("s", listOf(a, b))
        assertEquals(1, timeline.quantizedBoundaries)
        assertEquals("b", timeline.seek("SURROUND", 1_000)!!.segmentId)
        assertEquals(1_000L, timeline.mediaToSession("a", 1_001))
        assertEquals(1_001L, timeline.range("SURROUND", 0, 1_500)!!.first().toMediaMs)
    }

    @Test fun integerPtsCannotExplainEvenOneMillisecondGap() {
        val timeline = SessionPlaybackTimeline("s", listOf(proven("a", 1_000, 1_000, continuous(0, 1_000_000)),
            proven("b", 2_001, 1_000, continuous(1_000_000, 2_000_000))))
        assertEquals(0, timeline.quantizedBoundaries)
        assertNull(timeline.range("SURROUND", 0, 1_500))
    }

    @Test fun missingEvidenceChangedRunWrongPtsAndRealGapsStayStrict() {
        val a = proven("a", 1_000, 1_000, continuous(500, 1_001_000))
        val b = proven("b", 2_001, 1_000, continuous(1_001_000, 2_001_000))
        listOf(
            a.copy(continuousTimeline = null) to b,
            a to b.copy(continuousTimeline = b.continuousTimeline!!.copy(runId = "another-run")),
            a to b.copy(continuousTimeline = continuous(1_001_500, 2_001_500)),
            a to proven("b", 2_002, 1_000, continuous(1_001_000, 2_001_000)),
            a.copy(mediaDurationMs = 999) to b,
            a.copy(anchor = null) to b,
        ).forEach { (before, after) ->
            val timeline = SessionPlaybackTimeline("s", listOf(before, after))
            assertEquals(0, timeline.quantizedBoundaries)
            assertNull(timeline.range("SURROUND", 0, 1_500))
        }
    }

    @Test fun preciseCodecAnchorsRetainNanosecondFractionAndChannelsNeverProveEachOther() {
        val a = proven("a", 1_000, 1_000, continuous(123, 1_000_623), source = "CODEC_OUTPUT_OBSERVED")
            .copy(anchor = VideoClockAnchor(elapsedRealtimeNs = 1_000_800_000, source = "CODEC_OUTPUT_OBSERVED"))
        val b = proven("b", 2_001, 1_000, continuous(1_000_623, 2_000_623), source = "CODEC_OUTPUT_OBSERVED")
            .copy(anchor = VideoClockAnchor(elapsedRealtimeNs = 2_001_300_000, source = "CODEC_OUTPUT_OBSERVED"))
        assertEquals(1, SessionPlaybackTimeline("s", listOf(a, b)).quantizedBoundaries)
        assertEquals(0, SessionPlaybackTimeline("s", listOf(a, b.copy(channelId = "FRONT_CABIN"))).quantizedBoundaries)
    }

    @Test fun continuousPtsBoundsRejectOverflowBeforeArithmetic() {
        val huge = ContinuousSegmentTimeline(runId = "run", firstPtsUs = Long.MAX_VALUE - 10,
            lastPtsUs = Long.MAX_VALUE - 5, endExclusivePtsUs = Long.MAX_VALUE, frames = 2, startsWithKeyFrame = true)
        assertTrue(huge.validate().isNotEmpty())
        assertFalse(segment("a").copy(continuousTimeline = huge).valid())
        assertEquals(1, SessionPlaybackTimeline("s", listOf(segment("a").copy(continuousTimeline = huge))).rejectedSegments)
    }
}
