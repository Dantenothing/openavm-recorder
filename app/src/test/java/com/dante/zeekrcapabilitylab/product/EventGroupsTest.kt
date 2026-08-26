package com.dante.zeekrcapabilitylab.product

import com.dante.zeekrcapabilitylab.probe.camera.CameraFormatProfile
import com.dante.zeekrcapabilitylab.probe.camera.ProfileSize
import com.dante.zeekrcapabilitylab.service.recorder.SegmentSidecar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EventGroupsTest {

    private val profile = CameraFormatProfile(ProfileSize(5120, 1280), 28_000_000)

    private fun segment(
        file: File,
        start: Long,
        stop: Long,
        protected: Boolean = false,
        eventId: String? = null,
        segmentNumber: Int = 1,
        recordingSessionId: String? = null,
        processStartId: String = "1-1",
    ): EventGroups.Segment {
        val sidecar = SegmentSidecar(
            file = file.absolutePath,
            cameraId = "2",
            profile = profile,
            segmentSeconds = 60,
            segmentNumber = segmentNumber,
            processStartId = processStartId,
            recordingSessionId = recordingSessionId,
            startedAtEpochMs = start,
            stoppedAtEpochMs = stop,
            result = SegmentSidecar.RESULT_SUCCESS,
            protected = protected,
            eventId = eventId,
        )
        return EventGroups.Segment(file, sidecar)
    }

    @Test
    fun protectedSegmentWithinGapFormsSingleIncident() {
        val dir = File.createTempFile("event-groups", "").parentFile
        val a = segment(File(dir, "a.mp4"), 1000, 61_000, protected = true)
        val b = segment(File(dir, "b.mp4"), 62_000, 122_000)
        val c = segment(File(dir, "c.mp4"), 10 * 60_000L, 11 * 60_000L)

        val incidents = EventGroups.groupIncidents(listOf(a, b, c))

        assertEquals(1, incidents.size)
        assertEquals(2, incidents[0].segments.size)
        assertTrue(incidents[0].incident)
    }

    @Test
    fun distantSegmentsAreNotMergedIntoIncident() {
        val dir = File.createTempFile("event-groups", "").parentFile
        val a = segment(File(dir, "a.mp4"), 1000, 61_000, protected = true)
        val b = segment(File(dir, "b.mp4"), 20 * 60_000L, 21 * 60_000L)

        val incidents = EventGroups.groupIncidents(listOf(a, b))

        assertEquals(1, incidents.size)
        assertEquals(1, incidents[0].segments.size)
    }

    @Test
    fun unbookmarkedRunsProduceNoIncident() {
        val dir = File.createTempFile("event-groups", "").parentFile
        val a = segment(File(dir, "a.mp4"), 1000, 61_000)
        val b = segment(File(dir, "b.mp4"), 62_000, 122_000)

        val incidents = EventGroups.groupIncidents(listOf(a, b))

        assertTrue(incidents.isEmpty())
        val dates = EventGroups.groupByDate(listOf(a, b))
        assertFalse(dates.isEmpty())
        assertEquals(2, dates[0].second.segments.size)
    }

    @Test
    fun explicitEventIdGroupsOnlyItsProtectedSegments() {
        val dir = File.createTempFile("event-groups", "").parentFile
        val before = segment(File(dir, "before.mp4"), 1_000, 61_000, protected = true, eventId = "event-1")
        val current = segment(File(dir, "current.mp4"), 62_000, 122_000, protected = true, eventId = "event-1")
        val next = segment(File(dir, "next.mp4"), 123_000, 183_000, protected = true, eventId = "event-1")
        val ordinary = segment(File(dir, "ordinary.mp4"), 184_000, 244_000)

        val incidents = EventGroups.groupIncidents(listOf(before, current, next, ordinary))

        assertEquals(1, incidents.size)
        assertEquals(listOf("before.mp4", "current.mp4", "next.mp4"), incidents[0].segments.map { it.file.name })
    }

    @Test
    fun stableSessionIdPresentsManyInternalFilesAsOneRecording() {
        val dir = File.createTempFile("event-groups", "").parentFile
        val first = segment(File(dir, "a.mp4"), 1_000, 61_000, segmentNumber = 1, recordingSessionId = "session-a")
        val second = segment(File(dir, "b.mp4"), 61_010, 121_010, segmentNumber = 2, recordingSessionId = "session-a")
        val later = segment(File(dir, "c.mp4"), 200_000, 220_000, segmentNumber = 1, recordingSessionId = "session-b")

        val recordings = EventGroups.groupRecordings(listOf(first, later, second))

        assertEquals(2, recordings.size)
        assertEquals(listOf("a.mp4", "b.mp4"), recordings[1].segments.map { it.file.name })
        assertEquals(120_000L, recordings[1].durationMs)
    }

    @Test
    fun legacySidecarsUseConsecutiveNumbersWithoutMergingLaterStart() {
        val dir = File.createTempFile("event-groups", "").parentFile
        val first = segment(File(dir, "a.mp4"), 1_000, 61_000, segmentNumber = 1)
        val second = segment(File(dir, "b.mp4"), 61_010, 121_010, segmentNumber = 2)
        val restarted = segment(File(dir, "c.mp4"), 121_020, 141_020, segmentNumber = 1)

        val recordings = EventGroups.groupRecordings(listOf(first, second, restarted))

        assertEquals(2, recordings.size)
        assertEquals(listOf("a.mp4", "b.mp4"), recordings[1].segments.map { it.file.name })
        assertEquals(listOf("c.mp4"), recordings[0].segments.map { it.file.name })
    }

    @Test
    fun protectedSessionProducesOneSavedEventForTheWholeRecording() {
        val dir = File.createTempFile("event-groups", "").parentFile
        val first = segment(
            File(dir, "a.mp4"),
            1_000,
            61_000,
            protected = true,
            segmentNumber = 1,
            recordingSessionId = "session-a",
        )
        val second = segment(
            File(dir, "b.mp4"),
            61_010,
            121_010,
            protected = true,
            segmentNumber = 2,
            recordingSessionId = "session-a",
        )

        val incidents = EventGroups.groupIncidents(listOf(first, second))

        assertEquals(1, incidents.size)
        assertEquals(listOf("a.mp4", "b.mp4"), incidents.single().segments.map { it.file.name })
    }
}
