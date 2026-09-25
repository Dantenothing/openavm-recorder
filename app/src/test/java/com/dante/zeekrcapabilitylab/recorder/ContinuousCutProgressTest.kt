package com.dante.zeekrcapabilitylab.recorder

import com.dante.zeekrcapabilitylab.service.recorder.ContinuousCutProgress
import org.junit.Assert.*
import org.junit.Test

class ContinuousCutProgressTest {
    @Test fun manualStopCancelsDeadlinesWithoutErasingTheCutTraceOrRearmingIt() {
        val progress = ContinuousCutProgress(); progress.request(2, 100)
        progress.cancel()
        assertNull(progress.problem(10_000)); assertEquals(100L, progress.snapshot()!!.requestedAtMs)
        assertThrows(IllegalStateException::class.java) { progress.request(3, 10_000) }
    }
    @Test fun realMissingKeyframeRetainsBoundedStop() {
        val progress = ContinuousCutProgress(); progress.request(2, 100)
        assertNull(progress.problem(2099)); assertEquals("PRODUCT_KEYFRAME_CUT_TIMEOUT", progress.problem(2100))
    }
    @Test fun keyAcceptedButWriterBlockedHasDifferentReason() {
        val progress = ContinuousCutProgress(); progress.request(2, 100); progress.keyAccepted(200)
        assertNull(progress.problem(2199)); assertEquals("PRODUCT_WRITER_CUT_TIMEOUT", progress.problem(2200))
    }
    @Test fun slowOldFileCloseCannotBecomeKeyframeTimeout() {
        val progress = ContinuousCutProgress(); progress.request(2, 100); progress.keyAccepted(200); progress.writerReceived(220)
        assertNull(progress.problem(100_000))
        assertEquals(ContinuousCutProgress.Snapshot(2, 100, 200, 220), progress.snapshot())
    }
    @Test fun firstSnapshotSurvivesLaterDrainCompletion() {
        val progress = ContinuousCutProgress(); progress.request(2, 100); progress.keyAccepted(200)
        val failedAt = progress.snapshot()
        progress.writerReceived(3000)
        assertNull(failedAt!!.writerReceivedAtMs); assertEquals(3000L, progress.snapshot()!!.writerReceivedAtMs)
    }
    @Test fun repeatedOrRegressingCutIsRejected() {
        val progress = ContinuousCutProgress(); progress.request(2, 100)
        assertThrows(IllegalStateException::class.java) { progress.request(3, 200) }
        progress.keyAccepted(200); progress.writerReceived(210)
        assertThrows(IllegalStateException::class.java) { progress.request(2, 220) }
        progress.request(3, 220); assertNull(progress.snapshot()!!.keyAcceptedAtMs)
    }
}
