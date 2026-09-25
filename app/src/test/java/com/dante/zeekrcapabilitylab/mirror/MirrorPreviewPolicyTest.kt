package com.dante.zeekrcapabilitylab.mirror

import org.junit.Assert.*
import org.junit.Test
import com.dante.zeekrcapabilitylab.service.recorder.*

class MirrorPreviewPolicyTest {
    @Test fun callbackActivityCannotDisguiseFrozenOrInvalidTextureTimestamps() {
        val frames = MirrorFrameFreshness()
        frames.presentationChanged(0)
        frames.frame(42, 100)
        frames.frame(42, 8_200)
        frames.frame(0, 8_210)
        assertEquals(3L, frames.callbacks)
        assertEquals(1L, frames.frames)
        assertEquals(1L, frames.duplicateTimestampCallbacks)
        assertEquals(1L, frames.invalidTimestampCallbacks)
        assertEquals(10L, frames.callbackAge(8_220))
        assertEquals(8_120L, frames.age(8_220))
        assertTrue(frames.outputTimedOut(8_220))
        assertFalse(frames.isFresh(8_220))
        assertNull(frames.callbackAge(8_000))
    }
    @Test fun recoveryGetsANewConsumerButSegmentRotationKeepsTheSameConsumer() {
        val first = RecorderState(status = RecorderStatus.RECORDING, recordingSessionId = "run",
            mirrorPreviewManaged = true, cameraGeneration = 7)
        val key = MirrorPreviewPolicy.cameraKey(first)
        assertEquals(key, MirrorPreviewPolicy.cameraKey(first.copy(status = RecorderStatus.FINALIZING)))
        assertEquals(key, MirrorPreviewPolicy.cameraKey(first.copy(segmentNumber = 9)))
        assertNull(MirrorPreviewPolicy.cameraKey(first.copy(status = RecorderStatus.WAITING_CAMERA)))
        assertNull(MirrorPreviewPolicy.cameraKey(first.copy(status = RecorderStatus.RESUMING)))
        assertNotEquals(key, MirrorPreviewPolicy.cameraKey(first.copy(cameraGeneration = 8)))
        assertNull(MirrorPreviewPolicy.cameraKey(first.copy(status = RecorderStatus.STOPPED)))
        assertNull(MirrorPreviewPolicy.cameraKey(first.copy(cameraGeneration = 0)))
    }
    @Test fun oldMirrorCommandsCannotDisableOrReplaceTheRecoveredPreview() {
        val state = RecorderState(recordingSessionId = "run", cameraGeneration = 8)
        assertFalse(MirrorPreviewPolicy.commandMatches("run", 7, state))
        assertFalse(MirrorPreviewPolicy.commandMatches("older-run", 8, state))
        assertTrue(MirrorPreviewPolicy.commandMatches("run", 8, state))
    }
    @Test fun recordingDoesNotDowngradeTheCompositePreviewToFourLowResolutionStrips() {
        val full = com.dante.zeekrcapabilitylab.probe.camera.ProfileSize(1280, 5140)
        val small = com.dante.zeekrcapabilitylab.probe.camera.ProfileSize(640, 480)
        assertEquals(full, MirrorPreviewPolicy.previewSize(listOf(small, full), full))
    }
    @Test fun missingHighResolutionDeclarationDisablesMirrorInsteadOfSilentlyBlurringIt() {
        assertNull(MirrorPreviewPolicy.previewSize(
            listOf(com.dante.zeekrcapabilitylab.probe.camera.ProfileSize(640, 480)),
            com.dante.zeekrcapabilitylab.probe.camera.ProfileSize(1280, 5140)))
    }
    @Test fun screenOffOrKeyguardDeniesBothDisplayDestinationsWithoutRevivingAStoppedRun() {
        assertEquals(MirrorDestination.NONE, MirrorPreviewPolicy.destination(true, false, false, true, false, displayUsable = false))
        assertEquals(MirrorDestination.NONE, MirrorPreviewPolicy.destination(true, true, true, true, false, displayUsable = false))
        assertEquals(MirrorDestination.NONE, MirrorPreviewPolicy.destination(false, false, false, true, false, displayUsable = true))
    }
    @Test fun frozenConsumerTimesOutWithoutTreatingRepeatedTimestampAsProgress() {
        val frames = MirrorFrameFreshness()
        frames.presentationChanged(100); frames.frame(1, 200)
        assertFalse(frames.outputTimedOut(2200))
        frames.frame(1, 2201)
        assertFalse(frames.outputTimedOut(2201))
        assertTrue(frames.outputTimedOut(8201))
    }
    @Test fun briefStallHidesStalePixelsButDoesNotPermanentlyRetireTheConsumer() {
        val frames = MirrorFrameFreshness()
        frames.presentationChanged(100); frames.frame(1, 200)
        assertFalse(frames.isFresh(2500))
        assertFalse(frames.outputTimedOut(2500))
        frames.frame(2, 2600)
        assertTrue(frames.isFresh(2601))
        assertFalse(frames.outputTimedOut(2601))
    }
    @Test fun missedFinalizingTickStillGrantsTheNextSegmentItsFirstFrameWindow() {
        val frames = MirrorFrameFreshness()
        frames.recorderProgress(1, true, 0); frames.frame(1, 100)
        frames.recorderProgress(2, true, 9_000)
        assertFalse(frames.isFresh(9_000))
        assertFalse(frames.outputTimedOut(9_000))
        frames.recorderProgress(2, true, 17_001)
        assertTrue(frames.outputTimedOut(17_001)) // Same segment cannot reset the deadline forever.
        frames.frame(2, 17_002); assertTrue(frames.isFresh(17_002))
    }
    @Test fun newHostHasBoundedFirstFrameGraceAndSegmentTransitionCannotImmediatelyTimeout() {
        val frames = MirrorFrameFreshness()
        frames.presentationChanged(100)
        assertFalse(frames.outputTimedOut(8100)); assertTrue(frames.outputTimedOut(8101))
        frames.frame(1, 8200); frames.presentationChanged(9000)
        assertFalse(frames.outputTimedOut(9000))
        assertFalse(frames.outputTimedOut(16999)); assertTrue(frames.outputTimedOut(17001))
        frames.frame(2, 17002); assertFalse(frames.outputTimedOut(17002))
    }
    @Test fun homeAndOverlayAreMutuallyExclusiveEvenDuringDelayedLifecycleNotification() {
        assertEquals(MirrorDestination.HOME, MirrorPreviewPolicy.destination(true, true, true, true, false))
        assertEquals(MirrorDestination.OVERLAY, MirrorPreviewPolicy.destination(true, false, true, true, false))
        assertEquals(MirrorDestination.NONE, MirrorPreviewPolicy.destination(true, true, false, true, false))
    }
    @Test fun permissionAndDismissalCannotStartOrRestoreAnyCamera() {
        assertEquals(MirrorDestination.NONE, MirrorPreviewPolicy.destination(false, false, true, true, false))
        assertEquals(MirrorDestination.NONE, MirrorPreviewPolicy.destination(true, false, true, false, false))
        assertEquals(MirrorDestination.NONE, MirrorPreviewPolicy.destination(true, false, true, true, true))
        assertEquals(MirrorDestination.HOME, MirrorPreviewPolicy.destination(true, true, true, true, true))
    }
    @Test fun continuousFileBoundaryRetainsFreshPictureButStillDetectsStaleness() {
        val clock = MirrorFrameFreshness()
        clock.recorderProgress(1, true, 100, continuousInput = true)
        clock.frame(10, 101)
        clock.recorderProgress(2, true, 110, continuousInput = true)
        assertTrue(clock.isFresh(110))
        assertFalse(clock.isFresh(2102))
        assertTrue(clock.outputTimedOut(8200))
        clock.frame(11, 8300); assertTrue(clock.isFresh(8300))
    }
    @Test fun legacyRestartAndExplicitWindowChangesStillRequireANewFrame() {
        val clock = MirrorFrameFreshness()
        clock.recorderProgress(1, true, 100)
        clock.frame(10, 101)
        clock.recorderProgress(2, true, 110)
        assertFalse(clock.isFresh(110))
        clock.frame(11, 111); assertTrue(clock.isFresh(111))
        clock.presentationChanged(120); assertFalse(clock.isFresh(120))
    }
    @Test fun repaintingTheSameTextureTimestampDoesNotMakeAFrozenImageLive() {
        val clock = MirrorFrameFreshness()
        assertFalse(clock.isFresh(100))
        clock.frame(10, 100); assertTrue(clock.isFresh(101))
        clock.frame(10, 2101); assertFalse(clock.isFresh(2101))
        assertEquals(1L, clock.frames)
        clock.frame(11, 2200); assertTrue(clock.isFresh(2200))
    }
    @Test fun returningToAWindowNeedsANewFrameAndClockRollbackIsUnavailable() {
        val clock = MirrorFrameFreshness()
        clock.frame(3, 200); clock.presentationChanged()
        clock.frame(3, 201); assertFalse(clock.isFresh(201))
        clock.frame(4, 202); assertTrue(clock.isFresh(202))
        assertFalse(clock.isFresh(201)); assertNull(clock.age(201))
        clock.frame(0, 300); assertEquals(2L, clock.frames)
    }
    @Test fun retirementWaitsForProducerEvenWhenWindowIsAlreadyGone() {
        var releases = 0
        val lease = RetainedPreviewLease { releases++ }
        lease.attach(); lease.acquireProducer(); lease.detach(); lease.retire()
        assertEquals(0, releases)
        lease.producerReleased(); assertEquals(1, releases)
        lease.detach(); lease.retire(); lease.producerReleased(); assertEquals(1, releases)
    }
    @Test fun producerCompletionCannotDestroyAnAttachedConsumer() {
        var releases = 0
        val lease = RetainedPreviewLease { releases++ }
        lease.attach(); lease.acquireProducer(); lease.retire(); lease.producerReleased()
        assertEquals(0, releases)
        lease.detach(); assertEquals(1, releases)
    }
    @Test fun homeOverlayMovesKeepOneProducerAndNoReleaseUntilTerminalEvidence() {
        var releases = 0
        val lease = RetainedPreviewLease { releases++ }
        lease.attach(); lease.acquireProducer()
        repeat(100) { lease.detach(); lease.attach() }
        assertEquals(0, releases)
        lease.retire(); lease.detach(); assertEquals(0, releases)
        lease.producerReleased(); assertEquals(1, releases)
    }
    @Test fun neverSubmittedConsumerCanBeDisposedWithoutCameraAcknowledgement() {
        var releases = 0
        val lease = RetainedPreviewLease { releases++ }
        lease.attach(); lease.retire(); lease.detach()
        assertEquals(1, releases)
    }
    @Test(expected = IllegalStateException::class) fun lateAttachCannotResurrectARetiredOutput() {
        val lease = RetainedPreviewLease { }
        lease.attach(); lease.acquireProducer(); lease.retire(); lease.attach()
    }
    @Test fun squareLaneIsLetterboxedWithoutStretching() {
        assertArrayEquals(floatArrayOf(50f, 0f, 150f, 0f, 150f, 100f, 50f, 100f),
            MirrorGeometry.corners(200f, 100f, 1f, 0, false), 0.001f)
    }
    @Test fun rotationThenMirrorMapsAsymmetricMarkersInDisplayCoordinates() {
        assertArrayEquals(floatArrayOf(100f, 0f, 100f, 200f, 0f, 200f, 0f, 0f),
            MirrorGeometry.corners(100f, 200f, 2f, 90, false), 0.001f)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 200f, 100f, 200f, 100f, 0f),
            MirrorGeometry.corners(100f, 200f, 2f, 90, true), 0.001f)
    }
    @Test fun everyOrientationStaysWithinBoundsAndHasNonzeroArea() {
        for (rotation in listOf(0, 90, 180, 270)) for (mirror in listOf(false, true)) {
            val points = MirrorGeometry.corners(321f, 177f, 1.2f, rotation, mirror)
            assertTrue(points.toList().chunked(2).all { it[0] in 0f..321f && it[1] in 0f..177f })
            val cross = (points[2] - points[0]) * (points[5] - points[3]) - (points[3] - points[1]) * (points[4] - points[2])
            assertTrue(kotlin.math.abs(cross) > 1)
        }
    }
}
