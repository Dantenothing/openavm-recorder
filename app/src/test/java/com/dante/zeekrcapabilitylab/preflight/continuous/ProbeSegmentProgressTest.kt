package com.dante.zeekrcapabilitylab.preflight.continuous

import org.junit.Assert.*
import org.junit.Test

class ProbeSegmentProgressTest {
    private fun ready() = ProbeSegmentProgress(listOf(5_000_000,10_000_000,15_000_000)).apply {
        prepared(1,10); armed(1,11); input(4_966_666,12); syncRequested(1,13); syncReturned(1,14)
    }
    @Test fun acceptedKeyframeIsNotMissingWhileUsbCloseIsWithinItsOwnDeadline() {
        val p=ready(); p.accepted(1,5_000_000,20); p.enqueued(1,20); p.received(1,21)
        p.closeStarted(0,22); p.writer("USB_SYNC_CLOSE",0,22)
        p.input(6_533_333,120); p.output(6_500_000,120)
        assertNull(p.problem(120)) // Beta18's mixed predicate throws here.
        assertEquals("USB_SYNC_CLOSE_TIMEOUT",p.problem(2022)?.code)
    }
    @Test fun missingKeyframeRequiresEncodedOutputToHaveCrossedTheDeadline() {
        val p=ready(); p.input(6_533_333,100); p.output(6_533_333,100)
        assertEquals("CUT_KEYFRAME_MISSING",p.problem(100)?.code)
    }
    @Test fun encoderLagIsNotReportedAsMissingKeyframe() {
        val p=ready(); p.input(6_533_333,100); p.output(4_900_000,100)
        assertEquals("ENCODER_OUTPUT_LAG",p.problem(100)?.code)
    }
    @Test fun noEncoderOutputAndStalledWriteHaveDifferentReasons() {
        val p=ready(); p.input(5_000_000,20)
        assertEquals("ENCODER_OUTPUT_TIMEOUT",p.problem(2012)?.code)
        val w=ready(); w.writer("WRITE_SAMPLE",0,100)
        assertEquals("USB_WRITE_TIMEOUT",w.problem(2100)?.code)
    }
    @Test fun acceptedCutWaitingInQueueIsNotAFileCloseTimeout() {
        val p=ready(); p.accepted(1,5_000_000,100); p.enqueued(1,101)
        p.input(5_900_000,2050); p.output(5_866_666,2050)
        assertEquals("CUT_QUEUE_TIMEOUT",p.problem(2101)?.code)
    }
    @Test fun preparingFileAndFinalizingMuxerRemainBounded() {
        val p=ready(); p.writer("PREPARE_FILE",1,100)
        assertEquals("NEXT_FILE_PREPARE_TIMEOUT",p.problem(2100)?.code)
        val closing=ready(); closing.writer("MUXER_FINALIZE",0,3000)
        assertEquals("MUXER_FINALIZE_TIMEOUT",closing.problem(5000)?.code)
    }
    @Test fun preparationDoesNotRequestSyncSeveralSecondsEarly() {
        val p=ProbeSegmentProgress(listOf(5_000_000,10_000_000,15_000_000))
        p.prepared(1,1); p.armed(1,2); p.input(100_000,3)
        assertNull(p.syncDue()); p.input(4_966_666,100)
        assertEquals(1,p.syncDue()); p.syncRequested(1,101); assertNull(p.syncDue())
    }
    @Test fun failureSnapshotDoesNotTurnIntoFinalStateAfterDrain() {
        val p=ready(); p.writer("USB_SYNC_CLOSE",0,20)
        val frozen=p.snapshot(2020,7000000,34)
        p.closed(0,2100); p.writer("DONE",null,2101)
        assertNull(frozen.files[0].closedAtMs)
        assertEquals("USB_SYNC_CLOSE",frozen.writerStage)
        assertEquals(34,frozen.queueItems)
        assertEquals(2100L,p.snapshot(2200).files[0].closedAtMs)
    }
    @Test fun openAndSyncRequestHaveIndependentDeadlines() {
        val open=ProbeSegmentProgress(listOf(5_000_000,10_000_000,15_000_000))
        open.openStarted(1,100)
        assertEquals("USB_FILE_OPEN_TIMEOUT",open.problem(2100)?.code)
        val sync=ready(); sync.syncRequested(2,100)
        assertEquals("SYNC_REQUEST_TIMEOUT",sync.problem(2100)?.code)
    }
    @Test fun terminalInputDurationUses497FramesInsteadOfThe600FramePlan() {
        val p=ProbeSegmentProgress(listOf(5_000_000,10_000_000,15_000_000))
        repeat(497) { p.input(it*1_000_000L/30,it*34L) }
        assertEquals(16_566_666L,p.endPtsUs())
    }
    @Test fun lateCompletionCannotEraseAnOverrunBetweenWatchdogPolls() {
        val p=ready(); p.writer("USB_SYNC_CLOSE",0,100)
        p.closed(0,2101); p.writer("IDLE",0,2101)
        assertEquals("USB_SYNC_CLOSE_TIMEOUT",p.problem(2102)?.code)
        assertEquals(2101L,p.snapshot(2102).overrun?.returnedAtMs)
        val cut=ready(); cut.accepted(1,6_533_333,200)
        assertEquals("CUT_KEYFRAME_LATE",cut.problem(200)?.code)
    }
}
