package com.dante.zeekrbridge.sound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalWavSaverTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun wavFile(name: String, marker: Byte): File {
        val file = tmp.newFile(name)
        val value = marker.toFloat() / 32768f
        WavPcmWriter.writeWavFile(file, 1, 44100) { sink ->
            sink.writeFrames(FloatArray(8) { value }, 8)
        }
        return file
    }

    @Test
    fun savesNewFileWithoutBackup() {
        val dir = tmp.newFolder("sounds")
        val backupDir = tmp.newFolder("backups")
        val source = wavFile("source.wav", 1)
        val saved = LocalWavSaver(dir, backupDir).save(source, "tone.wav")
        assertTrue(File(dir, "tone.wav").isFile)
        assertEquals("tone.wav", saved.file.name)
        assertNull(saved.backupName)
        assertEquals(0, backupDir.listFiles()!!.size)
    }

    @Test
    fun sameNameBacksUpOriginalBeforeReplace() {
        val dir = tmp.newFolder("sounds")
        val backupDir = tmp.newFolder("backups")
        val existing = wavFile("tone.wav", 1)
        existing.copyTo(File(dir, "tone.wav"), overwrite = true)
        val source = wavFile("new.wav", 2)
        val saved = LocalWavSaver(dir, backupDir).save(source, "tone.wav")
        assertEquals(1, backupDir.listFiles()!!.size)
        val backup = backupDir.listFiles()!!.single()
        assertEquals(1, backup.readBytes()[44].toInt() and 0xff) // original content preserved
        assertEquals(2, File(dir, "tone.wav").readBytes()[44].toInt() and 0xff) // new content in place
        assertTrue(saved.backupName != null)
    }

    @Test
    fun missingSourceLeavesOriginalUntouched() {
        val dir = tmp.newFolder("sounds")
        val backupDir = tmp.newFolder("backups")
        val existing = wavFile("tone.wav", 1)
        existing.copyTo(File(dir, "tone.wav"), overwrite = true)
        val missing = File(tmp.root, "missing.wav")
        try {
            LocalWavSaver(dir, backupDir).save(missing, "tone.wav")
            assertTrue("expected failure", false)
        } catch (t: SoundIoException) {
            assertEquals("SOURCE_MISSING", t.code)
        }
        assertEquals(1, File(dir, "tone.wav").readBytes()[44].toInt() and 0xff)
        assertEquals(0, backupDir.listFiles()!!.size)
        assertTrue(dir.listFiles()!!.none { it.name.contains(".installing-") })
    }

    @Test
    fun verifyFailureRestoresOriginalAndCleansUp() {
        val dir = tmp.newFolder("sounds")
        val backupDir = tmp.newFolder("backups")
        val existing = wavFile("tone.wav", 1)
        existing.copyTo(File(dir, "tone.wav"), overwrite = true)
        val source = wavFile("new.wav", 2)
        try {
            LocalWavSaver(dir, backupDir).save(source, "tone.wav", verify = { false })
            assertTrue("expected failure", false)
        } catch (t: SoundIoException) {
            assertEquals("VERIFY_FAILED", t.code)
        }
        // original content restored, no backup/temp leftovers
        assertEquals(1, File(dir, "tone.wav").readBytes()[44].toInt() and 0xff)
        assertEquals(0, backupDir.listFiles()!!.size)
        assertEquals(1, dir.listFiles()!!.size)
    }

    @Test
    fun sanitizesNameAndAppendsWav() {
        val dir = tmp.newFolder("sounds")
        val backupDir = tmp.newFolder("backups")
        val source = wavFile("source.wav", 1)
        val saved = LocalWavSaver(dir, backupDir).save(source, "my?tone.mp3")
        assertEquals("my tone.wav", saved.file.name)
        assertTrue(File(dir, "my tone.wav").isFile)
    }
}
