package com.dante.zeekrcapabilitylab.recorder

import com.dante.zeekrcapabilitylab.probe.camera.CameraFormatProfile
import com.dante.zeekrcapabilitylab.probe.camera.ProfileSize
import com.dante.zeekrcapabilitylab.service.recorder.ActualTrackInfo
import com.dante.zeekrcapabilitylab.service.recorder.CaptureSubmissionMode
import com.dante.zeekrcapabilitylab.service.recorder.FrameHealthReport
import com.dante.zeekrcapabilitylab.service.recorder.SegmentFrameStats
import com.dante.zeekrcapabilitylab.service.recorder.SegmentSidecar
import com.dante.zeekrcapabilitylab.service.recorder.SegmentSidecarIO
import com.dante.zeekrcapabilitylab.service.recorder.RecordingLayoutKind
import com.dante.zeekrcapabilitylab.service.recorder.RecordingMode
import com.dante.zeekrcapabilitylab.service.recorder.RecordingSourceRole
import com.dante.zeekrcapabilitylab.service.recorder.TimeLapseAccuracy
import com.dante.zeekrcapabilitylab.service.recorder.VideoTriggerMarker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

class SegmentSidecarTest {

    private val profile = CameraFormatProfile(ProfileSize(1280, 5140), 14_000_000)

    @Test fun continuousSidecarSeparatesCameraRasterFromEncodedTrackAndFileTimeline() {
        val source = sampleSidecar(tempMp4()).copy(
            actualTrack = ActualTrackInfo(width = 3840, height = 1728),
            laneLayout = com.dante.zeekrcapabilitylab.service.recorder.SegmentLaneLayoutFactory.forProfile(
                1280, 5140, emptyList(), emptyList(), emptyList()))
        val contract = io.github.dantenothing.avmtransfer.protocol.StripRepackContract(inputWidth = 1280,
            inputHeight = 5140, stripHeight = 1728, encodedWidth = 3840, encodedHeight = 1728)
        val timeline = io.github.dantenothing.avmtransfer.protocol.ContinuousSegmentTimeline(runId = source.recordingSessionId!!,
            firstPtsUs = 60_000_000, lastPtsUs = 119_966_667, endExclusivePtsUs = 120_000_000,
            frames = 1800, startsWithKeyFrame = true)
        val output = source.withContinuousRaster(contract, timeline)
        val raw = SegmentSidecarIO.json.encodeToString(SegmentSidecar.serializer(), output)
        val restored = SegmentSidecarIO.json.decodeFromString(SegmentSidecar.serializer(), raw)
        assertEquals(output, restored)
        assertEquals(9, restored.schemaVersion)
        assertEquals(5140, restored.profile.size.height)
        assertEquals(1728, restored.actualTrack?.height)
        assertEquals(60_000_000L, restored.continuousTimeline?.firstPtsUs)
        assertTrue(io.github.dantenothing.avmtransfer.protocol.RecordingRasterMetadata.read(raw) is
            io.github.dantenothing.avmtransfer.protocol.RecordingRasterMetadata.Repacked)
        assertTrue(runCatching { source.copy(profile = profile.copy(size = ProfileSize(3840, 1728)))
            .withContinuousRaster(contract, timeline) }.isFailure)
        assertTrue(runCatching { source.withContinuousRaster(contract, timeline.copy(runId = "wrong")) }.isFailure)
        val lapse = source.copy(recordingMode = RecordingMode.TIME_LAPSE, timeLapseMultiplier = 150,
            realDurationMs = 60_000, requestedCaptureRateFps = 0.2,
            captureSubmissionMode = CaptureSubmissionMode.REPEATING_ENCODER)
            .withContinuousRaster(contract, timeline.copy(firstPtsUs = 0, lastPtsUs = 366_666,
                endExclusivePtsUs = 400_000, frames = 12))
        val decoded = SegmentSidecarIO.json.decodeFromString(SegmentSidecar.serializer(),
            SegmentSidecarIO.json.encodeToString(SegmentSidecar.serializer(), lapse))
        assertEquals(150, decoded.timeLapseMultiplier); assertEquals(60_000L, decoded.realDurationMs)
        assertEquals(400_000L, decoded.continuousTimeline?.endExclusivePtsUs)
        assertEquals(CaptureSubmissionMode.REPEATING_ENCODER, decoded.captureSubmissionMode)
    }

    private fun sampleSidecar(mp4: File, protected: Boolean = false) = SegmentSidecar(
        file = mp4.absolutePath,
        cameraId = "2",
        profile = profile,
        segmentSeconds = 60,
        segmentNumber = 4,
        processStartId = "12345-1700000000000",
        recordingSessionId = "session-1700000000000-a",
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
        assertEquals(8, decoded?.schemaVersion)
        assertEquals("session-1700000000000-a", decoded?.recordingSessionId)
        assertEquals(RecordingSourceRole.SURROUND, decoded?.sourceRole)
        assertEquals(RecordingLayoutKind.FOUR_LANE_V1, decoded?.layoutKind)
    }

    @Test
    fun playbackReadsInternalAndDirectUsbSidecarNames() {
        val mp4 = tempMp4()
        val sidecar = sampleSidecar(mp4).copy(eventId = "event", eventRequestedAtEpochMs = 3000)
        val usbMetadata = File(mp4.parentFile, mp4.nameWithoutExtension + ".sidecar.json")
        usbMetadata.writeText(SegmentSidecarIO.json.encodeToString(SegmentSidecar.serializer(), sidecar))
        assertEquals(sidecar, SegmentSidecarIO.readForMedia(mp4))
        assertEquals(2000L, com.dante.zeekrcapabilitylab.player.ManualBookmarkTimeline.from(SegmentSidecarIO.readForMedia(mp4)).single().positionMs)
        val internal = sidecar.copy(eventId = "internal", eventRequestedAtEpochMs = 4000)
        SegmentSidecarIO.writeAtomic(mp4, internal)
        assertEquals(internal, SegmentSidecarIO.readForMedia(mp4))
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
            .replace("\"schemaVersion\": 8", "\"schemaVersion\": 3")
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

    @Test
    fun schemaFourWithoutRecordingSessionIdRemainsReadable() {
        val encoded = SegmentSidecarIO.json.encodeToString(
            SegmentSidecar.serializer(),
            sampleSidecar(tempMp4()),
        )
        val legacy = encoded
            .replace("\"schemaVersion\": 8", "\"schemaVersion\": 4")
            .lineSequence()
            .filterNot { line -> line.contains("\"recordingSessionId\"") }
            .joinToString("\n")

        val decoded = SegmentSidecarIO.json.decodeFromString(SegmentSidecar.serializer(), legacy)

        assertEquals(4, decoded.schemaVersion)
        assertNull(decoded.recordingSessionId)
    }

    @Test
    fun timeLapseMetadataRoundTrips() {
        val mp4 = tempMp4()
        val encoded = sampleSidecar(mp4).copy(
            recordingMode = RecordingMode.TIME_LAPSE,
            timeLapseMultiplier = 60,
            requestedCaptureRateFps = 0.5,
            captureSubmissionMode = CaptureSubmissionMode.PACED_SINGLE_ENCODER,
            effectiveSegmentSeconds = 600,
            realDurationMs = 600_000,
            measuredMultiplier = 60.0,
            timeLapseRelativeError = 0.0,
            timeLapseAccuracy = TimeLapseAccuracy.PASS,
            finalizeReason = "TIMEOUT",
        )

        val decoded = SegmentSidecarIO.read(SegmentSidecarIO.writeAtomic(mp4, encoded))

        assertEquals(encoded, decoded)
        assertEquals(RecordingMode.TIME_LAPSE, decoded?.recordingMode)
        assertEquals(60, decoded?.timeLapseMultiplier)
        assertEquals(600_000L, decoded?.realDurationMs)
        assertEquals(CaptureSubmissionMode.PACED_SINGLE_ENCODER, decoded?.captureSubmissionMode)
    }

    @Test
    fun schemaFiveWithoutTimeLapseFieldsDefaultsToNormal() {
        val encoded = SegmentSidecarIO.json.encodeToString(
            SegmentSidecar.serializer(),
            sampleSidecar(tempMp4()),
        )
        val removed = setOf(
            "recordingMode",
            "timeLapseMultiplier",
            "requestedCaptureRateFps",
            "captureSubmissionMode",
            "effectiveSegmentSeconds",
            "realDurationMs",
            "measuredMultiplier",
            "timeLapseRelativeError",
            "timeLapseAccuracy",
            "finalizeReason",
        )
        val current = SegmentSidecarIO.json.parseToJsonElement(encoded).jsonObject
        val legacy = JsonObject(
            current.filterKeys { it !in removed } + ("schemaVersion" to JsonPrimitive(5)),
        ).toString()

        val decoded = SegmentSidecarIO.json.decodeFromString(SegmentSidecar.serializer(), legacy)

        assertEquals(5, decoded.schemaVersion)
        assertEquals(RecordingMode.NORMAL, decoded.recordingMode)
        assertEquals(1, decoded.timeLapseMultiplier)
        assertEquals(decoded.segmentSeconds, decoded.effectiveSegmentSeconds)
    }

    @Test
    fun sentryTriggerMetadataSurvivesExportAndOlderSidecarsRemainReadable() {
        val mp4 = tempMp4()
        val markers = listOf(VideoTriggerMarker(17_250, "VISUAL_RISK", 100_000, setOf(2, 4)),
            VideoTriggerMarker(42_500, "MANUAL", 125_250))
        val sentry = sampleSidecar(mp4).copy(eventRole = "SENTRY", triggerMarkers = markers)
        val written = SegmentSidecarIO.writeAtomic(mp4, sentry)
        assertEquals(markers, SegmentSidecarIO.read(written)?.triggerMarkers)

        val current = SegmentSidecarIO.json.parseToJsonElement(written.readText()).jsonObject
        val legacy = JsonObject(current.filterKeys { it != "triggerMarkers" } + ("schemaVersion" to JsonPrimitive(7)))
        val decoded = SegmentSidecarIO.json.decodeFromString(SegmentSidecar.serializer(), legacy.toString())
        assertEquals(7, decoded.schemaVersion)
        assertEquals("SENTRY", decoded.eventRole)
        assertTrue(decoded.triggerMarkers.isEmpty())
    }

    private fun tempMp4(): File {
        val dir = Files.createTempDirectory("seg-sidecar").toFile()
        return File(dir, "seg-0004-1000-1280x5140-14M.mp4")
    }
}
