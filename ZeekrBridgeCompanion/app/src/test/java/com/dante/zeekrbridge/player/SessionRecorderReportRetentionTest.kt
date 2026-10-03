package com.dante.zeekrbridge.player

import io.github.dantenothing.avmtransfer.protocol.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SessionRecorderReportRetentionTest {
    private val session = "00000000-0000-4000-8000-000000000001"
    private fun timeline() = SessionPlaybackTimeline(session, listOf(
        SessionVideoSegment("a", "SURROUND", session, 1000, 2000, 1000),
        SessionVideoSegment("b", "SURROUND", session, 2000, 3000, 1000)))
    private fun report(files: Int) = DriveRecorderReport(appVersion = "5.1.0-drive4",
        sessionCorrelation = DriveRecorderReport.correlation(session), channels = listOf(
            DriveChannelReport("SURROUND", files, 0, 0, 1000L * files, mediaDurationMs = 1000L * files,
                anchoredFiles = 0, observationAnchoredFiles = 0, approximateFiles = files)))
    private fun bundle(start: Long, report: DriveRecorderReport) = DriveDataBundle(schemaVersion = 2,
        recordingSessionId = session, segmentStartedElapsedMs = start, segmentEndedElapsedMs = start + 1000,
        recorderReport = report)

    @Test fun endOfVideoRetainsVerifiedRecorderEvidenceWithoutClaimingCurrentFrameIntegrity() {
        val acc = SessionReviewAccumulator(timeline())
        acc.add("a", bundle(1000, report(1)))
        val retained = acc.finish().recorderSnapshot
        assertEquals(report(1), retained)
        val copied = recordedPlaybackReport("5.1.0-drive4", null, 2, -1, 2000,
            DriveAttachmentStatus.MISSING, RecordedDriveFrame(null, null, null), emptyMap(),
            recorder = retained, recorderFromCurrentPosition = false)
        val result = Json.parseToJsonElement(copied).jsonObject
        assertTrue(result["recorderSnapshotAvailable"]!!.jsonPrimitive.boolean)
        assertFalse(result["verifiedCurrentAttachment"]!!.jsonPrimitive.boolean)
        assertEquals("VERIFIED_SESSION_ATTACHMENT", result["recorderSnapshotSource"]!!.jsonPrimitive.content)
        assertFalse(copied.contains(session))
    }

    @Test fun parallelCopiesRetainOneMostCompleteSnapshotRatherThanSumDuplicates() {
        val acc = SessionReviewAccumulator(timeline())
        acc.add("a", bundle(1000, report(2)))
        acc.add("b", bundle(2000, report(1)))
        assertEquals(2, acc.finish().recorderSnapshot!!.channels.single().finalizedFiles)
    }

    @Test fun foreignOrInvalidSnapshotCannotAttachToSelectedSession() {
        val acc = SessionReviewAccumulator(timeline())
        acc.add("a", bundle(1000, report(1).copy(sessionCorrelation = "a".repeat(16))))
        acc.add("b", bundle(2000, report(1).copy(appVersion = "private invalid value")))
        assertNull(acc.finish().recorderSnapshot)
    }

    @Test fun parallelCopyWithMoreFieldsCompletesSameObservationWithoutDoubleCounting() {
        val clock = SessionPlaybackTimeline(session, listOf(
            SessionVideoSegment("surround", "SURROUND", session, 1000, 3000, 2000),
            SessionVideoSegment("front", "FRONT_CABIN", session, 1000, 3000, 2000)))
        val acc = SessionReviewAccumulator(clock)
        fun row(at: Long, pressed: Double?) = DriveVehicleSample(at, 1_800_000_000_000 + at,
            pressed?.let { mapOf("brake_pedal" to DriveVehicleValue(at, it)) }.orEmpty())
        fun data(rows: List<DriveVehicleSample>) = DriveDataBundle(recordingSessionId = session,
            segmentStartedElapsedMs = 1000, segmentEndedElapsedMs = 3000, vehicle = rows)
        acc.add("surround", data(listOf(row(1000, null), row(1500, 1.0))))
        acc.add("front", data(listOf(row(1000, 0.0), row(1500, 1.0))))
        val review = acc.finish()
        assertEquals(2, review.summary.vehicleObservationCount)
        assertEquals(1, review.events.count { it.kind == io.github.dantenothing.drivemap.DriveEventKind.BRAKE })
        assertEquals("pressed", review.events.single { it.kind == io.github.dantenothing.drivemap.DriveEventKind.BRAKE }.value)
    }
}
