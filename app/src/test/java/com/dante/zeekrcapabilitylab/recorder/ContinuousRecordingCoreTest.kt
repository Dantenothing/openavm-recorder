package com.dante.zeekrcapabilitylab.recorder

import com.dante.zeekrcapabilitylab.service.recorder.*
import org.junit.Assert.*
import org.junit.Test

class ContinuousRecordingCoreTest {
    @Test fun firstFileWaitsForIndependentKeyframe() {
        val p = KeyframeSegmentPlanner()
        assertNull(p.accept(1_000, false))
        assertEquals(KeyframeSegmentPlanner.Placement(0, false), p.accept(2_000, true))
        assertEquals(KeyframeSegmentPlanner.Placement(500, false), p.accept(2_500, false))
    }

    @Test fun cutKeepsInterFramesInOldFileAndPlacesKeyframeInNewFileOnce() {
        val p = KeyframeSegmentPlanner()
        p.accept(1_000, true)
        p.requestCut()
        assertEquals(KeyframeSegmentPlanner.Placement(500, false), p.accept(1_500, false))
        assertEquals(KeyframeSegmentPlanner.Placement(0, true), p.accept(2_000, true))
        assertEquals(KeyframeSegmentPlanner.Placement(500, false), p.accept(2_500, false))
        assertEquals(KeyframeSegmentPlanner.Placement(1_000, false), p.accept(3_000, true))
    }

    @Test fun repeatedCutsDoNotDuplicateOrDropAnyAcceptedFrame() {
        val p = KeyframeSegmentPlanner()
        val files = mutableListOf(mutableListOf<Long>())
        repeat(331) { index ->
            if (index > 0 && index % 30 == 1) p.requestCut()
            val placement = p.accept(index * 33_333L, index % 30 == 0)!!
            if (placement.cutBefore) files.add(mutableListOf())
            files.last().add(placement.ptsUs)
        }
        assertEquals(12, files.size)
        assertEquals(331, files.sumOf { it.size })
        assertTrue(files.all { it.first() == 0L })
        assertTrue(files.all { file -> file.zipWithNext().all { it.second > it.first } })
    }

    @Test fun unexpectedReorderedTimestampsFailInsteadOfProducingCorruptFiles() {
        val p = KeyframeSegmentPlanner()
        p.accept(10, true)
        assertThrows(IllegalStateException::class.java) { p.accept(9, false) }
        assertThrows(IllegalStateException::class.java) { p.accept(10, false) }
    }

    @Test fun blockedWriterStillCountsAgainstMemoryLimitAndRejectsBeforeAllocating() {
        val q = BoundedEncodedQueue<String>(10, 5)
        assertTrue(q.offer(6) { "in-flight" })
        val held = q.poll()!!
        assertFalse(q.offer(5) { error("must not allocate") })
        assertTrue(q.offer(4) { "queued" })
        assertEquals(10L to 2, q.snapshot())
        q.clear()
        assertEquals(6L to 1, q.snapshot())
        q.release(held)
        assertEquals(0L to 0, q.snapshot())
    }

    @Test fun boundaryOrderingAndCountLimitArePreserved() {
        val q = BoundedEncodedQueue<String>(10, 3)
        assertTrue(q.offer(1) { "old" })
        assertTrue(q.offer(0) { "boundary" })
        assertTrue(q.offer(1) { "new-key" })
        assertFalse(q.offer(0) { "unbounded-control" })
        assertEquals(listOf("old", "boundary", "new-key"), List(3) {
            val ticket = q.poll()!!; q.release(ticket); ticket.value
        })
        assertEquals(0L to 0, q.snapshot())
    }

    @Test fun failedAllocationDoesNotLeakCredits() {
        val q = BoundedEncodedQueue<String>(10, 2)
        assertThrows(IllegalStateException::class.java) { q.offer(10) { error("allocation failure") } }
        assertEquals(0L to 0, q.snapshot())
        assertTrue(q.offer(10) { "ok" })
    }

    @Test fun stopOrChangedOwnershipRejectsDelayedRolloverCallback() {
        assertTrue(ContinuousRecordingPolicy.mayCommitRotation(true, true, true, false, false))
        assertFalse(ContinuousRecordingPolicy.mayCommitRotation(true, true, true, true, false))
        assertFalse(ContinuousRecordingPolicy.mayCommitRotation(true, true, true, false, true))
        assertFalse(ContinuousRecordingPolicy.mayCommitRotation(false, true, true, false, false))
        assertFalse(ContinuousRecordingPolicy.mayCommitRotation(true, false, true, false, false))
        assertFalse(ContinuousRecordingPolicy.mayCommitRotation(true, true, false, false, false))
    }

    @Test fun timelapseAndOtherCamerasKeepExistingRecorder() {
        assertTrue(ContinuousRecordingPolicy.eligible(RecordingMode.NORMAL, true, RecordingSourceRole.SURROUND))
        assertFalse(ContinuousRecordingPolicy.eligible(RecordingMode.TIME_LAPSE, true, RecordingSourceRole.SURROUND))
        assertFalse(ContinuousRecordingPolicy.eligible(RecordingMode.NORMAL, false, RecordingSourceRole.SURROUND))
        assertFalse(ContinuousRecordingPolicy.eligible(RecordingMode.NORMAL, true, RecordingSourceRole.CABIN))
        assertFalse(ContinuousRecordingPolicy.eligible(RecordingMode.NORMAL, true, RecordingSourceRole.IR))
    }
}
