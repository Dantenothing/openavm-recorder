package com.dante.zeekrcapabilitylab.recorder

import com.dante.zeekrcapabilitylab.service.recorder.EncodedOutputWatchdogPolicy
import com.dante.zeekrcapabilitylab.service.recorder.IncidentWindowPolicy
import com.dante.zeekrcapabilitylab.service.recorder.SegmentTimeRange
import com.dante.zeekrcapabilitylab.service.recorder.StoragePolicy
import com.dante.zeekrcapabilitylab.service.recorder.SegmentGapPolicy
import com.dante.zeekrcapabilitylab.service.recorder.RecorderFailureStatusPolicy
import com.dante.zeekrcapabilitylab.service.recorder.RecorderStatus
import com.dante.zeekrcapabilitylab.service.recorder.RecorderWorkKind
import com.dante.zeekrcapabilitylab.service.recorder.RecorderWorkLane
import com.dante.zeekrcapabilitylab.service.recorder.RecorderWorkLanePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrontFirstLifecyclePolicyTest {
    @Test
    fun rolloverGateNeverSharesTheCompletedSegmentAnalysisLane() {
        assertEquals(RecorderWorkLane.STORAGE, RecorderWorkLanePolicy.laneFor(RecorderWorkKind.NEXT_SEGMENT_GATE))
        assertEquals(
            RecorderWorkLane.ANALYSIS,
            RecorderWorkLanePolicy.laneFor(RecorderWorkKind.COMPLETED_SEGMENT_ANALYSIS),
        )
    }

    @Test
    fun recorderAndStorageFailuresRemainDistinct() {
        assertEquals(RecorderStatus.ERROR, RecorderFailureStatusPolicy.recorderFailure())
        assertEquals(RecorderStatus.STORAGE_BLOCKED, RecorderFailureStatusPolicy.storageFailure())
    }

    @Test
    fun encodedWatchdogRequiresBothTimestampAndFileProgressToStall() {
        assertTrue(
            EncodedOutputWatchdogPolicy.stalled(
                EncodedOutputWatchdogPolicy.STALL_TIMEOUT_MS,
                encodedFrameCount = 10,
                previousEncodedFrameCount = 10,
                fileBytes = 100,
                previousFileBytes = 100,
            ),
        )
        assertFalse(
            EncodedOutputWatchdogPolicy.stalled(
                EncodedOutputWatchdogPolicy.STALL_TIMEOUT_MS,
                encodedFrameCount = 11,
                previousEncodedFrameCount = 10,
                fileBytes = 100,
                previousFileBytes = 100,
            ),
        )
        assertFalse(
            EncodedOutputWatchdogPolicy.stalled(
                EncodedOutputWatchdogPolicy.STALL_TIMEOUT_MS - 1,
                encodedFrameCount = 10,
                previousEncodedFrameCount = 10,
                fileBytes = 100,
                previousFileBytes = 100,
            ),
        )
    }

    @Test
    fun incidentWindowUsesEncodedTimeRangesNotFilesystemOrder() {
        val ranges = listOf(
            SegmentTimeRange("later", 2000, 2999),
            SegmentTimeRange("before", 0, 999),
            SegmentTimeRange("current", 1000, 1999),
            SegmentTimeRange("outside", 5000, 5999),
        )

        assertEquals(
            listOf("before", "current", "later"),
            IncidentWindowPolicy.select(ranges, requestedAtEpochMs = 1500, beforeMs = 600, afterMs = 600),
        )
    }

    @Test
    fun quarantineUsageHasIndependentHardBound() {
        assertTrue(StoragePolicy.quarantineWithinBound(StoragePolicy.MAX_QUARANTINE_BYTES))
        assertFalse(StoragePolicy.quarantineWithinBound(StoragePolicy.MAX_QUARANTINE_BYTES + 1))
        assertFalse(StoragePolicy.quarantineWithinBound(-1))
    }

    @Test
    fun encodedRolloverGapUsesPresentationTimestamps() {
        assertEquals(250L, SegmentGapPolicy.encodedGapMs(1_000_000, 1_250_000))
        assertEquals(null, SegmentGapPolicy.encodedGapMs(null, 1_250_000))
    }
}
