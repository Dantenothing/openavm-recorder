package com.dante.zeekrcapabilitylab.transfer

import com.dante.zeekrcapabilitylab.probe.camera.CameraFormatProfile
import com.dante.zeekrcapabilitylab.probe.camera.ProfileSize
import com.dante.zeekrcapabilitylab.service.recorder.*
import com.dante.zeekrcapabilitylab.telemetry.*
import io.github.dantenothing.avmtransfer.protocol.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class SessionRecorderEvidenceTest {
    private val session = "00000000-0000-4000-8000-000000000001"
    private fun sidecar(role: RecordingSourceRole = RecordingSourceRole.SURROUND) = SegmentSidecar(
        file = "private-path-account-token", cameraId = "2", profile = CameraFormatProfile(ProfileSize(640, 480), 2_000_000),
        sourceRole = role, segmentSeconds = 60, segmentNumber = 1, processStartId = "private-process",
        recordingSessionId = session, startedAtElapsedRealtimeMs = 1_000, stoppedAtElapsedRealtimeMs = 2_000,
        result = SegmentSidecar.RESULT_SUCCESS, fileBytes = 1024, frameStats = SegmentFrameStats(count = 30),
        actualTrack = ActualTrackInfo(durationMs = 1_000),
        videoClockAnchor = VideoClockAnchor(elapsedRealtimeNs = 1_000_000_000, source = "CODEC_OUTPUT_OBSERVED"))

    @Test fun reportIsTypedBoundedAndContainsNoRawSessionPathsOrCoordinates() {
        val track = TelemetryTrack(TelemetryHeader(sessionId = session, startedElapsedMs = 1_000), listOf(
            TelemetryFix(1_000, 1_790_000_000_000, -34.912345, 138.654321, 4f, "gps")))
        val report = SessionRecorderEvidence.build("5.1.0-drive4", session,
            listOf(sidecar(), sidecar(RecordingSourceRole.CABIN), sidecar().copy(recordingSessionId = "another")), track, null)
        assertTrue(report.valid())
        assertEquals(2, report.channels.size)
        assertEquals(1, report.gpsSamples)
        assertFalse(report.inventoryComplete)
        val json = Json.encodeToString(report)
        listOf("private-path", "private-process", session, "-34.912345", "138.654321", "latitude", "longitude")
            .forEach { assertFalse(it, json.contains(it)) }
        assertEquals(DriveRecorderReport.correlation(session), report.sessionCorrelation)
        assertEquals(1, report.channels.first().observationAnchoredFiles)
    }

    @Test fun missingAndProvisionalFilesDoNotBecomeFinalizedSuccess() {
        val report = SessionRecorderEvidence.build("5.1.0-drive4", session, listOf(
            sidecar().copy(result = SegmentSidecar.RESULT_FAILED, frameStats = null, actualTrack = null, fileBytes = 0, videoClockAnchor = null),
            sidecar(RecordingSourceRole.CABIN).copy(provisional = true)), null, null)
        assertEquals(0, report.channels.sumOf { it.finalizedFiles })
        assertEquals(1, report.channels.sumOf { it.failedFiles })
        assertEquals(1, report.channels.sumOf { it.provisionalFiles })
        assertNull(report.channels.first().observedFrames)
        assertEquals("NOT_ASSERTED", report.hardwareAcceptance)
        assertEquals("NOT_OBSERVED_BY_FILE_SNAPSHOT", report.cleanupOutcome)
    }

    @Test fun pedalSummaryUsesOnlyMatchingSessionRawValuesAndLabelsTheirScale() {
        val samples = listOf(
            VehicleTelemetrySample(1000, 10000, mapOf("throttle_depth" to VehicleTelemetryValue(1000, number = 42.0, status = "PROVISIONAL_RAW"))),
            VehicleTelemetrySample(1250, 10250, mapOf("throttle_depth" to VehicleTelemetryValue(1250, status = "UNAVAILABLE"))),
            VehicleTelemetrySample(1500, 10500, mapOf("throttle_depth" to VehicleTelemetryValue(1500, number = 120.0, status = "PROVISIONAL_RAW"))))
        val track = VehicleTelemetryTrack(VehicleTelemetryHeader(sessionId = session, startedElapsedMs = 1000), samples)
        val report = SessionRecorderEvidence.build("5.1.0-drive4", session, listOf(sidecar()), null, track)
        val pedal = report.pedals.single { it.key == "throttle_depth" }
        assertEquals(3, pedal.observations)
        assertEquals(2, pedal.rawAvailable)
        assertEquals(42.0, pedal.rawMin!!, 0.0)
        assertEquals(120.0, pedal.rawMax!!, 0.0)
        assertEquals("UNVERIFIED_RAW_NO_PERCENTAGE", pedal.scale)
        assertEquals(0, report.pedals.single { it.key == "brake_depth" }.observations)
        assertTrue(SessionRecorderEvidence.build("5.1.0-drive4", "other", emptyList(), null, track).pedals.all { it.observations == 0 })
    }
}
