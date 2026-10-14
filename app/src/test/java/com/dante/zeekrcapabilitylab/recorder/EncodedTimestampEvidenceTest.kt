package com.dante.zeekrcapabilitylab.recorder

import com.dante.zeekrcapabilitylab.service.recorder.*
import org.junit.Assert.*
import org.junit.Test

class EncodedTimestampEvidenceTest {
    @Test fun duplicateAndRegressedSamplesKeepTheirActualValuesAndDoNotConsumeCut() {
        val planner = KeyframeSegmentPlanner()
        planner.accept(156_572_106, true); planner.requestCut()
        for (pts in listOf(156_572_106L, 156_550_000L, -1L)) {
            val error = assertThrows(EncodedTimestampException::class.java) { planner.accept(pts, true) }
            assertEquals(156_572_106L, error.previousPtsUs)
            assertEquals(pts, error.rejectedPtsUs)
            assertTrue(error.cutPending)
        }
        assertTrue(requireNotNull(planner.accept(156_630_498, true)).cutBefore)
    }

    @Test fun firstFailurePreservesWrappedTimestampFactsAfterLaterCleanupErrors() {
        val error = EncodedTimestampException(200, 199, false, false)
        val capture = ContinuousFailureCapture("test-session", { 1000 }, { 2000 })
        val first = capture.capture(IllegalStateException("CONTINUOUS_ENCODER_FAILED", error), ContinuousFailureStage.CODEC_DRAIN)
        capture.capture(IllegalStateException("CAMERA_CLOSE_UNCONFIRMED"), ContinuousFailureStage.CODEC_CLEANUP)
        assertSame(first, capture.value)
        assertEquals("ENCODED_TIMESTAMP_NOT_INCREASING", first.errorCode)
        assertEquals("200", first.facts()["timestampPreviousPtsUs"])
        assertEquals("199", first.facts()["timestampRejectedPtsUs"])
        assertEquals("false", first.facts()["timestampKeyFrame"])
        assertTrue(ContinuousFailureEvidence.FACT_KEYS.containsAll(error.facts().keys))
    }
}
