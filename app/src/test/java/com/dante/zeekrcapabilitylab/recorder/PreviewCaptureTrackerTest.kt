package com.dante.zeekrcapabilitylab.recorder

import com.dante.zeekrcapabilitylab.service.recorder.*
import org.junit.Assert.*
import org.junit.Test

class PreviewCaptureTrackerTest {
    @Test fun successfulMetadataAndLostPreviewBufferRemainSeparateEvidence() {
        val tracker = PreviewCaptureTracker().apply { reset(1) }
        tracker.completed(1, true, 700, 100)
        tracker.bufferLost(1, CaptureOutputRole.PREVIEW, 101)
        assertEquals(1L, tracker.stats.completedWithPreviewTarget)
        assertEquals(1L, tracker.stats.previewBuffersLost)
        assertEquals(0L, tracker.stats.encoderBuffersLost)
        assertEquals(0L, tracker.stats.captureFailures)
    }

    @Test fun newSegmentRejectsOldCompletionsLossesAndFailures() {
        val tracker = PreviewCaptureTracker().apply { reset(1) }
        tracker.completed(1, true, 700, 100)
        tracker.reset(2)
        tracker.completed(1, true, 701, 110)
        tracker.bufferLost(1, CaptureOutputRole.PREVIEW, 111)
        tracker.failed(1, 2)
        assertEquals(PreviewCaptureStats(), tracker.stats)
        tracker.completed(2, false, 800, 120)
        assertEquals(false, tracker.stats.lastCompletedHadPreviewTarget)
        assertEquals(1L, tracker.stats.completedWithoutPreviewTarget)
        assertNull(tracker.stats.lastPreviewResultElapsedMs)
    }

    @Test fun disablingPreviewDoesNotPretendAnEncoderOnlyResultRefreshedThePreview() {
        val tracker = PreviewCaptureTracker().apply { reset(3) }
        tracker.completed(3, true, 400, 500)
        tracker.completed(3, false, 500, 600)
        assertEquals(500L, tracker.stats.lastPreviewResultElapsedMs)
        assertEquals(400L, tracker.stats.lastPreviewSensorTimestampNs)
        assertEquals(false, tracker.stats.lastCompletedHadPreviewTarget)
        tracker.bufferLost(3, CaptureOutputRole.ENCODER, 601)
        tracker.bufferLost(3, CaptureOutputRole.OTHER, 602)
        tracker.failed(3, 0)
        assertEquals(0L, tracker.stats.previewBuffersLost)
        assertEquals(1L, tracker.stats.encoderBuffersLost)
        assertEquals(1L, tracker.stats.otherBuffersLost)
        assertEquals(1L, tracker.stats.captureFailures)
    }
}
