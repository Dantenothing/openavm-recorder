package com.dante.zeekrcapabilitylab.preflight.continuous

import org.junit.Assert.*
import org.junit.Test

class CameraContainerTimingTest {
    // Variable camera timestamps can exercise Android's sub-100us STTS coalescing.
    private fun candidate(input:LongArray)=SyntheticSegmentVerifier(input,1,100_000,120,
        DecodedPtsContract.ANDROID_MP4_SUBFRAME_ADJUSTMENT)
    @Test fun completeFramesWithSubframeContainerAdjustmentRemainContinuous() {
        val v=candidate(longArrayOf(0,33_000,66_050,99_100))
        v.beginSegment(0,true)
        listOf(0L,33_000,66_000,99_100).forEachIndexed {i,p->v.decoded(DecodedProbeFrame(i.toLong(),p))}
        v.endSegment(true);v.finishRun()
        assertEquals(SyntheticSequenceStatus.PASS,v.result().status)
        assertEquals(50L,v.result().maximumPtsErrorUs)
        assertEquals(1,v.result().beyondStrictRoundingFrames)
    }
    @Test fun frameSizedTimingShiftMustFailEvenWithCorrectImageIds() {
        val v=candidate(longArrayOf(0,33_000,66_050))
        v.beginSegment(0,true)
        listOf(0L,33_000,99_383).forEachIndexed {i,p->v.decoded(DecodedProbeFrame(i.toLong(),p))}
        v.endSegment(true);v.finishRun()
        assertEquals(SyntheticSequenceStatus.FAIL,v.result().status)
    }
    @Test fun legacyComparisonStaysStrictAndNewComparisonHasAFixedBound() {
        val old=SyntheticSegmentVerifier(longArrayOf(0,33_000,66_050),1,100_000,20)
        old.beginSegment(0,true)
        listOf(0L,33_000,66_000).forEachIndexed {i,p->old.decoded(DecodedProbeFrame(i.toLong(),p))}
        old.endSegment(true);old.finishRun();assertEquals(SyntheticSequenceStatus.FAIL,old.result().status)
        assertThrows(IllegalArgumentException::class.java) {SyntheticSegmentVerifier(longArrayOf(0),1,100_000,121,DecodedPtsContract.ANDROID_MP4_SUBFRAME_ADJUSTMENT)}
        val large=candidate(longArrayOf(0,33_000,66_050));large.beginSegment(0,true)
        listOf(0L,33_000,66_171).forEachIndexed {i,p->large.decoded(DecodedProbeFrame(i.toLong(),p))}
        large.endSegment(true);large.finishRun();assertEquals(SyntheticSequenceStatus.FAIL,large.result().status)
    }
    @Test fun nearTimestampCannotHideDuplicatedImageOrNonIncreasingPts() {
        for(duplicateImage in listOf(true,false)) {
            val v=candidate(longArrayOf(0,100,200));v.beginSegment(0,true)
            v.decoded(DecodedProbeFrame(0,0));v.decoded(DecodedProbeFrame(1,100))
            v.decoded(DecodedProbeFrame(if(duplicateImage)1 else 2,if(duplicateImage)200 else 100))
            v.endSegment(true);v.finishRun();assertEquals(SyntheticSequenceStatus.FAIL,v.result().status)
        }
    }
}
