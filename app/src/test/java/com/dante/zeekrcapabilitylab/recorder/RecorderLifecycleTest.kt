package com.dante.zeekrcapabilitylab.recorder

import com.dante.zeekrcapabilitylab.service.recorder.FinalizeOutcome
import com.dante.zeekrcapabilitylab.service.recorder.FinalizePolicy
import com.dante.zeekrcapabilitylab.service.recorder.RecorderLifecycleOrder
import com.dante.zeekrcapabilitylab.service.recorder.RecorderTransitionPolicy
import com.dante.zeekrcapabilitylab.service.recorder.SegmentGuardPolicy
import com.dante.zeekrcapabilitylab.service.recorder.SegmentGapPolicy
import com.dante.zeekrcapabilitylab.service.recorder.SidecarProtectionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RecorderLifecycleTest {

    @Test
    fun ioShutdownRequiresFinalizeCommittedAndTeardownComplete() {
        assertFalse(RecorderLifecycleOrder.canShutdownIo(finalizeCommitted = false, teardownComplete = false))
        assertFalse(RecorderLifecycleOrder.canShutdownIo(finalizeCommitted = false, teardownComplete = true))
        assertFalse(RecorderLifecycleOrder.canShutdownIo(finalizeCommitted = true, teardownComplete = false))
        assertTrue(RecorderLifecycleOrder.canShutdownIo(finalizeCommitted = true, teardownComplete = true))
    }

    @Test
    fun gapUsesElapsedRealtimeBetweenSegments() {
        assertEquals(5_000L, SegmentGapPolicy.gapMs(previousStoppedElapsedMs = 1_000, currentStartedElapsedMs = 6_000))
        assertEquals(-3_000L, SegmentGapPolicy.gapMs(previousStoppedElapsedMs = 6_000, currentStartedElapsedMs = 3_000))
        assertNull(SegmentGapPolicy.gapMs(previousStoppedElapsedMs = null, currentStartedElapsedMs = 6_000))
        assertNull(SegmentGapPolicy.gapMs(previousStoppedElapsedMs = 1_000, currentStartedElapsedMs = null))
    }

    @Test
    fun finalizeSuccessRequiresValidPartialAndRename() {
        assertEquals(
            FinalizeOutcome(success = true, error = null),
            FinalizePolicy.outcome(
                stopError = null,
                partialExists = true,
                partialBytes = 1024,
                renameSucceeded = true,
            ),
        )
    }

    @Test
    fun finalizeFailureAlwaysCarriesConcreteError() {
        assertEquals(
            FinalizeOutcome(success = false, error = "stop exploded"),
            FinalizePolicy.outcome(
                stopError = "stop exploded",
                partialExists = true,
                partialBytes = 1024,
                renameSucceeded = false,
            ),
        )
        assertEquals(
            FinalizeOutcome(success = false, error = FinalizePolicy.ERROR_MISSING),
            FinalizePolicy.outcome(
                stopError = null,
                partialExists = false,
                partialBytes = 0,
                renameSucceeded = false,
            ),
        )
        assertEquals(
            FinalizeOutcome(success = false, error = FinalizePolicy.ERROR_EMPTY),
            FinalizePolicy.outcome(
                stopError = null,
                partialExists = true,
                partialBytes = 0,
                renameSucceeded = false,
            ),
        )
        assertEquals(
            FinalizeOutcome(success = false, error = FinalizePolicy.ERROR_RENAME),
            FinalizePolicy.outcome(
                stopError = null,
                partialExists = true,
                partialBytes = 2048,
                renameSucceeded = false,
            ),
        )
    }

    @Test
    fun stopIsInvokedOnlyForSegmentsThatActuallyStarted() {
        assertTrue(RecorderTransitionPolicy.shouldInvokeStop(wasRecording = true))
        assertFalse(RecorderTransitionPolicy.shouldInvokeStop(wasRecording = false))
    }

    @Test
    fun cameraLossFinalizesAnyOwnedPartialIncludingPreparing() {
        assertTrue(SegmentGuardPolicy.shouldFinalizeOnLoss(hasCurrentPartial = true))
        assertFalse(SegmentGuardPolicy.shouldFinalizeOnLoss(hasCurrentPartial = false))
    }

    @Test
    fun setupOwnershipRequiresGenerationAndSamePartial() {
        val partial = File("/segments/seg-0001-1-1280x5140-14M.mp4.partial")
        val other = File("/segments/seg-0002-2-1280x5140-14M.mp4.partial")

        assertTrue(SegmentGuardPolicy.ownsSetup(3, 3, partial, partial))
        assertFalse(SegmentGuardPolicy.ownsSetup(2, 3, partial, partial))
        assertFalse(SegmentGuardPolicy.ownsSetup(3, 3, other, partial))
        assertFalse(SegmentGuardPolicy.ownsSetup(3, 3, null, partial))
    }

    @Test
    fun sidecarEnrichmentMergesBookmarkProtection() {
        assertTrue(SidecarProtectionPolicy.effectiveProtected(existingProtected = true, snapshotProtected = false))
        assertTrue(SidecarProtectionPolicy.effectiveProtected(existingProtected = false, snapshotProtected = true))
        assertTrue(SidecarProtectionPolicy.effectiveProtected(existingProtected = true, snapshotProtected = true))
        assertFalse(SidecarProtectionPolicy.effectiveProtected(existingProtected = false, snapshotProtected = false))
        assertFalse(SidecarProtectionPolicy.effectiveProtected(existingProtected = null, snapshotProtected = false))
    }
}
