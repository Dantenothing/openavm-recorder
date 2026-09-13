package com.dante.zeekrbridge.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MediaExportPlanTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun trimRangeIsSplitAcrossPhysicalSegments() {
        val segments = listOf(segment("a", 0, 60_000), segment("b", 60_000, 60_000))

        val plan = MediaExportPlanner.build(segments, MediaExportTarget.ORIGINAL, 50_000, 80_000)

        assertEquals(2, plan.clips.size)
        assertEquals(50_000, plan.clips[0].clipStartMs)
        assertEquals(60_000, plan.clips[0].clipEndMs)
        assertEquals(0, plan.clips[1].clipStartMs)
        assertEquals(20_000, plan.clips[1].clipEndMs)
        assertEquals(30_000, plan.outputDurationMs)
        assertNull(plan.clips[0].crop)
    }

    @Test
    fun laneSelectionUsesFrozenDisplayOrderAndCoordinates() {
        val segment = segment("ordered", 0, 60_000).copy(
            lanes = listOf(
                IndexedLane("Right", 0, 1280, 0, 1280, displayOrder = 4, lane = 1),
                IndexedLane("Front", 0, 1280, 3855, 5135, displayOrder = 1, lane = 4),
                IndexedLane("Left", 0, 1280, 1285, 2565, displayOrder = 3, lane = 2),
                IndexedLane("Rear", 0, 1280, 2570, 3850, displayOrder = 2, lane = 3),
            ),
        )

        val crop = MediaExportPlanner.cropFor(segment, MediaExportTarget.FRONT)!!

        assertEquals(3855, crop.y0)
        assertEquals(5135, crop.y1)
    }

    @Test
    fun legacyCompositeFallsBackToKnownFivePixelSeparators() {
        val crop = MediaExportPlanner.cropFor(segment("legacy", 0, 60_000), MediaExportTarget.RIGHT)!!

        assertEquals(3855, crop.y0)
        assertEquals(5135, crop.y1)
    }

    @Test
    fun gridCompositeOffersAndCropsNeutralQuadrantsWithoutDirectionClaims() {
        val grid = segment("grid", 0, 60_000).copy(
            layoutKind = IndexedLayoutKind.FOUR_LANE_GRID_2X2,
            lanes = FourLaneLayoutClassifier.classify(2560, 2560)!!.lanes,
            originalWidth = 2560,
            originalHeight = 2560,
        )

        assertEquals(
            listOf(
                MediaExportTarget.ORIGINAL,
                MediaExportTarget.TOP_LEFT,
                MediaExportTarget.TOP_RIGHT,
                MediaExportTarget.BOTTOM_LEFT,
                MediaExportTarget.BOTTOM_RIGHT,
            ),
            MediaExportPlanner.supportedTargets(listOf(grid)),
        )
        val crop = MediaExportPlanner.cropFor(grid, MediaExportTarget.BOTTOM_RIGHT)!!
        assertEquals(PixelCrop(2560, 2560, 1280, 2560, 1280, 2560), crop)
        assertThrows(IllegalArgumentException::class.java) {
            MediaExportPlanner.cropFor(grid, MediaExportTarget.FRONT)
        }
    }

    @Test
    fun topLeftPixelCropIsConvertedToMedia3BottomLeftCoordinates() {
        val normalized = PixelCrop(1280, 5140, 0, 1280, 0, 1280).normalizedForMedia3()

        assertEquals(-1f, normalized.left)
        assertEquals(1f, normalized.right)
        assertEquals(1f, normalized.top)
        assertTrue(normalized.bottom in 0.49f..0.51f)
    }

    @Test
    fun interruptionGapIsReportedButNotInsertedIntoOutputDuration() {
        val first = segment("a", 0, 60_000)
        val second = segment("b", 20 * 60_000, 60_000)

        val plan = MediaExportPlanner.build(listOf(first, second), MediaExportTarget.ORIGINAL)

        assertEquals(1, plan.missingGapCount)
        assertEquals(19 * 60_000, plan.missingGapDurationMs)
        assertEquals(120_000, plan.outputDurationMs)
    }

    @Test
    fun timeLapseMetadataIsPreservedWhileTrimUsesFinishedVideoTime() {
        val timeLapse = segment("timelapse", 0, 60_000).copy(
            recordingMode = IndexedRecordingMode.TIME_LAPSE,
            timeLapseMultiplier = 60,
            realDurationMs = 3_600_000,
        )

        val plan = MediaExportPlanner.build(
            listOf(timeLapse),
            MediaExportTarget.ORIGINAL,
            requestedStartMs = 10_000,
            requestedEndMs = 20_000,
        )

        assertEquals(IndexedRecordingMode.TIME_LAPSE, plan.recordingMode)
        assertEquals(60, plan.timeLapseMultiplier)
        assertEquals(10_000, plan.outputDurationMs)
        assertEquals(10_000, plan.clips.single().clipStartMs)
        assertEquals(20_000, plan.clips.single().clipEndMs)
    }

    @Test
    fun cabinCannotRequestDirectionExport() {
        val cabin = segment("cabin", 0, 60_000).copy(
            sourceRole = IndexedSourceRole.CABIN,
            layoutKind = IndexedLayoutKind.SINGLE_V1,
        )

        assertThrows(IllegalArgumentException::class.java) {
            MediaExportPlanner.build(listOf(cabin), MediaExportTarget.FRONT)
        }
    }

    private fun segment(name: String, start: Long, duration: Long): IndexedMediaSegment {
        val file = temp.newFile("$name.mp4")
        return IndexedMediaSegment(
            id = name,
            filePath = file.absolutePath,
            fileName = file.name,
            sidecarPath = null,
            sizeBytes = 100,
            startedAtEpochMs = start,
            stoppedAtEpochMs = start + duration,
            durationMs = duration,
            segmentNumber = 1,
            recordingSessionId = "session",
            eventId = null,
            eventRole = null,
            protected = false,
            sourceRole = IndexedSourceRole.SURROUND,
            layoutKind = IndexedLayoutKind.FOUR_LANE_V1,
            cameraId = "2",
            lanes = emptyList(),
            originalWidth = 1280,
            originalHeight = 5140,
        )
    }
}
