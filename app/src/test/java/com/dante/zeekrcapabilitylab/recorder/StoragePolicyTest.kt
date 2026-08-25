package com.dante.zeekrcapabilitylab.recorder

import com.dante.zeekrcapabilitylab.service.recorder.ManagedSegmentFile
import com.dante.zeekrcapabilitylab.service.recorder.StoragePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoragePolicyTest {

    private val gb = 1024L * 1024L * 1024L

    private fun file(
        name: String,
        bytes: Long,
        modified: Long,
        isFinal: Boolean = true,
        hasSidecar: Boolean = true,
        protected: Boolean = false,
        uploadPinned: Boolean = false,
        analysisInFlight: Boolean = false,
        playing: Boolean = false,
        stoppedAtEpochMs: Long? = null,
    ) = ManagedSegmentFile(
        path = "/segments/$name",
        bytes = bytes,
        lastModifiedMs = modified,
        isFinalMp4 = isFinal,
        hasSidecar = hasSidecar,
        protected = protected,
        uploadPinned = uploadPinned,
        analysisInFlight = analysisInFlight,
        playing = playing,
        stoppedAtEpochMs = stoppedAtEpochMs,
    )

    @Test
    fun oldestUnprotectedSegmentsAreEvictedFirst() {
        val files = listOf(
            file("old.mp4", 2L * gb, modified = 100),
            file("middle.mp4", 2L * gb, modified = 200),
            file("new.mp4", 2L * gb, modified = 300),
        )

        val evictions = StoragePolicy.selectEvictions(files, 4L * gb)

        assertEquals(listOf("/segments/old.mp4"), evictions)
    }

    @Test
    fun protectedSegmentsAreNeverEvicted() {
        val files = listOf(
            file("old.mp4", 2L * gb, modified = 100, protected = true),
            file("middle.mp4", 2L * gb, modified = 200, protected = true),
            file("new.mp4", 2L * gb, modified = 300, protected = true),
        )

        val evictions = StoragePolicy.selectEvictions(files, 4L * gb)

        assertEquals(emptyList<String>(), evictions)
    }

    @Test
    fun segmentsWithoutSidecarAreUnknownAndNotEvicted() {
        val files = listOf(
            file("old-no-sidecar.mp4", 2L * gb, modified = 100, hasSidecar = false),
            file("middle-no-sidecar.mp4", 2L * gb, modified = 200, hasSidecar = false),
        )

        val evictions = StoragePolicy.selectEvictions(files, 2L * gb)

        assertEquals(emptyList<String>(), evictions)
    }

    @Test
    fun currentPartialFileIsNeverSelected() {
        val files = listOf(
            file("seg-0001-1-1280x5140-14M.mp4", 2L * gb, modified = 100),
            file("seg-0002-2-1280x5140-14M.mp4.partial", 1L * gb, modified = 200, isFinal = false),
        )

        val evictions = StoragePolicy.selectEvictions(files, 1L * gb)

        assertEquals(listOf("/segments/seg-0001-1-1280x5140-14M.mp4"), evictions)
        assertTrue(evictions.none { it.endsWith(".partial") })
    }

    @Test
    fun underLimitNothingIsEvicted() {
        val files = listOf(
            file("a.mp4", 1L * gb, modified = 100),
            file("b.mp4", 2L * gb, modified = 200),
        )

        assertEquals(emptyList<String>(), StoragePolicy.selectEvictions(files, 8L * gb))
    }

    @Test
    fun usageCountsOnlyFinalizedMp4s() {
        val files = listOf(
            file("a.mp4", 3L * gb, modified = 100),
            file("b.mp4.partial", 4L * gb, modified = 200, isFinal = false),
            ManagedSegmentFile(
                path = "/segments/notes.txt",
                bytes = 9L * gb,
                lastModifiedMs = 300,
                isFinalMp4 = false,
                hasSidecar = false,
                protected = false,
            ),
        )

        assertEquals(3L * gb, StoragePolicy.currentUsageBytes(files))
    }

    @Test
    fun segmentBytesAreBitrateTimesSecondsOverEight() {
        assertEquals(14_000_000L * 60 / 8, StoragePolicy.estimateSegmentBytes(14_000_000, 60))
        assertEquals(40_000_000L * 10 / 8, StoragePolicy.estimateSegmentBytes(40_000_000, 10))
        assertEquals(0L, StoragePolicy.estimateSegmentBytes(0, 60))
        assertEquals(0L, StoragePolicy.estimateSegmentBytes(14_000_000, 0))
    }

    @Test
    fun startIsRefusedWhenEstimatedBytesExceedCap() {
        val estimated = StoragePolicy.estimateSegmentBytes(14_000_000, 60)
        val decision = StoragePolicy.canStartSegment(
            usageBytes = 4L * gb - estimated + 1,
            limitBytes = 4L * gb,
            estimatedBytes = estimated,
            availableBytes = Long.MAX_VALUE,
        )

        assertFalse(decision.proceed)
        assertTrue(decision.reason?.startsWith("STORAGE_CAP_EXCEEDED") == true)
    }

    @Test
    fun startIsRefusedWhenUsableSpaceIsBelowEstimatedPlusReserve() {
        val estimated = StoragePolicy.estimateSegmentBytes(40_000_000, 60)
        val required = estimated + StoragePolicy.SAFETY_RESERVE_BYTES
        val decision = StoragePolicy.canStartSegment(
            usageBytes = 1L * gb,
            limitBytes = 8L * gb,
            estimatedBytes = estimated,
            availableBytes = required - 1,
        )

        assertFalse(decision.proceed)
        assertTrue(decision.reason?.startsWith("DISK_SPACE_LOW") == true)
    }

    @Test
    fun startProceedsWhenWithinCapAndDiskReserve() {
        val estimated = StoragePolicy.estimateSegmentBytes(14_000_000, 60)
        val decision = StoragePolicy.canStartSegment(
            usageBytes = 2L * gb,
            limitBytes = 4L * gb,
            estimatedBytes = estimated,
            availableBytes = estimated + StoragePolicy.SAFETY_RESERVE_BYTES + 1,
        )

        assertTrue(decision.proceed)
        assertTrue(decision.reason == null)
    }

    @Test
    fun unknownDiskSpaceDoesNotBlockRecording() {
        val decision = StoragePolicy.canStartSegment(
            usageBytes = 2L * gb,
            limitBytes = 4L * gb,
            estimatedBytes = StoragePolicy.estimateSegmentBytes(14_000_000, 60),
            availableBytes = -1L,
        )

        assertTrue(decision.proceed)
    }

    @Test
    fun evictionTargetsRoomForNextSegmentNotJustCurrentLimit() {
        val estimated = StoragePolicy.estimateSegmentBytes(14_000_000, 60)
        val files = listOf(
            file("old.mp4", 500L * 1024L * 1024L, modified = 100),
            file("new.mp4", 3_500L * 1024L * 1024L, modified = 300),
        )

        // Usage == limit exactly, but the next segment needs `estimated` more bytes:
        // proactive eviction must free the oldest file down to limit - estimated.
        val target = 4L * gb - estimated
        val evictions = StoragePolicy.selectEvictionsToTarget(files, target)

        assertEquals(listOf("/segments/old.mp4"), evictions)
    }

    @Test
    fun inFlightAnalysisFilesAreNeverEvicted() {
        val files = listOf(
            file("old-in-flight.mp4", 2L * gb, modified = 100, analysisInFlight = true),
            file("new.mp4", 2L * gb, modified = 300),
        )

        val evictions = StoragePolicy.selectEvictionsToTarget(files, 2L * gb)

        assertEquals(listOf("/segments/new.mp4"), evictions)
    }

    @Test
    fun targetEvictionSkipsProtectedAndUnknownFiles() {
        val files = listOf(
            file("protected.mp4", 2L * gb, modified = 100, protected = true),
            file("no-sidecar.mp4", 2L * gb, modified = 200, hasSidecar = false),
        )

        val evictions = StoragePolicy.selectEvictionsToTarget(files, 0L)

        assertEquals(emptyList<String>(), evictions)
    }

    @Test
    fun uploadPinnedFilesAreNeverEvicted() {
        val files = listOf(
            file("pinned.mp4", 2L * gb, modified = 100, uploadPinned = true),
            file("new.mp4", 2L * gb, modified = 300),
        )

        val evictions = StoragePolicy.selectEvictionsToTarget(files, 2L * gb)

        assertEquals(listOf("/segments/new.mp4"), evictions)
    }

    @Test
    fun playingFilesAreNeverEvicted() {
        val files = listOf(
            file("playing.mp4", 2L * gb, modified = 100, playing = true),
            file("new.mp4", 2L * gb, modified = 300),
        )

        val evictions = StoragePolicy.selectEvictionsToTarget(files, 2L * gb)

        assertEquals(listOf("/segments/new.mp4"), evictions)
    }

    @Test
    fun retentionEvictsOnlyOrdinarySegmentsOlderThanTheCutoff() {
        val files = listOf(
            file("oldest.mp4", 1L, modified = 900, stoppedAtEpochMs = 100),
            file("old.mp4", 1L, modified = 100, stoppedAtEpochMs = 200),
            file("boundary.mp4", 1L, modified = 50, stoppedAtEpochMs = 300),
            file("new.mp4", 1L, modified = 10, stoppedAtEpochMs = 400),
        )

        val evictions = StoragePolicy.selectRetentionEvictions(files, cutoffEpochMs = 300)

        assertEquals(listOf("/segments/oldest.mp4", "/segments/old.mp4"), evictions)
    }

    @Test
    fun retentionNeverEvictsProtectedPinnedInFlightPlayingOrUnknownSegments() {
        val files = listOf(
            file("protected.mp4", 1L, 1, protected = true, stoppedAtEpochMs = 100),
            file("upload.mp4", 1L, 2, uploadPinned = true, stoppedAtEpochMs = 100),
            file("analysis.mp4", 1L, 3, analysisInFlight = true, stoppedAtEpochMs = 100),
            file("playing.mp4", 1L, 4, playing = true, stoppedAtEpochMs = 100),
            file("unknown.mp4", 1L, 5, hasSidecar = false, stoppedAtEpochMs = 100),
            file("missing-time.mp4", 1L, 6),
        )

        assertEquals(emptyList<String>(), StoragePolicy.selectRetentionEvictions(files, 300))
    }

    @Test
    fun retentionCutoffUsesHoursAndRejectsInvalidInputs() {
        val hourMs = 60L * 60L * 1000L

        assertEquals(2L * hourMs, StoragePolicy.retentionCutoffEpochMs(10L * hourMs, 8))
        assertEquals(null, StoragePolicy.retentionCutoffEpochMs(10L * hourMs, 0))
        assertEquals(null, StoragePolicy.retentionCutoffEpochMs(0L, 8))
    }
}
