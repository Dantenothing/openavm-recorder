package com.dante.zeekrcapabilitylab.event

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventLogRotationPolicyTest {

    private val mb = 1024L * 1024L

    private fun rotated(epochMs: Long, bytes: Long) =
        RotatedLogFile(name = EventLogRotationPolicy.rotatedName(epochMs), bytes = bytes)

    @Test
    fun rotatesAtOrAboveTheActiveCapOnly() {
        val cap = EventLogRotationPolicy.MAX_ACTIVE_FILE_BYTES
        assertFalse(EventLogRotationPolicy.shouldRotate(0L))
        assertFalse(EventLogRotationPolicy.shouldRotate(cap - 1L))
        assertTrue(EventLogRotationPolicy.shouldRotate(cap))
        assertTrue(EventLogRotationPolicy.shouldRotate(cap + 1L))
    }

    @Test
    fun rotatedNamesRoundTripAndForeignNamesAreRejected() {
        val name = EventLogRotationPolicy.rotatedName(1_700_000_000_000L)
        assertEquals("app-1700000000000.jsonl", name)
        assertTrue(EventLogRotationPolicy.isRotatedName(name))

        assertFalse(EventLogRotationPolicy.isRotatedName(EventLogRotationPolicy.ACTIVE_FILE_NAME))
        assertFalse(EventLogRotationPolicy.isRotatedName(EventLogRotationPolicy.LEGACY_FILE_NAME))
        assertFalse(EventLogRotationPolicy.isRotatedName("app-notanumber.jsonl"))
        assertFalse(EventLogRotationPolicy.isRotatedName("session-123.jsonl"))
        assertFalse(EventLogRotationPolicy.isRotatedName("app-123.txt"))
    }

    @Test
    fun nothingIsDeletedWhileWithinTheByteBudget() {
        val files = listOf(
            rotated(epochMs = 100, bytes = 2L * mb),
            rotated(epochMs = 200, bytes = 2L * mb),
        )
        assertEquals(
            emptyList<String>(),
            EventLogRotationPolicy.selectRotatedDeletions(files, maxTotalBytes = 8L * mb),
        )
    }

    @Test
    fun oldestRotatedFilesAreDeletedFirstWhenOverBudget() {
        val files = listOf(
            rotated(epochMs = 100, bytes = 4L * mb),
            rotated(epochMs = 300, bytes = 4L * mb),
            rotated(epochMs = 200, bytes = 4L * mb),
        )
        // Budget fits the newest two (300, 200); the oldest (100) is deleted.
        assertEquals(
            listOf(EventLogRotationPolicy.rotatedName(100)),
            EventLogRotationPolicy.selectRotatedDeletions(files, maxTotalBytes = 8L * mb),
        )
    }

    @Test
    fun deletionsAreReturnedOldestFirst() {
        val files = listOf(
            rotated(epochMs = 100, bytes = 4L * mb),
            rotated(epochMs = 200, bytes = 4L * mb),
            rotated(epochMs = 300, bytes = 4L * mb),
            rotated(epochMs = 400, bytes = 4L * mb),
        )
        assertEquals(
            listOf(
                EventLogRotationPolicy.rotatedName(100),
                EventLogRotationPolicy.rotatedName(200),
            ),
            EventLogRotationPolicy.selectRotatedDeletions(files, maxTotalBytes = 8L * mb),
        )
    }

    @Test
    fun newestRotatedFileSurvivesEvenWhenItAloneExceedsTheBudget() {
        val oversizedLegacy = rotated(epochMs = 500, bytes = 200L * mb)
        assertEquals(
            emptyList<String>(),
            EventLogRotationPolicy.selectRotatedDeletions(listOf(oversizedLegacy), maxTotalBytes = 8L * mb),
        )
        // Once newer evidence exists, the oversized file becomes deletable.
        val withNewer = listOf(oversizedLegacy, rotated(epochMs = 600, bytes = 1L * mb))
        assertEquals(
            listOf(oversizedLegacy.name),
            EventLogRotationPolicy.selectRotatedDeletions(withNewer, maxTotalBytes = 8L * mb),
        )
    }

    @Test
    fun foreignFileNamesAreNeverSelectedForDeletion() {
        val files = listOf(
            RotatedLogFile(name = "lab-session-1.jsonl", bytes = 500L * mb),
            RotatedLogFile(name = EventLogRotationPolicy.ACTIVE_FILE_NAME, bytes = 500L * mb),
            rotated(epochMs = 100, bytes = 1L * mb),
        )
        assertEquals(
            emptyList<String>(),
            EventLogRotationPolicy.selectRotatedDeletions(files, maxTotalBytes = 8L * mb),
        )
    }
}
