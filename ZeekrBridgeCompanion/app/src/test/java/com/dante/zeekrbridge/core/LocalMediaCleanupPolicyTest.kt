package com.dante.zeekrbridge.core

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalMediaCleanupPolicyTest {
    @Test
    fun onlyExpiredUnprotectedSegmentsAreSelected() {
        val old = segment("old", stoppedAt = 1_000L, protected = false)
        val protected = segment("protected", stoppedAt = 1_000L, protected = true)
        val recent = segment("recent", stoppedAt = 20_000L, protected = false)

        val result = LocalMediaCleanupPolicy.expiredUnprotected(
            listOf(old, protected, recent),
            cutoffEpochMs = 10_000L,
        )

        assertEquals(listOf("old"), result.map { it.id })
    }

    private fun segment(id: String, stoppedAt: Long, protected: Boolean) = IndexedMediaSegment(
        id = id,
        filePath = "$id.mp4",
        fileName = "$id.mp4",
        sidecarPath = null,
        sizeBytes = 1L,
        startedAtEpochMs = stoppedAt - 1_000L,
        stoppedAtEpochMs = stoppedAt,
        durationMs = 1_000L,
        segmentNumber = 1,
        recordingSessionId = "session-$id",
        eventId = if (protected) "event-$id" else null,
        eventRole = null,
        protected = protected,
        sourceRole = IndexedSourceRole.SURROUND,
        layoutKind = IndexedLayoutKind.FOUR_LANE_V1,
        cameraId = "2",
        lanes = emptyList(),
        originalWidth = 1280,
        originalHeight = 5140,
    )
}
