package com.dante.zeekrbridge.player

import com.dante.zeekrbridge.core.*
import io.github.dantenothing.avmtransfer.protocol.*
import io.github.dantenothing.drivemap.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PhoneSessionPlaybackTest {
    @get:Rule val temp = TemporaryFolder()
    private val session = "12345678-1234-1234-1234-123456789abc"
    private fun segment(id: String, start: Long, end: Long, channel: String = "SURROUND", wall: Long = start): IndexedMediaSegment {
        val file = temp.newFile("$id.mp4").apply { writeText("synthetic") }
        return IndexedMediaSegment(id, file.path, file.name, null, file.length(), wall, wall + end - start,
            999_999, 1, session, null, null, false,
            if (channel == "SURROUND") IndexedSourceRole.SURROUND else IndexedSourceRole.CABIN,
            if (channel == "SURROUND") IndexedLayoutKind.FOUR_LANE_V1 else IndexedLayoutKind.SINGLE_V1,
            null, emptyList(), 1280, 5140, channelId = channel, startedElapsedMs = start, stoppedElapsedMs = end,
            sessionStartedElapsedMs = 10_000)
    }
    private fun timeline(items: List<IndexedMediaSegment>, durations: Map<String, Long> = items.associate { it.id to (it.stoppedElapsedMs!! - it.startedElapsedMs!!) }) =
        phoneSessionTimeline(items, durations, items.map { it.id }.toSet())!!
    private fun gps(at: Long, broken: Boolean = false) = DriveGpsSample(at, 1_800_000_000_000 + at,
        -34.9 + (at - 10_000) / 1_000_000.0, 138.6, 5f, "gps", breakBefore = broken)
    private fun bundle(start: Long, end: Long, points: List<DriveGpsSample>, vehicles: List<DriveVehicleSample> = emptyList()) =
        DriveDataBundle(recordingSessionId = session, segmentStartedElapsedMs = start, segmentEndedElapsedMs = end, gps = points, vehicle = vehicles)

    @Test fun parallelCamerasShareTimeInsteadOfSummingDurations() {
        val items = listOf(segment("s", 10_000, 20_000), segment("f", 10_000, 20_000, "FRONT_CABIN"))
        val clock = timeline(items)
        assertEquals(10_000L, clock.durationMs)
        assertEquals(4_000L, clock.seek("FRONT_CABIN", 4_000)?.mediaPositionMs)
        assertEquals(10_000L, IndexedRecordingSession(session, items, false).durationMs)
    }

    @Test fun switchingToMissingChannelNeverBorrowsSurroundFrames() {
        val clock = timeline(listOf(segment("s", 10_000, 30_000), segment("f", 20_000, 30_000, "FRONT_CABIN")))
        assertNull(clock.seek("FRONT_CABIN", 4_000))
        assertEquals("s", clock.seek("SURROUND", 4_000)?.segmentId)
        assertEquals(10_000L, nextSessionVideoPosition(clock, "FRONT_CABIN", 4_000))
    }

    @Test fun actualDurationAndDelayedFirstFrameDeterminePlaybackWindow() {
        val item = segment("s", 10_000, 30_000).copy(videoClockAnchor = VideoClockAnchor(
            elapsedRealtimeNs = 12_000_000_000, source = "CALLBACK_OBSERVED"))
        val clock = timeline(listOf(item), mapOf("s" to 7_000))
        assertNull(clock.seek("SURROUND", 1_999))
        assertEquals(0L, clock.seek("SURROUND", 2_000)?.mediaPositionMs)
        assertEquals(9_000L, clock.durationMs)
        assertNull(clock.seek("SURROUND", 9_000))
        assertEquals(2_000L, initialPhoneSessionPosition(clock, "SURROUND"))
        assertEquals("s", clock.seek("SURROUND", initialPhoneSessionPosition(clock, "SURROUND"))?.segmentId)
    }

    @Test fun missingMiddleAndUnknownDurationLeaveSessionHoles() {
        val items = listOf(segment("a", 10_000, 20_000), segment("b", 20_000, 30_000), segment("c", 30_000, 40_000))
        val clock = timeline(items, mapOf("a" to 10_000, "c" to 10_000))
        assertNull(clock.seek("SURROUND", 15_000))
        assertEquals("c", clock.seek("SURROUND", 20_000)?.segmentId)
        assertNull(clock.range("SURROUND", 0, 30_000))
    }

    @Test fun sessionAndClockResetCannotLeakAnotherSession() {
        val items = listOf(segment("a", 10_000, 20_000), segment("b", 10_000, 20_000).copy(recordingSessionId = "other"))
        assertNull(phoneSessionTimeline(items, mapOf("a" to 10_000, "b" to 10_000), setOf("a", "b")))
        assertTrue(supportsSessionPlayback(items)) // Explicit parallel files must show an error, never serialize cameras.
    }

    @Test fun legacyIncompleteTimingFallsBackToTheOriginalPlayer() {
        val old = segment("old", 10_000, 20_000).copy(channelId = null)
        assertTrue(supportsSessionPlayback(listOf(old)))
        assertFalse(supportsSessionPlayback(listOf(old.copy(stoppedElapsedMs = null))))
        assertFalse(supportsSessionPlayback(listOf(old.copy(startedElapsedMs = null))))
        assertFalse(supportsSessionPlayback(listOf(old.copy(stoppedElapsedMs = 9_000))))
        assertFalse(supportsSessionPlayback(listOf(old, old.copy(id = "other", stoppedElapsedMs = null))))
    }

    @Test fun explicitParallelFilesWithBrokenTimingNeverUseSerialPlayback() {
        val modern = segment("modern", 10_000, 20_000).copy(stoppedElapsedMs = null)
        assertTrue(supportsSessionPlayback(listOf(modern)))
        assertTrue(supportsSessionPlayback(listOf(modern.copy(recordingSessionId = null))))
        assertEquals(0L, phoneSessionTimeline(listOf(modern), mapOf(modern.id to 10_000), setOf(modern.id))!!.durationMs)
        val invalid = modern.copy(channelId = "FUTURE_UNKNOWN_CHANNEL")
        assertNull(invalid.playbackChannelId)
        assertTrue(supportsSessionPlayback(listOf(invalid)))
    }

    @Test fun declaredChannelCannotBorrowAConflictingSourceRole() {
        val item = segment("wrong-camera", 10_000, 20_000).copy(channelId = "FRONT_CABIN")
        assertEquals(0L, timeline(listOf(item)).durationMs)
    }

    @Test fun adapterRetainsExactContinuousCutProofForMillisecondRounding() {
        val firstCut = ContinuousSegmentTimeline(runId = session, firstPtsUs = 999, lastPtsUs = 900_999,
            endExclusivePtsUs = 1_001_998, frames = 10, startsWithKeyFrame = true)
        val secondCut = ContinuousSegmentTimeline(runId = session, firstPtsUs = 1_001_998, lastPtsUs = 1_901_998,
            endExclusivePtsUs = 2_001_998, frames = 10, startsWithKeyFrame = true)
        val a = segment("rounded-a", 10_000, 11_000).copy(continuousTimeline = firstCut,
            videoClockAnchor = VideoClockAnchor(elapsedRealtimeNs = 10_000_000_000, source = "CALLBACK_OBSERVED"))
        val b = segment("rounded-b", 11_001, 12_001).copy(continuousTimeline = secondCut,
            videoClockAnchor = VideoClockAnchor(elapsedRealtimeNs = 11_001_000_000, source = "CALLBACK_OBSERVED"))
        val clock = timeline(listOf(a, b), mapOf(a.id to 1_000, b.id to 1_000))
        assertEquals(firstCut, clock.segments.first().continuousTimeline)
        assertEquals(1, clock.quantizedBoundaries)
        assertEquals(a.id, clock.seek("SURROUND", 1_000)?.segmentId)
        assertEquals(b.id, clock.seek("SURROUND", 1_001)?.segmentId)
        assertEquals(2, clock.range("SURROUND", 0, 2_001)?.size)
        val plan = MediaExportPlanner.buildSession(listOf(a, b), clock, "SURROUND", MediaExportTarget.ORIGINAL, 0, 2_001)
        assertEquals(2_000L, plan.outputDurationMs)
        assertEquals(2_001L, plan.requestedEndMs)
    }

    @Test fun exportKeepsMonotonicOrderAcrossWallClockReset() {
        val a = segment("a", 10_000, 20_000, wall = 100_000)
        val b = segment("b", 20_000, 30_000, wall = 1_000)
        val items = listOf(a, b)
        val plan = MediaExportPlanner.buildSession(items, timeline(items), "SURROUND", MediaExportTarget.ORIGINAL, 5_000, 15_000)
        assertEquals(listOf(a.filePath, b.filePath), plan.clips.map { it.filePath })
        assertEquals(listOf(5_000L, 0L), plan.clips.map { it.clipStartMs })
        assertEquals(listOf(10_000L, 5_000L), plan.clips.map { it.clipEndMs })
        assertEquals(10_000L, plan.outputDurationMs)
    }

    @Test fun exportRejectsGapsMissingFilesAndMixedLayout() {
        val a = segment("a", 10_000, 20_000)
        val b = segment("b", 21_000, 30_000)
        assertThrows(IllegalArgumentException::class.java) { MediaExportPlanner.buildSession(listOf(a, b), timeline(listOf(a, b)),
            "SURROUND", MediaExportTarget.ORIGINAL, 0, 20_000) }
        val contiguous = b.copy(startedElapsedMs = 20_000)
        val items = listOf(a, contiguous)
        val clock = timeline(items)
        b.file.delete()
        assertThrows(IllegalArgumentException::class.java) { MediaExportPlanner.buildSession(items, clock, "SURROUND", MediaExportTarget.ORIGINAL, 0, 20_000) }
        val changed = segment("c", 20_000, 30_000).copy(originalWidth = 1920)
        assertThrows(IllegalArgumentException::class.java) { MediaExportPlanner.buildSession(listOf(a, changed), timeline(listOf(a, changed)),
            "SURROUND", MediaExportTarget.ORIGINAL, 0, 20_000) }
    }

    @Test fun anchoredDriveReadingsUseMediaClockAndRejectFutureSamples() {
        val data = bundle(10_000, 30_000, listOf(gps(12_000), gps(14_000))).copy(schemaVersion = 2,
            alignment = DriveDataCodec.ANCHORED_ALIGNMENT, videoClockAnchor = VideoClockAnchor(
                elapsedRealtimeNs = 12_000_000_000, source = "CODEC_OUTPUT_OBSERVED"))
        assertEquals(12_000L, recordedDriveFrame(data, 0).gps?.elapsedMs)
        assertEquals(12_000L, recordedDriveFrame(data, 1_000).gps?.elapsedMs)
        assertNull(recordedDriveFrame(data, 18_000).gps)
    }

    @Test fun parallelAttachmentsDeduplicateCoverageAndPreserveGpsGap() {
        val clock = timeline(listOf(segment("s", 10_000, 30_000), segment("f", 10_000, 30_000, "FRONT_CABIN")))
        val data = bundle(10_000, 30_000, listOf(gps(10_000), gps(11_000), gps(20_000), gps(21_000)))
        val acc = SessionReviewAccumulator(clock)
        acc.add("s", data); acc.add("f", data)
        val result = acc.finish()
        assertEquals(4, result.summary.gpsPointCount)
        assertTrue(result.route.points[2].breakBefore)
        assertTrue(result.events.any { it.kind == DriveEventKind.GPS_GAP && it.sessionMs == 10_000L })
        assertTrue(result.summary.distanceM < 1_000)
        assertTrue(result.summary.incomplete)
    }

    @Test fun vehicleTransitionsAndMissingWindowsStayHonest() {
        val clock = timeline(listOf(segment("s", 10_000, 20_000)))
        val rows = listOf(DriveVehicleSample(10_000, 1_800_000_010_000, mapOf("brake_pedal" to DriveVehicleValue(10_000, 0.0))),
            DriveVehicleSample(11_000, 1_800_000_011_000, mapOf("brake_pedal" to DriveVehicleValue(11_000, 1.0))),
            DriveVehicleSample(15_000, 1_800_000_015_000, emptyMap(), true))
        val acc = SessionReviewAccumulator(clock)
        acc.add("s", bundle(10_000, 20_000, emptyList(), rows))
        val result = acc.finish()
        assertTrue(result.events.any { it.kind == DriveEventKind.BRAKE && it.value == "pressed" })
        assertTrue(result.events.any { it.kind == DriveEventKind.VEHICLE_GAP })
        assertFalse(result.events.any { it.kind == DriveEventKind.BRAKE && it.value == "released" })
    }

    @Test fun shortMissingAttachmentBreaksRouteEvenWithinGpsFreshness() {
        val clock = timeline(listOf(segment("a", 10_000, 12_000), segment("missing", 12_000, 13_000), segment("b", 13_000, 15_000)))
        val acc = SessionReviewAccumulator(clock)
        acc.add("a", bundle(10_000, 12_000, listOf(gps(11_000))))
        acc.missing()
        acc.add("b", bundle(13_000, 15_000, listOf(gps(13_000))))
        val result = acc.finish()
        assertTrue(result.route.points.last().breakBefore)
        assertEquals(0.0, result.summary.distanceM, 0.0)
    }

    @Test fun combinedReportIncludesOnlyValidatedRecorderAggregate() {
        val report = DriveRecorderReport(appVersion = "5.2.0", sessionCorrelation = "0123456789abcdef")
        val text = recordedPlaybackReport("5.2.0", null, 1, 0, 100, DriveAttachmentStatus.AVAILABLE,
            RecordedDriveFrame(null, null, null), mapOf("secret" to "/private/location"), report,
            DriveDataCodec.ANCHORED_ALIGNMENT, 3, 2, 0, 3,
            VideoClockAnchor(elapsedRealtimeNs = 12_000_000_000, source = "CALLBACK_OBSERVED"))
        assertTrue(text.contains("recorderSnapshotAvailable\":true"))
        assertTrue(text.contains("HASH_AND_SIDECAR_BINDING_VERIFIED"))
        assertTrue(text.contains("CALLBACK_OBSERVED"))
        assertTrue(text.contains("timingUncertaintyKnown\":false"))
        assertFalse(text.contains("/private")); assertFalse(text.contains(session))
        val invalid = recordedPlaybackReport(null, null, 1, 0, null, DriveAttachmentStatus.MISSING,
            RecordedDriveFrame(null, null, null), emptyMap(), report.copy(appVersion = "/private/secret"))
        assertFalse(invalid.contains("/private"))
        assertTrue(invalid.contains("recorderSnapshotAvailable\":false"))
    }

    @Test fun exportBoundRejectsOversizeSelectionBeforeQueueing() {
        val items = listOf(segment("long", 10_000, 10_000 + MediaExportPlanner.MAX_SESSION_EXPORT_MS + 1))
        val clock = timeline(items)
        assertThrows(IllegalArgumentException::class.java) { MediaExportPlanner.buildSession(items, clock,
            "SURROUND", MediaExportTarget.ORIGINAL, 0, clock.durationMs) }
    }

    @Test fun markersRequireKnownKindsAndAnActualPositionBeforeMediaEnd() {
        val item = segment("a", 10_000, 20_000).copy(triggerMarkers = listOf(
            IndexedVideoMarker(1_000, "MANUAL"), IndexedVideoMarker(2_000, "EMERGENCY"),
            IndexedVideoMarker(3_000, "UNKNOWN"), IndexedVideoMarker(10_000, "EMERGENCY")), eventRequestedElapsedMs = 20_000)
        val markers = phoneSessionMarkers(listOf(item), timeline(listOf(item)))
        assertEquals(listOf(DriveEventKind.MANUAL, DriveEventKind.EMERGENCY), markers.map { it.kind })
        assertEquals(listOf(1_000L, 2_000L), markers.map { it.sessionMs })
    }
}
