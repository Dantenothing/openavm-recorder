package com.dante.zeekrcapabilitylab.mirror

import com.dante.zeekrcapabilitylab.probe.camera.CameraFormatProfile
import com.dante.zeekrcapabilitylab.probe.camera.ProfileSize
import com.dante.zeekrcapabilitylab.service.recorder.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MirrorProfilePreparationTest {
    private fun valid() = RecorderConfig(SessionSourceSnapshot(RecordingSourceRole.SURROUND, "2",
        CameraFormatProfile(ProfileSize(1280, 5140), 28_000_000), RecordingLayoutKind.FOUR_LANE_V1),
        120, 15L * 1024 * 1024 * 1024, mirrorPreviewEnabled = true)

    @Test fun wakeWaitsForMetadataInsteadOfFailingAtTheFirstEmptySnapshot() = runTest {
        var reads = 0
        val result = MirrorProfilePreparation.prepare(RecordingSourceRole.SURROUND, true,
            { testScheduler.currentTime }, { true }, { if (++reads < 3) null else valid() })
        assertTrue("A short camera catalog wake delay must not consume the entire return attempt", result is MirrorProfileResult.Ready)
        assertEquals(3, reads)
        assertEquals(1_500L, testScheduler.currentTime)
    }
    @Test fun permanentMissingMetadataIsBoundedAndDoesNotInventACameraProfile() = runTest {
        var reads = 0
        val result = MirrorProfilePreparation.prepare(RecordingSourceRole.SURROUND, true,
            { testScheduler.currentTime }, { true }, { reads++; null })
        assertEquals(MirrorProfileResult.Failed("NO_SURROUND_PROFILE_AFTER_WAKE"), result)
        assertEquals(15_000L, testScheduler.currentTime)
        assertEquals(20, reads)
    }
    @Test fun ordinaryRequestsStillFailPromptlyWhenNoProfileExists() = runTest {
        var reads = 0
        assertEquals(MirrorProfileResult.Failed("NO_SURROUND_PROFILE"), MirrorProfilePreparation.prepare(
            RecordingSourceRole.SURROUND, false, { testScheduler.currentTime }, { true }, { reads++; null }))
        assertEquals(1, reads); assertEquals(0L, testScheduler.currentTime)
    }
    @Test fun aRevokedReturnPermitPreventsAnyFurtherMetadataLookup() = runTest {
        var reads = 0
        var allowed = true
        val result = MirrorProfilePreparation.prepare(RecordingSourceRole.SURROUND, true,
            { testScheduler.currentTime }, { allowed }, { reads++; allowed = false; null })
        assertEquals(MirrorProfileResult.Cancelled, result); assertEquals(1, reads)
    }
    @Test fun anInvalidConfigIsAValidationErrorAndDoesNotRetry() = runTest {
        var reads = 0
        val result = MirrorProfilePreparation.prepare(RecordingSourceRole.SURROUND, true,
            { testScheduler.currentTime }, { true }, { reads++; valid().copy(requestedFrameRate = 7) })
        assertEquals(MirrorProfileResult.Failed("INVALID_SURROUND_CONFIG"), result)
        assertEquals(1, reads); assertEquals(0L, testScheduler.currentTime)
    }
    @Test fun permissionOrScreenRevokedWhileReadingCannotAcceptALateValidConfig() = runTest {
        var allowed = true
        val result = MirrorProfilePreparation.prepare(RecordingSourceRole.SURROUND, true,
            { testScheduler.currentTime }, { allowed }, { delay(100); allowed = false; valid() })
        assertEquals(MirrorProfileResult.Cancelled, result)
    }
    @Test fun metadataArrivingAfterTheWakeDeadlineCannotStartAnything() = runTest {
        val result = MirrorProfilePreparation.prepare(RecordingSourceRole.SURROUND, true,
            { testScheduler.currentTime }, { true }, { delay(16_000); valid() })
        assertEquals(MirrorProfileResult.Failed("NO_SURROUND_PROFILE_AFTER_WAKE"), result)
    }
    @Test fun cancellationIsNeverConvertedIntoAnotherMetadataRetry() = runTest {
        var reads = 0
        try {
            MirrorProfilePreparation.prepare(RecordingSourceRole.SURROUND, true,
                { testScheduler.currentTime }, { true }, { reads++; throw CancellationException("screen off") })
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { assertEquals(1, reads) }
    }
    @Test fun anAvailableCabinCameraCannotBeUsedAsASurroundFallback() = runTest {
        val config = valid()
        assertEquals(MirrorProfileResult.Failed("INVALID_SURROUND_CONFIG"), MirrorProfilePreparation.prepare(
            RecordingSourceRole.SURROUND, true, { testScheduler.currentTime }, { true },
            { config.copy(source = config.source.copy(sourceRole = RecordingSourceRole.CABIN)) }))
    }
}
