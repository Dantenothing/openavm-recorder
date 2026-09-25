package com.dante.zeekrcapabilitylab.preflight.continuous

import com.dante.zeekrcapabilitylab.service.recorder.KeyframeSegmentPlanner
import org.junit.Assert.*
import org.junit.Test

class SyntheticSegmentVerifierTest {
    private val times = LongArray(120) { 9_000_000L + it * 33_333L }

    @Test fun seeingAllExpectedFramesDoesNotPassBeforeTheWholeFileManifestIsFinished() {
        val verifier = SyntheticSegmentVerifier(longArrayOf(100), 1, 100)
        verifier.beginSegment(100, true)
        verifier.decoded(DecodedProbeFrame(0, 0))
        verifier.endSegment(true)
        assertEquals(SyntheticSequenceStatus.INCOMPLETE, verifier.result().status)
    }

    private fun fourFiles(
        eof: Boolean = true,
        transform: (Int, DecodedProbeFrame) -> DecodedProbeFrame? = { _, frame -> frame },
    ): SyntheticSequenceResult {
        val verifier = SyntheticSegmentVerifier(times, expectedSegments = 4, maximumInputGapUs = 66_667)
        repeat(4) { segment ->
            val offset = segment * 30
            verifier.beginSegment(times[offset], firstSampleIsKeyFrame = true)
            for (frame in offset until offset + 30) {
                transform(frame, DecodedProbeFrame(frame.toLong(), times[frame] - times[offset]))?.let(verifier::decoded)
            }
            verifier.endSegment(decoderReachedEos = eof)
        }
        verifier.finishRun()
        return verifier.result()
    }

    @Test fun threeBoundariesWithDecodedFrameIdsAndFilePtsCanPassOnlySequenceScope() {
        val result = fourFiles()
        assertEquals(SyntheticSequenceStatus.PASS, result.status)
        assertEquals("SYNTHETIC_DECODED_SEQUENCE_ONLY", result.scope)
        assertEquals(4, result.completedSegments)
        assertEquals(120L, result.decodedFrames)
        assertEquals(33_333L, result.maximumInputGapUs)
        assertEquals(33_333L, result.maximumBoundaryGapUs)
    }

    @Test fun droppingOrDuplicatingAFrameAtTheBoundaryFailsEvenIfFileDurationsLookNormal() {
        val missing = fourFiles { index, frame -> frame.takeUnless { index == 29 } }
        assertEquals(SyntheticSequenceStatus.FAIL, missing.status)
        assertTrue(SyntheticSequenceIssue.FRAME_ID_MISMATCH in missing.issues)
        val duplicate = fourFiles { index, frame -> if (index == 30) frame.copy(sourceFrameId = 29) else frame }
        assertEquals(SyntheticSequenceStatus.FAIL, duplicate.status)
    }

    @Test fun timestampResetInsideAFileAndWrongFileOriginFail() {
        val reset = fourFiles { index, frame -> if (index == 65) frame.copy(filePtsUs = 0) else frame }
        assertEquals(SyntheticSequenceStatus.FAIL, reset.status)
        assertTrue(SyntheticSequenceIssue.PTS_MISMATCH in reset.issues)
        val verifier = SyntheticSegmentVerifier(times, 1, 66_667)
        verifier.beginSegment(times[0] + 10, true)
        verifier.decoded(DecodedProbeFrame(0, 0))
        assertTrue(SyntheticSequenceIssue.PTS_MISMATCH in verifier.result().issues)
    }

    @Test fun absentPixelIdsCannotPassUsingContainerTimestampsAlone() {
        val result = fourFiles { _, frame -> frame.copy(sourceFrameId = null) }
        assertEquals(SyntheticSequenceStatus.FAIL, result.status)
        assertTrue(SyntheticSequenceIssue.FRAME_ID_UNREADABLE in result.issues)
    }

    @Test fun missingDecoderEosOrUnfinishedRunIsIncomplete() {
        assertEquals(SyntheticSequenceStatus.INCOMPLETE, fourFiles(eof = false).status)
        val verifier = SyntheticSegmentVerifier(times, 4, 66_667)
        assertEquals(SyntheticSequenceStatus.INCOMPLETE, verifier.result().status)
        verifier.beginSegment(times[0], true)
        verifier.decoded(DecodedProbeFrame(0, 0))
        assertEquals(SyntheticSequenceStatus.INCOMPLETE, verifier.result().status)
    }

    @Test fun nonKeyOrEmptyFilesCannotPass() {
        val verifier = SyntheticSegmentVerifier(times, 1, 66_667)
        verifier.beginSegment(times[0], false)
        verifier.endSegment(true)
        assertEquals(SyntheticSequenceStatus.FAIL, verifier.result().status)
        assertTrue(SyntheticSequenceIssue.FIRST_SAMPLE_NOT_KEY_FRAME in verifier.result().issues)
        assertTrue(SyntheticSequenceIssue.EMPTY_SEGMENT in verifier.result().issues)
    }

    @Test fun fullyDecodedManifestWithMissingInputTailFails() {
        val result = fourFiles { index, frame -> frame.takeUnless { index >= 115 } }
        assertEquals(SyntheticSequenceStatus.FAIL, result.status)
        assertTrue(SyntheticSequenceIssue.INPUT_TAIL_NOT_DECODED in result.issues)
        assertEquals(115L, result.decodedFrames)
    }

    @Test fun keyframePlannerMayDelayCutButMustPreserveEveryFrameAcrossItsBoundaries() {
        val verifier = SyntheticSegmentVerifier(times, 4, 66_667)
        val planner = KeyframeSegmentPlanner()
        var files = 0
        for (index in times.indices) {
            if (index == 31 || index == 61 || index == 91) planner.requestCut()
            val key = index % 12 == 0
            val placement = planner.accept(times[index], key)!!
            if (index == 0 || placement.cutBefore) {
                if (index != 0) verifier.endSegment(true)
                verifier.beginSegment(times[index], key)
                files++
            }
            verifier.decoded(DecodedProbeFrame(index.toLong(), placement.ptsUs))
        }
        verifier.endSegment(true)
        verifier.finishRun()
        assertEquals(4, files)
        assertEquals(SyntheticSequenceStatus.PASS, verifier.result().status)
    }

    @Test fun aGapInSourceTimeCannotBeHiddenByRenumberingFrames() {
        val gapped = times.copyOf().also { for (i in 60 until it.size) it[i] += 2_000_000 }
        val verifier = SyntheticSegmentVerifier(gapped, 1, 66_667)
        assertEquals(SyntheticSequenceStatus.FAIL, verifier.result().status)
        assertTrue(SyntheticSequenceIssue.INPUT_TIMESTAMP_GAP in verifier.result().issues)
    }

    @Test fun ledgerIsBoundedAndDefensivelyCopied() {
        val mutable = longArrayOf(100, 200)
        val verifier = SyntheticSegmentVerifier(mutable, 1, 100)
        mutable[0] = 999
        verifier.beginSegment(100, true)
        verifier.decoded(DecodedProbeFrame(0, 0))
        verifier.decoded(DecodedProbeFrame(1, 100))
        verifier.endSegment(true)
        verifier.finishRun()
        assertEquals(SyntheticSequenceStatus.PASS, verifier.result().status)
        assertThrows(IllegalArgumentException::class.java) {
            SyntheticSegmentVerifier(LongArray(SyntheticSegmentVerifier.MAX_INPUT_FRAMES + 1) { it.toLong() }, 1, 1)
        }
        assertThrows(IllegalArgumentException::class.java) { SyntheticSegmentVerifier(longArrayOf(1, 1), 1, 1) }
    }

    @Test fun invalidNativeEvidenceIsReportedWithoutTimestampOverflowOrUnboundedErrors() {
        val verifier = SyntheticSegmentVerifier(longArrayOf(100), 1, 100)
        verifier.beginSegment(Long.MAX_VALUE, true)
        verifier.decoded(DecodedProbeFrame(0, Long.MAX_VALUE))
        repeat(1000) { verifier.decoded(DecodedProbeFrame(-1, -1)) }
        verifier.endSegment(true)
        assertEquals(SyntheticSequenceStatus.FAIL, verifier.result().status)
        assertTrue(SyntheticSequenceIssue.PTS_MISMATCH in verifier.result().issues)
        assertTrue(SyntheticSequenceIssue.EXTRA_DECODED_FRAME in verifier.result().issues)
        assertTrue(verifier.result().issues.size <= SyntheticSequenceIssue.entries.size)
    }

    @Test fun uninspectedFilesCannotBeIgnoredEvenWhenAllExpectedFrameIdsWereAlreadySeen() {
        val verifier = SyntheticSegmentVerifier(longArrayOf(100), 2, 100)
        verifier.beginSegment(100, true)
        verifier.decoded(DecodedProbeFrame(0, 0))
        verifier.endSegment(true)
        verifier.finishRun()
        assertEquals(SyntheticSequenceStatus.INCOMPLETE, verifier.result().status)
        assertTrue(SyntheticSequenceIssue.FILE_MANIFEST_INCOMPLETE in verifier.result().issues)
    }

    @Test fun sealedRunRejectsLateEvidenceAndExtraFilesFail() {
        val verifier = SyntheticSegmentVerifier(longArrayOf(100), 1, 100)
        verifier.beginSegment(100, true)
        verifier.decoded(DecodedProbeFrame(0, 0))
        verifier.endSegment(true)
        verifier.beginSegment(100, true)
        verifier.decoded(DecodedProbeFrame(0, 0))
        verifier.endSegment(true)
        verifier.finishRun()
        assertEquals(SyntheticSequenceStatus.FAIL, verifier.result().status)
        assertTrue(SyntheticSequenceIssue.EXTRA_SEGMENT in verifier.result().issues)
        assertThrows(IllegalStateException::class.java) { verifier.beginSegment(100, true) }
        assertThrows(IllegalStateException::class.java) { verifier.decoded(DecodedProbeFrame(1, 100)) }
    }
}
