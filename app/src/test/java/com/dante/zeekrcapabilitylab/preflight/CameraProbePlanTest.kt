package com.dante.zeekrcapabilitylab.preflight

import com.dante.zeekrcapabilitylab.preflight.continuous.*
import org.junit.Assert.*
import org.junit.Test

class CameraProbePlanTest {
    @Test fun minutePlanUsesThreeCutsWithoutInflatingFileCount() {
        val p=CameraProbePlan(240,true)
        assertEquals(listOf(60_000_000L,120_000_000L,180_000_000L),p.cutTargetsUs)
        assertNull(p.spaceReason(p.minimumFreeBytes))
        assertEquals("P2_USB_FREE_SPACE_LOW",p.spaceReason(p.minimumFreeBytes-1))
        assertTrue(p.encodedByteBudget>240*53_000_000L/8)
        assertThrows(IllegalArgumentException::class.java) { CameraProbePlan(Int.MAX_VALUE,false) }
    }
    @Test fun cameraTimesAreNotReplacedWithIdealThirtyFps() {
        val l=CameraFrameLedger(4)
        assertNull(l.admit(0));assertEquals(0L,l.admit(10_000_000_000L))
        assertNull(l.admit(10_000_000_000L))
        assertEquals(34_321L,l.admit(10_034_321_000L))
        assertEquals(104_321L,l.admit(10_104_321_000L))
        assertEquals(70_000_000L,l.maximumGapNs)
        assertArrayEquals(longArrayOf(0,34_321,104_321),l.pts())
        assertEquals(137_655L,l.endPts())
        assertThrows(IllegalStateException::class.java) { l.admit(10_034_321_000L) }
    }
    @Test fun decodedRealSequenceDetectsMissingAdmittedImageAtCut() {
        val verifier=SyntheticSegmentVerifier(longArrayOf(0,33_333,67_000,100_100),2,100_000,20)
        verifier.beginSegment(0,true);verifier.decoded(DecodedProbeFrame(0,0));verifier.decoded(DecodedProbeFrame(1,33_333));verifier.endSegment(true)
        verifier.beginSegment(100_100,true);verifier.decoded(DecodedProbeFrame(3,0));verifier.endSegment(true);verifier.finishRun()
        assertEquals(SyntheticSequenceStatus.FAIL,verifier.result().status)
    }
}
