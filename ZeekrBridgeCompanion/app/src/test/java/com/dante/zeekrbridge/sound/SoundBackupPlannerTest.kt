package com.dante.zeekrbridge.sound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundBackupPlannerTest {
    @Test
    fun noConflictMeansNoBackup() {
        val plan = SoundBackupPlanner.plan("tone.wav", listOf("other.wav"), epoch = 1234L)
        assertEquals("tone.wav", plan.finalName)
        assertEquals("tone.installing-1234.wav.tmp", plan.tempName)
        assertNull(plan.backupName)
        assertFalse(plan.conflict)
    }

    @Test
    fun sameNameDetectsCaseInsensitiveConflictAndCreatesBackup() {
        val plan = SoundBackupPlanner.plan("tone.wav", listOf("TONE.WAV", "other.wav"), epoch = 1234L)
        assertTrue(plan.conflict)
        assertEquals("tone.wav", plan.finalName)
        assertEquals("tone.backup-1234.wav.bak", plan.backupName)
    }

    @Test
    fun tempNameAvoidsExistingCollisions() {
        val plan = SoundBackupPlanner.plan(
            "tone.wav",
            listOf("tone.wav", "tone.installing-1234.wav.tmp"),
            epoch = 1234L,
        )
        assertEquals("tone.installing-1234-1.wav.tmp", plan.tempName)
    }

    @Test
    fun backupNameAvoidsCollisions() {
        val plan = SoundBackupPlanner.plan(
            "tone.wav",
            listOf("tone.wav", "tone.backup-1234.wav.bak"),
            epoch = 1234L,
        )
        assertEquals("tone.backup-1234-1.wav.bak", plan.backupName)
    }

    @Test
    fun sanitizesRequestedNameBeforePlanning() {
        val plan = SoundBackupPlanner.plan("my?tone.mp3", listOf("other.wav"), epoch = 7L)
        assertEquals("my tone.wav", plan.finalName)
        assertNotNull(plan.tempName)
    }
}
