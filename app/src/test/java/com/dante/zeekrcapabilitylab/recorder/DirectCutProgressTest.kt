package com.dante.zeekrcapabilitylab.recorder

import com.dante.zeekrcapabilitylab.service.recorder.*
import org.junit.Assert.*
import org.junit.Test

class DirectCutProgressTest {
    @Test fun slowStorageHasOwnBudgetAndDoesNotConsumeKeyframeTime() {
        val cut = DirectCutProgress(1_000)
        assertNull(cut.problem(10_000))
        cut.advance(DirectCutProgress.Phase.PREPARING_MUXER, 12_000)
        cut.advance(DirectCutProgress.Phase.WAITING_KEYFRAME, 13_000)
        assertNull(cut.problem(20_999))
        assertEquals("CONTINUOUS_KEYFRAME_CUT_TIMEOUT", cut.problem(21_000))
    }
    @Test fun everyBlockingStageStillHasAnUpperBound() {
        val phases = listOf(DirectCutProgress.Phase.PREPARING_DESTINATION, DirectCutProgress.Phase.PREPARING_MUXER,
            DirectCutProgress.Phase.WAITING_KEYFRAME, DirectCutProgress.Phase.WRITING_BOUNDARY)
        val codes = listOf("CONTINUOUS_OUTPUT_PREPARATION_TIMEOUT", "CONTINUOUS_MUXER_PREPARATION_TIMEOUT",
            "CONTINUOUS_KEYFRAME_CUT_TIMEOUT", "CONTINUOUS_WRITER_CUT_TIMEOUT")
        phases.forEachIndexed { index, phase ->
            val cut = DirectCutProgress(0)
            if (index > 0) cut.advance(phase, 0)
            assertEquals(codes[index], cut.problem(if (index == 0) 30_000 else 8_000))
        }
    }
    @Test fun acceptedKeyframeTransfersDeadlineToWriterAndCompletedCutNeverTimesOut() {
        val cut = DirectCutProgress(0)
        cut.advance(DirectCutProgress.Phase.WAITING_KEYFRAME, 1_000)
        cut.advance(DirectCutProgress.Phase.WRITING_BOUNDARY, 7_000)
        assertNull(cut.problem(9_000))
        cut.advance(DirectCutProgress.Phase.COMPLETE, 10_000)
        assertNull(cut.problem(100_000))
    }
    @Test fun stopRevokesLateProgressWithoutAFalseFailure() {
        val old = DirectCutProgress(0)
        old.cancel(10)
        old.advance(DirectCutProgress.Phase.WAITING_KEYFRAME, 20)
        assertEquals(DirectCutProgress.Phase.CANCELLED, old.snapshot(30).phase)
        assertNull(old.problem(99_000))
        assertEquals("CONTINUOUS_OUTPUT_PREPARATION_TIMEOUT", DirectCutProgress(0).problem(30_000))
    }
    @Test fun firstFailureContainsBoundedCutPhaseAndCachedCounters() {
        val cut = DirectCutProgress(0)
        cut.advance(DirectCutProgress.Phase.WAITING_KEYFRAME, 1_000)
        val capture = ContinuousFailureCapture("test-run", { 10_000 }, { 123 })
        val facts = capture.capture(null, ContinuousFailureStage.CUT_WATCHDOG,
            ContinuousFailureSample(9_999, 0, 0, 300, 500_000, 2, null),
            cut.problem(10_000), cut.snapshot(10_000)).facts()
        assertEquals("WAITING_KEYFRAME", facts["cutPhase"])
        assertEquals("9000", facts["cutPhaseMs"])
        assertEquals("CONTINUOUS_KEYFRAME_CUT_TIMEOUT", facts["errorCode"])
        assertEquals("300", facts["faultEncodedFrames"])
        assertTrue(ContinuousFailureEvidence.FACT_KEYS.containsAll(DirectCutProgress.FACT_KEYS))
    }
}
