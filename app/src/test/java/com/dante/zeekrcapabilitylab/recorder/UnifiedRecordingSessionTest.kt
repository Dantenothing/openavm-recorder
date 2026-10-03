package com.dante.zeekrcapabilitylab.recorder

import com.dante.zeekrcapabilitylab.product.*
import com.dante.zeekrcapabilitylab.service.recorder.*
import com.dante.zeekrcapabilitylab.probe.camera.CameraFormatProfile
import com.dante.zeekrcapabilitylab.probe.camera.ProfileSize
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class UnifiedRecordingSessionTest {
    private val id = "00000000-0000-4000-8000-000000000001"
    private fun metadata(role: RecordingSourceRole) = SegmentSidecar(file = role.name, cameraId = role.name,
        profile = CameraFormatProfile(ProfileSize(640, 480), 2_000_000), sourceRole = role,
        layoutKind = if (role == RecordingSourceRole.SURROUND) RecordingLayoutKind.FOUR_LANE_V1 else RecordingLayoutKind.SINGLE_V1,
        segmentSeconds = 60, segmentNumber = 1, processStartId = "1-1", recordingSessionId = id,
        startedAtElapsedRealtimeMs = 1_000, stoppedAtElapsedRealtimeMs = 11_000,
        startedAtEpochMs = 1_000, stoppedAtEpochMs = 11_000,
        result = "SUCCESS", actualTrack = ActualTrackInfo(durationMs = 10_000))
    private val front = File("front.mp4")
    private val rear = File("rear.mp4")
    private val surround = File("surround.mp4")
    private fun internal() = RecorderLibrary.Recording("session:$id", listOf(front to metadata(RecordingSourceRole.CABIN), rear to metadata(RecordingSourceRole.IR)))
    private fun usb() = VehicleUsbRecording("usb:$id", "session:$id", "volume", "USB", VehicleMediaCategory.NORMAL,
        availability = MediaAvailability.ONLINE, startedAtEpochMs = 1_000, stoppedAtEpochMs = 11_000,
        totalBytes = 100, files = listOf(surround), sourceRole = RecordingSourceRole.SURROUND,
        layoutKind = RecordingLayoutKind.FOUR_LANE_V1, timeLapseMultiplier = 1, containsDirectRecording = true)

    @Test fun parallelInternalLibraryDurationIsOneSessionNotSummedFiles() {
        assertEquals(10_000L, internal().encodedDurationMs)
        assertEquals(10_000L, internal().realDurationMs)
    }

    @Test fun exactSessionJoinsSplitStorageAndRetainsSeparateOwnership() {
        val group = UnifiedRecordingSession.group(listOf(internal()), listOf(usb())).single()
        assertEquals(setOf(front, rear, surround), group.files.toSet())
        assertEquals(listOf(front, rear), group.internal.files)
        assertEquals(listOf(surround), group.usb.single().files)
        assertFalse(group.hasOfflineFiles)
        assertTrue(UnifiedRecordingSession.group(listOf(internal()), listOf(usb().copy(logicalId = "session:another"))).isEmpty())
    }

    @Test fun offlineUsbIsExplicitAndCannotBorrowAFileOrHideInternalFootage() {
        val group = UnifiedRecordingSession.group(listOf(internal()), listOf(usb().copy(availability = MediaAvailability.OFFLINE))).single()
        assertEquals(setOf(front, rear), group.files.toSet())
        assertTrue(group.hasOfflineFiles)
    }

    @Test fun ordinaryUsbCopiesAndTimelapseAreNotMistakenForParallelNewChannels() {
        assertTrue(UnifiedRecordingSession.group(listOf(internal()), listOf(usb().copy(containsDirectRecording = false))).isEmpty())
        assertTrue(UnifiedRecordingSession.group(listOf(internal()), listOf(usb().copy(category = VehicleMediaCategory.TIME_LAPSE))).isEmpty())
    }

    @Test fun onlineSurroundProvidesTheEstablishedCoverAndInitialPlayback() {
        val group = UnifiedRecordingSession.group(listOf(internal()), listOf(usb())).single()
        assertEquals(surround, group.coverSource?.file)
        assertEquals(RecordingLayoutKind.FOUR_LANE_V1, group.coverSource?.layoutKind)
        assertEquals(RecordingSourceRole.SURROUND, group.coverSource?.sourceRole)
        assertEquals(surround, group.initialFile)
        assertEquals(3, group.sourceRoles.size)
    }

    @Test fun offlineSurroundFallsBackToARealInternalCoverAndKeepsOfflineStatus() {
        val group = UnifiedRecordingSession.group(listOf(internal()), listOf(usb().copy(availability = MediaAvailability.OFFLINE))).single()
        assertEquals(front, group.coverSource?.file)
        assertEquals(front, group.initialFile)
        assertEquals(RecordingSourceRole.CABIN, group.coverSource?.sourceRole)
        assertEquals(RecordingLayoutKind.SINGLE_V1, group.coverSource?.layoutKind)
        assertTrue(group.hasOfflineFiles)
    }
}
