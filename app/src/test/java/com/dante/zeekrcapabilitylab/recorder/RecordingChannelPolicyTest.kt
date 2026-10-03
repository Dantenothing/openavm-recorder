package com.dante.zeekrcapabilitylab.recorder

import com.dante.zeekrcapabilitylab.probe.camera.CameraFormatProfile
import com.dante.zeekrcapabilitylab.probe.camera.ProfileSize
import com.dante.zeekrcapabilitylab.service.recorder.*
import org.junit.Assert.*
import org.junit.Test

class RecordingChannelPolicyTest {
    private val session = "12345678-1234-1234-1234-123456789abc"
    private fun config() = RecorderConfig(SessionSourceSnapshot(RecordingSourceRole.SURROUND, "2",
        CameraFormatProfile(ProfileSize(1280, 5140), 14_000_000), RecordingLayoutKind.FOUR_LANE_V1),
        segmentSeconds = 60, storageLimitBytes = 15L * 1024 * 1024 * 1024,
        auxiliaryChannels = listOf(RecordingSourceRole.CABIN, RecordingSourceRole.IR))

    @Test fun optionalChannelsOnlyApplyToNormalSurroundStarts() {
        val cfg = config()
        assertEquals(listOf(RecordingSourceRole.CABIN, RecordingSourceRole.IR), RecordingChannelPolicy.roles(cfg))
        assertTrue(RecordingChannelPolicy.roles(cfg.copy(recordingMode = RecordingMode.TIME_LAPSE)).isEmpty())
        assertTrue(RecordingChannelPolicy.roles(cfg.copy(source = cfg.source.copy(sourceRole = RecordingSourceRole.CABIN))).isEmpty())
        assertTrue(RecordingChannelPolicy.roles(cfg.copy(auxiliaryChannels = emptyList())).isEmpty())
        assertTrue(cfg.validate().isEmpty())
        assertFalse(cfg.copy(auxiliaryChannels = listOf(RecordingSourceRole.CABIN, RecordingSourceRole.CABIN)).validate().isEmpty())
        assertFalse(cfg.copy(auxiliaryChannels = listOf(RecordingSourceRole.SURROUND)).validate().isEmpty())
    }

    @Test fun receivingOrEncoderOutputAloneNeverClaimsWrittenFrames() {
        val old = RecordingChannelHealth(session, RecordingChannelId.FRONT_CABIN)
        val running = RecorderState(status = RecorderStatus.RECORDING, libraryRevision = 100)
        val received = RecordingChannelPolicy.update(old, running,
            CaptureEvidence(session, frames = SegmentFrameStats(100), lastReceivedElapsedMs = 1_000,
                encoder = EncoderEvidence(frames = 99, completedFiles = 0)), 1_001)
        assertEquals("RECEIVING", received.status)
        assertEquals(0L, received.writtenFrames)
        assertEquals(0L, received.completedFiles)
        val written = RecordingChannelPolicy.update(received, running,
            CaptureEvidence(session, frames = SegmentFrameStats(101), lastReceivedElapsedMs = 1_010,
                encoder = EncoderEvidence(frames = 100, writtenFrames = 90, lastWriteElapsedMs = 1_009, completedFiles = 2)), 1_011)
        assertEquals("WRITING", written.status)
        assertEquals(90L, written.writtenFrames)
        assertEquals(2L, written.completedFiles)
        assertEquals(1_009L, written.lastWriteElapsedMs)
        val stalledWriter = RecordingChannelPolicy.update(written, running,
            CaptureEvidence(session, frames = SegmentFrameStats(200), lastReceivedElapsedMs = 7_000,
                encoder = EncoderEvidence(frames = 200, writtenFrames = 90, lastWriteElapsedMs = 1_009)), 7_010)
        assertEquals("INTERRUPTED", stalledWriter.status)
    }

    @Test fun staleFramesFailuresAndCleanupDoNotKeepWritingLabel() {
        assertEquals("STARTING", RecordingChannelPolicy.health(RecorderStatus.STARTING, 0, 0, null, 100, null))
        assertEquals("INTERRUPTED", RecordingChannelPolicy.health(RecorderStatus.RECORDING, 100, 90, 1_000, 6_001, null))
        assertEquals("INTERRUPTED", RecordingChannelPolicy.health(RecorderStatus.WAITING_CAMERA, 100, 90, 1_000, 1_010, null))
        assertEquals("UNAVAILABLE", RecordingChannelPolicy.health(RecorderStatus.ERROR, 0, 0, null, 100, "CAMERA_BUSY"))
        assertEquals("INTERRUPTED", RecordingChannelPolicy.health(RecorderStatus.STOPPED, 100, 90, 1_000, 7_000, "WRITE_FAILED"))
        assertEquals("STOPPING", RecordingChannelPolicy.health(RecorderStatus.FINALIZING, 100, 90, 1_000, 7_000, null))
        assertEquals("STOPPED", RecordingChannelPolicy.health(RecorderStatus.STOPPED, 100, 90, 1_000, 7_000, null))
        val health = RecordingChannelPolicy.update(RecordingChannelHealth(session, RecordingChannelId.FRONT_CABIN),
            RecorderState(status = RecorderStatus.ERROR, lastError = "/private/path or details", cleanupUnconfirmed = true), null, 100)
        assertEquals("RECORDER_ERROR", health.errorCode)
        assertTrue(health.cleanupUnconfirmed)
    }

    @Test fun ownerSlotsRequireExactNativeAcknowledgementAndCannotAccumulate() {
        val slots = RecordingChannelAdmission()
        val a = Any(); val b = Any(); val stale = Any()
        assertTrue(slots.admit("1", a))
        assertFalse(slots.admit("1", b))
        assertTrue(slots.admit("0", b))
        assertFalse(slots.admit("3", stale))
        assertFalse(slots.release("1", a, confirmed = false))
        assertFalse(slots.release("1", stale, confirmed = true))
        assertEquals(2, slots.count())
        assertTrue(slots.release("1", a, confirmed = true))
        assertTrue(slots.admit("1", stale))
        assertFalse(slots.release("1", a, confirmed = true))
        assertEquals(2, slots.count())
    }

    @Test fun sharedIdentityRequiresValidSessionOriginAndDistinctStableChannelNames() {
        val origin = RecordingChannelIdentity(session, 1_000, RecordingChannelId.SURROUND)
        assertEquals(origin.sessionId, RecordingChannelIdentity(session, 1_000, RecordingChannelId.REAR_CABIN).sessionId)
        assertEquals(RecordingChannelId.FRONT_CABIN, RecordingChannelId.forRole(RecordingSourceRole.CABIN))
        assertTrue(runCatching { RecordingChannelIdentity("unknown", 1_000, RecordingChannelId.SURROUND) }.isFailure)
        assertTrue(runCatching { RecordingChannelIdentity(session, -1, RecordingChannelId.SURROUND) }.isFailure)
        assertTrue(runCatching { RecordingChannelIdentity(session, 1_000, "arbitrary") }.isFailure)
        val identity = RecordingSessionIdentity { "new-id" }
        assertEquals(session, identity.beginNewSession(origin.sessionId))
        identity.endSession()
        assertEquals("new-id", identity.beginNewSession())
    }

    @Test fun normalStopDropsServiceObserverOnlyAfterFinalCountersSettle() {
        assertTrue(RecordingChannelRetention.retainPrimary(false, RecorderStatus.RECORDING, false, false, 0, 50_000))
        assertTrue(RecordingChannelRetention.retainPrimary(true, RecorderStatus.FINALIZING, true, false, 20_000, 19_999))
        assertFalse(RecordingChannelRetention.retainPrimary(true, RecorderStatus.STOPPED, false, false, 20_000, 1_000))
        assertFalse(RecordingChannelRetention.retainPrimary(false, RecorderStatus.IDLE, false, false, 0, 1_000))
    }

    @Test fun blockedCleanupCannotRetainPresentationObserverBeyondStopWindow() {
        assertTrue(RecordingChannelRetention.retainPrimary(true, RecorderStatus.STOPPED, true, true, 20_000, 19_999))
        assertFalse(RecordingChannelRetention.retainPrimary(true, RecorderStatus.FINALIZING, true, true, 20_000, 20_000))
        assertFalse(RecordingChannelRetention.retainPrimary(true, RecorderStatus.RECORDING, false, false, 20_000, 20_001))
        // Separate camera admission remains held until that exact native owner acknowledges.
        val slots = RecordingChannelAdmission(); val owner = Any()
        assertTrue(slots.admit("1", owner))
        assertFalse(slots.release("1", owner, confirmed = false))
        assertEquals(1, slots.count())
        assertTrue(RecordingChannelRetention.pollAuxiliary(false, false))
        assertTrue(RecordingChannelRetention.pollAuxiliary(true, false))
        assertFalse(RecordingChannelRetention.pollAuxiliary(true, true))
    }
}
