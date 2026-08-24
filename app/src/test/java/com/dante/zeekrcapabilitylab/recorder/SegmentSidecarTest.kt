package com.dante.zeekrcapabilitylab.recorder

import com.dante.zeekrcapabilitylab.probe.camera.CameraFormatProfile
import com.dante.zeekrcapabilitylab.probe.camera.ProfileSize
import com.dante.zeekrcapabilitylab.service.recorder.ActualTrackInfo
import com.dante.zeekrcapabilitylab.service.recorder.FrameHealthReport
import com.dante.zeekrcapabilitylab.service.recorder.SegmentFrameStats
import com.dante.zeekrcapabilitylab.service.recorder.SegmentSidecar
import com.dante.zeekrcapabilitylab.service.recorder.SegmentSidecarIO
import com.dante.zeekrcapabilitylab.service.recorder.RecordingLayoutKind
import com.dante.zeekrcapabilitylab.service.recorder.RecordingSourceRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SegmentSidecarTest {

    private val profile = CameraFormatProfile(ProfileSize(1280, 5140), 14_000_000)

    private fun sampleSidecar(mp4: File, protected: Boolean = false) = SegmentSidecar(
        file = mp4.absolutePath,
        cameraId = "2",
        profile = profile,
        segmentSeconds = 60,
        segmentNumber = 4,
        processStartId = "12345-1700000000000",
        startedAtEpochMs = 1000,
        stoppedAtEpochMs = 61_000,
        startedAtElapsedRealtimeMs = 500,
        stoppedAtElapsedRealtimeMs = 60_500,
        gapFromPreviousMs = 12,
        result = SegmentSidecar.RESULT_SUCCESS,
        error = null,
        fileBytes = 104_000_000,
        protected = protected,
        actualTrack = ActualTrackInfo(width = 1280, height = 5140, bitrateBps = 14_100_000, durationMs = 60_003),
        frameStats = SegmentFrameStats(count = 1800, firstTimestampNs = 1, lastTimestampNs = 1_800_000_000, maxGapNs = 33_400_000),
        frameHealth = FrameHealthReport(
            status = FrameHealthReport.STATUS_OK,
            sampledFrames = 3,
            averageBrightness = 128.0,
            firstAhash = 0x1234,
            maxHammingDistance = 5,
            blackSuspected = false,
            frozenSuspected = false,
        ),
    )

    @Test
    fun sidecarRoundTripsThroughJson() {
        val mp4 = tempMp4()
        val sidecar = sampleSidecar(mp4)

        val file = SegmentSidecarIO.writeAtomic(mp4, sidecar)
        val decoded = SegmentSidecarIO.read(file)

        assertEquals(sidecar, decoded)
        assertEquals(4, decoded?.segmentNumber)
        assertEquals(60_003L, decoded?.actualTrack?.durationMs)
        assertEquals(1800L, decoded?.frameStats?.count)
        assertEquals(5, decoded?.frameHealth?.maxHammingDistance)
        assertEquals(4, decoded?.schemaVersion)
        assertEquals(RecordingSourceRole.SURROUND, decoded?.sourceRole)
        assertEquals(RecordingLayoutKind.FOUR_LANE_V1, decoded?.layoutKind)
    }

    @Test
    fun atomicWriteLeavesNoTempFileBehind() {
        val mp4 = tempMp4()
        val sidecar = sampleSidecar(mp4)

        val file = SegmentSidecarIO.writeAtomic(mp4, sidecar)

        assertTrue(file.exists())
        assertFalse(File(file.absolutePath + ".tmp").exists())
    }

    @Test
    fun corruptSidecarReadsAsNull() {
        val mp4 = tempMp4()
        val sidecarFile = SegmentSidecarIO.sidecarFileFor(mp4)
        sidecarFile.writeText("{ not json")

        assertNull(SegmentSidecarIO.read(sidecarFile))
    }

    @Test
    fun missingSidecarReadsAsNull() {
        val mp4 = tempMp4()
        assertNull(SegmentSidecarIO.read(SegmentSidecarIO.sidecarFileFor(mp4)))
    }

    @Test
    fun provisionalSidecarRoundTripsAndDefaultsToFalse() {
        val mp4 = tempMp4()
        val provisional = sampleSidecar(mp4).copy(
            provisional = true,
            actualTrack = null,
            frameHealth = null,
        )

        val written = SegmentSidecarIO.writeAtomic(mp4, provisional)
        val decoded = SegmentSidecarIO.read(written)

        assertEquals(true, decoded?.provisional)
        assertNull(decoded?.actualTrack)
        assertNull(decoded?.frameHealth)

        val plain = sampleSidecar(mp4)
        assertEquals(false, plain.provisional)
    }

    @Test
    fun uploadPinnedRoundTripsAndDefaultsToFalse() {
        val mp4 = tempMp4()
        val pinned = sampleSidecar(mp4).copy(uploadPinned = true)

        val written = SegmentSidecarIO.writeAtomic(mp4, pinned)
        val decoded = SegmentSidecarIO.read(written)

        assertEquals(true, decoded?.uploadPinned)
        assertEquals(false, decoded?.protected)
        assertEquals(false, sampleSidecar(mp4).uploadPinned)
    }

    @Test
    fun incidentMetadataRoundTripsAndDefaultsToNull() {
        val mp4 = tempMp4()
        val incident = sampleSidecar(mp4).copy(
            eventId = "event-1700000000000",
            eventRequestedAtEpochMs = 1_700_000_000_000,
            eventRole = "CURRENT",
        )

        val decoded = SegmentSidecarIO.read(SegmentSidecarIO.writeAtomic(mp4, incident))

        assertEquals("event-1700000000000", decoded?.eventId)
        assertEquals(1_700_000_000_000, decoded?.eventRequestedAtEpochMs)
        assertEquals("CURRENT", decoded?.eventRole)
        assertNull(sampleSidecar(mp4).eventId)
    }

    @Test
    fun schemaThreeWithoutSourceFieldsRemainsReadable() {
        val encoded = SegmentSidecarIO.json.encodeToString(
            SegmentSidecar.serializer(),
            sampleSidecar(tempMp4()),
        )
        val legacy = encoded
            .replace("\"schemaVersion\": 4", "\"schemaVersion\": 3")
            .lineSequence()
            .filterNot { line ->
                line.contains("\"sourceRole\"") ||
                    line.contains("\"layoutKind\"") ||
                    line.contains("\"mappingRevision\"")
            }
            .joinToString("\n")
        val decoded = SegmentSidecarIO.json.decodeFromString(SegmentSidecar.serializer(), legacy)

        assertEquals(3, decoded.schemaVersion)
        assertEquals(RecordingSourceRole.SURROUND, decoded.sourceRole)
        assertEquals(RecordingLayoutKind.FOUR_LANE_V1, decoded.layoutKind)
    }

    private fun tempMp4(): File {
        val dir = Files.createTempDirectory("seg-sidecar").toFile()
        return File(dir, "seg-0004-1000-1280x5140-14M.mp4")
    }
}
