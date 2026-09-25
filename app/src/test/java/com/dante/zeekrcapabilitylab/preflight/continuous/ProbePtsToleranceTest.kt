package com.dante.zeekrcapabilitylab.preflight.continuous

import org.junit.Assert.*
import org.junit.Test

class ProbePtsToleranceTest {
    private fun verify(last:Long,id:Long=2):SyntheticSequenceResult {
        val v=SyntheticSegmentVerifier(longArrayOf(0,33_333,66_666),2,33_334,20)
        v.beginSegment(0,true); v.decoded(DecodedProbeFrame(0,0)); v.endSegment(true)
        v.beginSegment(33_333,true); v.decoded(DecodedProbeFrame(1,0)); v.decoded(DecodedProbeFrame(id,last))
        v.endSegment(true); v.finishRun(); return v.result()
    }
    @Test fun roundingAllowanceNeverBecomesAFrameOrIdAllowance() {
        assertEquals(SyntheticSequenceStatus.PASS,verify(33_334).status)
        assertEquals(SyntheticSequenceStatus.FAIL,verify(66_666).status)
        assertEquals(SyntheticSequenceStatus.FAIL,verify(33_334,1).status)
        assertThrows(IllegalArgumentException::class.java) { SyntheticSegmentVerifier(longArrayOf(0),1,33_334,33_334) }
    }
}
