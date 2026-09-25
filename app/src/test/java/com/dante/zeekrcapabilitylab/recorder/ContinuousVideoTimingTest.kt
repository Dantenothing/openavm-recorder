package com.dante.zeekrcapabilitylab.recorder

import com.dante.zeekrcapabilitylab.service.recorder.*
import org.junit.Assert.*
import org.junit.Test

class ContinuousVideoTimingTest {
    @Test fun normalPreservesEveryTimestampIncludingRealGaps() {
        val clock = ContinuousVideoTiming(30, 1)
        for (pts in listOf(0L, 33_333, 70_000, 5_000_000, 5_040_000)) assertEquals(pts, clock.select(pts))
        assertEquals(2_000L, clock.keyframeTimeoutMs)
    }
    @Test fun allMultipliersKeepThirtyFpsPlaybackWithoutSamplingTheDisplay() {
        for (multiple in TimeLapsePolicy.MULTIPLIERS) {
            val clock = ContinuousVideoTiming(30, multiple)
            val selected = (0 until 30 * multiple).mapNotNull { frame -> clock.select(frame * 1_000_000L / 30) }
            assertEquals("${multiple}x", 30, selected.size)
            assertTrue(selected.zipWithNext().all { (a, b) -> b - a in 33_332L..33_334L })
            assertEquals(30 * multiple * 1_000_000L / 30, clock.sourceUs(1_000_000))
        }
    }
    @Test fun returningAfterSourcePauseDoesNotEncodeABurstOfDuplicateFrames() {
        val clock = ContinuousVideoTiming(30, 150)
        assertEquals(0L, clock.select(0)); assertNull(clock.select(4_999_999))
        assertEquals(33_333L, clock.select(5_000_000))
        assertEquals(1_200_000L, clock.select(180_000_000))
        repeat(149) { assertNull(clock.select(180_000_000L + (it + 1) * 1_000_000 / 30)) }
        assertEquals(1_233_333L, clock.select(185_000_000))
    }
    @Test fun sparseFinalSampleCannotClaimFiveSecondsAfterAnEarlyManualStop() {
        val clock = ContinuousVideoTiming(30, 150)
        assertEquals(5_233_333L, clock.sourceEndUs(66_666, 5_200_000))
        assertEquals(9_999_900L, clock.sourceEndUs(66_666, 10_000_000))
    }
    @Test fun sourceWaitDoesNotReopenCameraOrConfuseAnAliveWorkerWithANativeHang() {
        assertNull(ContinuousSourceWaitPolicy.problem(20, 10 * 60_000))
        assertEquals("PRODUCT_INPUT_THREAD_STALLED", ContinuousSourceWaitPolicy.problem(5_000, 20))
        assertEquals("PRODUCT_SOURCE_WAIT_TIMEOUT", ContinuousSourceWaitPolicy.problem(20, 30 * 60_000))
    }
    @Test fun sparseKeyframeWaitAndUsbWriterWaitUseDifferentBudgets() {
        val clock = ContinuousVideoTiming(30, 150)
        val progress = ContinuousCutProgress { clock.keyframeTimeoutMs }
        progress.request(2, 0)
        assertNull(progress.problem(5_100)); assertNull(progress.problem(11_999))
        assertEquals("PRODUCT_KEYFRAME_CUT_TIMEOUT", progress.problem(12_000))
        progress.keyAccepted(12_100)
        assertEquals("PRODUCT_WRITER_CUT_TIMEOUT", progress.problem(14_100, sourceQuiet = true))
    }
    @Test fun waitingForSourcePausesOnlyTheKeyframeDeadlineThenRestoresIt() {
        val progress = ContinuousCutProgress(); progress.request(2, 0)
        assertNull(progress.problem(1_000, true)); assertNull(progress.problem(600_000, true))
        assertNull(progress.problem(601_000, false)); assertNull(progress.problem(601_999))
        assertEquals("PRODUCT_KEYFRAME_CUT_TIMEOUT", progress.problem(602_000))
        assertEquals(600_000L, progress.snapshot()!!.sourceWaitMs)
    }
    @Test fun keyframeArrivalBeforeWatchdogTickStillRecordsTheSourceWait() {
        val progress = ContinuousCutProgress(); progress.request(2, 0); progress.problem(1000, true)
        progress.keyAccepted(601_000)
        assertEquals(600_000L, progress.snapshot()!!.sourceWaitMs)
        assertEquals("PRODUCT_WRITER_CUT_TIMEOUT", progress.problem(603_000, true))
    }
    @Test fun cameraRecoveryCannotFallBackToInternalStorageWhenUsbIsLost() {
        for (phase in CameraRecoveryPhase.entries) assertTrue(ProductContinuousPolicy.blocksUsbFallback(true, phase))
        for (phase in listOf(CameraRecoveryPhase.FINALIZING, CameraRecoveryPhase.WAITING_CAMERA,
            CameraRecoveryPhase.RESUMING, CameraRecoveryPhase.PROBATION))
            assertTrue(ProductContinuousPolicy.blocksUsbFallback(false, phase))
        assertFalse(ProductContinuousPolicy.blocksUsbFallback(false, CameraRecoveryPhase.HEALTHY))
    }
}
