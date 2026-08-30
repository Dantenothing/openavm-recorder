package com.dante.zeekrbridge.core

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaLibraryPoliciesTest {
    @Test
    fun backClosesPlayerThenDetailThenSelectionBeforeExiting() {
        assertEquals(
            LibraryBackAction.CLOSE_PLAYER,
            resolveLibraryBackAction(playerOpen = true, detailOpen = true, selectionCount = 2),
        )
        assertEquals(
            LibraryBackAction.CLOSE_DETAIL,
            resolveLibraryBackAction(playerOpen = false, detailOpen = true, selectionCount = 2),
        )
        assertEquals(
            LibraryBackAction.CLEAR_SELECTION,
            resolveLibraryBackAction(playerOpen = false, detailOpen = false, selectionCount = 2),
        )
        assertEquals(
            LibraryBackAction.EXIT_LIBRARY,
            resolveLibraryBackAction(playerOpen = false, detailOpen = false, selectionCount = 0),
        )
    }

    @Test
    fun deletePlanDeduplicatesPhysicalSegmentsSharedBySessionAndEvent() {
        val shared = segment("shared", "C:/received/shared.mp4")
        val sessionOnly = segment("session-only", "C:/received/session-only.mp4")
        val duplicateObject = segment("another-id", "C:/received/shared.mp4")

        val plan = MediaDeletePlanner.plan(
            listOf(
                listOf(shared, sessionOnly),
                listOf(duplicateObject),
            ),
        )

        assertEquals(2, plan.physicalVideoCount)
        assertEquals(
            setOf("C:/received/shared.mp4", "C:/received/session-only.mp4"),
            plan.segments.map { it.filePath.replace('\\', '/') }.toSet(),
        )
    }

    private fun segment(id: String, path: String) = IndexedMediaSegment(
        id = id,
        filePath = path,
        fileName = path.substringAfterLast('/'),
        sidecarPath = null,
        sizeBytes = 100L,
        startedAtEpochMs = 1_000L,
        stoppedAtEpochMs = 61_000L,
        durationMs = 60_000L,
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
