package com.dante.zeekrbridge.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MediaIndexModelsTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun stableSessionIdGroupsSegmentsAndKeepsDifferentSessionsApart() {
        val segments = listOf(
            segment("a", 1_000, 1, sessionId = "session-a"),
            segment("b", 61_000, 2, sessionId = "session-a"),
            segment("c", 121_000, 1, sessionId = "session-b"),
        )

        val index = MediaIndexGrouper.build(segments)

        assertEquals(2, index.sessions.size)
        assertEquals(2, index.sessions.first { it.id == "session-a" }.segments.size)
        assertFalse(index.sessions.first { it.id == "session-a" }.legacy)
    }

    @Test
    fun stableSessionIdKeepsOneManualDriveTogetherAcrossMissingRecordings() {
        val segments = listOf(
            segment("18-00", 0L, 1, sessionId = "one-drive"),
            segment("18-20", 20 * 60_000L, 8, sessionId = "one-drive"),
            segment("18-40", 40 * 60_000L, 19, sessionId = "one-drive"),
        )

        val session = MediaIndexGrouper.build(segments).sessions.single()

        assertEquals("one-drive", session.id)
        assertEquals(listOf("18-00", "18-20", "18-40"), session.segments.map { it.id })
        assertFalse(session.legacy)
    }

    @Test
    fun legacySegmentsOnlyJoinWhenConsecutiveAndContiguous() {
        val segments = listOf(
            segment("a", 1_000, 1),
            segment("b", 61_000, 2),
            segment("c", 121_000, 1),
        )

        val index = MediaIndexGrouper.build(segments)

        assertEquals(2, index.sessions.size)
        assertTrue(index.sessions.all { it.legacy })
        assertEquals(listOf(1, 2), index.sessions.first { it.segments.size == 2 }.segments.map { it.segmentNumber })
    }

    @Test
    fun eventIdOrdersPreviousCurrentAndNext() {
        val values = listOf(
            segment("next", 121_000, 3, eventId = "event-1", eventRole = "NEXT"),
            segment("previous", 1_000, 1, eventId = "event-1", eventRole = "PREVIOUS"),
            segment("current", 61_000, 2, eventId = "event-1", eventRole = "CURRENT"),
        )

        val event = MediaIndexGrouper.build(values).events.single()

        assertEquals(listOf("PREVIOUS", "CURRENT", "NEXT"), event.segments.map { it.eventRole })
    }

    @Test
    fun scannerKeepsCabinAndIrDistinctAtSameResolution() {
        val cabin = temp.newFile("cabin.mp4")
        File(temp.root, "cabin.json").writeText(sidecar("CABIN", "1", "session-cabin"))
        val ir = temp.newFile("ir.mp4")
        File(temp.root, "ir.json").writeText(sidecar("IR", "0", "session-ir"))

        val index = MediaIndexScanner.scan(listOf(cabin, ir))

        assertEquals(IndexedSourceRole.CABIN, index.segments.first { it.fileName == "cabin.mp4" }.sourceRole)
        assertEquals(IndexedSourceRole.IR, index.segments.first { it.fileName == "ir.mp4" }.sourceRole)
        assertTrue(index.segments.all { it.layoutKind == IndexedLayoutKind.SINGLE_V1 })
    }

    @Test
    fun scannerPreservesFrozenSurroundLaneMetadata() {
        val video = temp.newFile("surround.mp4")
        File(temp.root, "surround.json").writeText(
            """
            {
              "cameraId": "2",
              "sourceRole": "SURROUND",
              "layoutKind": "FOUR_LANE_V1",
              "recordingSessionId": "session-360",
              "eventId": "event-360",
              "segmentNumber": 4,
              "startedAtEpochMs": 1000,
              "stoppedAtEpochMs": 61000,
              "laneLayout": {
                "originalWidth": 1280,
                "originalHeight": 5140,
                "lanes": [
                  {"lane":1,"label":"Front","x0":0,"x1":1280,"y0":0,"y1":1280,"displayOrder":1},
                  {"lane":2,"label":"Rear","x0":0,"x1":1280,"y0":1285,"y1":2565,"displayOrder":2},
                  {"lane":3,"label":"Left","x0":0,"x1":1280,"y0":2570,"y1":3850,"displayOrder":3},
                  {"lane":4,"label":"Right","x0":0,"x1":1280,"y0":3855,"y1":5135,"displayOrder":4}
                ]
              }
            }
            """.trimIndent(),
        )

        val segment = MediaIndexScanner.scan(listOf(video)).segments.single()

        assertEquals(IndexedSourceRole.SURROUND, segment.sourceRole)
        assertEquals(IndexedLayoutKind.FOUR_LANE_V1, segment.layoutKind)
        assertEquals(listOf("Front", "Rear", "Left", "Right"), segment.playbackLabels)
        assertEquals(listOf(1, 2, 3, 4), segment.playbackLaneOrder)
        assertEquals(1280, segment.originalWidth)
        assertEquals(5140, segment.originalHeight)
        assertEquals("event-360", segment.eventId)
    }

    @Test
    fun playbackUsesFrozenDisplayOrderInsteadOfRawCompositeOrder() {
        val segment = segment("ordered", 1_000, 1).copy(
            lanes = listOf(
                IndexedLane("Front", 0, 1280, 3855, 5135, displayOrder = 1, lane = 4),
                IndexedLane("Rear", 0, 1280, 2570, 3850, displayOrder = 2, lane = 3),
                IndexedLane("Left", 0, 1280, 1285, 2565, displayOrder = 3, lane = 2),
                IndexedLane("Right", 0, 1280, 0, 1280, displayOrder = 4, lane = 1),
            ),
        )

        assertEquals(listOf(4, 3, 2, 1), segment.playbackLaneOrder)
        assertEquals(listOf("Front", "Rear", "Left", "Right"), segment.playbackLabels)
    }

    @Test
    fun legacySurroundWithoutLaneMetadataKeepsFileTopToBottomOrder() {
        val segment = segment("legacy-order", 1_000, 1)

        assertEquals(listOf(1, 2, 3, 4), segment.playbackLaneOrder)
    }

    @Test
    fun corruptOrMissingMetadataRemainsUnknownInsteadOfGuessing() {
        val file = temp.newFile("unknown.mp4")
        File(temp.root, "unknown.json").writeText("not-json")

        val segment = MediaIndexScanner.scan(listOf(file)).segments.single()

        assertEquals(IndexedSourceRole.UNKNOWN, segment.sourceRole)
        assertEquals(IndexedLayoutKind.UNKNOWN, segment.layoutKind)
    }

    private fun segment(
        id: String,
        start: Long,
        number: Int,
        sessionId: String? = null,
        eventId: String? = null,
        eventRole: String? = null,
    ) = IndexedMediaSegment(
        id = id,
        filePath = id,
        fileName = "$id.mp4",
        sidecarPath = null,
        sizeBytes = 100,
        startedAtEpochMs = start,
        stoppedAtEpochMs = start + 60_000,
        durationMs = 60_000,
        segmentNumber = number,
        recordingSessionId = sessionId,
        eventId = eventId,
        eventRole = eventRole,
        protected = eventId != null,
        sourceRole = IndexedSourceRole.SURROUND,
        layoutKind = IndexedLayoutKind.FOUR_LANE_V1,
        cameraId = "2",
        lanes = emptyList(),
        originalWidth = 1280,
        originalHeight = 5140,
    )

    private fun sidecar(role: String, cameraId: String, sessionId: String): String = """
        {
          "schemaVersion": 5,
          "cameraId": "$cameraId",
          "sourceRole": "$role",
          "layoutKind": "SINGLE_V1",
          "recordingSessionId": "$sessionId",
          "segmentNumber": 1,
          "startedAtEpochMs": 1000,
          "stoppedAtEpochMs": 61000,
          "actualTrack": { "width": 3840, "height": 2160, "durationMs": 60000 },
          "profile": { "size": { "width": 3840, "height": 2160 }, "bitrateBps": 14000000 }
        }
    """.trimIndent()
}
