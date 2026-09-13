package com.dante.zeekrbridge.sound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

class WavPcmWriterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun writesLittleEndian16BitPcmAndReReadsClean() {
        val file = tmp.newFile("out.wav")
        val frames = floatArrayOf(0.5f, -0.5f, 1f, -1f, 0f)
        WavPcmWriter.writeWavFile(file, 1, 44100) { sink ->
            sink.writeFrames(frames, frames.size)
        }

        val info = WavPcmValidator.validateWavFile(file)
        assertTrue(info.valid)
        assertEquals(1, info.channels)
        assertEquals(44100, info.sampleRate)
        assertEquals(16, info.bitsPerSample)
        assertEquals((frames.size * 2L), info.dataBytes)
        assertEquals(frames.size * 1000L / 44100L, info.durationMs)
        assertEquals(44L + frames.size * 2L, info.totalBytes)

        val bytes = file.readBytes()
        // 0.5 * 32767 = 16383.5 -> 16384 (0x4000), little-endian 00 40
        assertEquals(0x00, bytes[44].toInt() and 0xff)
        assertEquals(0x40, bytes[45].toInt() and 0xff)
        // -0.5 -> Math.round(-16383.5) = -16383 (0xC001), little-endian 01 C0
        assertEquals(0x01, bytes[46].toInt() and 0xff)
        assertEquals(0xc0, bytes[47].toInt() and 0xff)
        // 1.0 clamps to 32767 (0x7FFF)
        assertEquals(0xff, bytes[48].toInt() and 0xff)
        assertEquals(0x7f, bytes[49].toInt() and 0xff)
        // -1.0 clamps to -32768 (0x8000)
        assertEquals(0x00, bytes[50].toInt() and 0xff)
        assertEquals(0x80, bytes[51].toInt() and 0xff)
    }

    @Test
    fun stereoFileHasMatchingByteRateAndBlockAlign() {
        val file = tmp.newFile("stereo.wav")
        WavPcmWriter.writeWavFile(file, 2, 44100) { sink ->
            sink.writeFrames(floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f), 2)
        }
        val info = WavPcmValidator.validateWavFile(file)
        assertTrue(info.valid)
        assertEquals(2, info.channels)
        assertEquals(8L, info.dataBytes)
        assertEquals(4, file.readBytes()[32].toInt() and 0xff) // blockAlign 4
    }

    @Test
    fun writeHeaderProducesValidatorCompatibleStream() {
        val out = ByteArrayOutputStream()
        WavPcmWriter.writeHeader(out, 1, 44100, 8L)
        out.write(byteArrayOf(0x00, 0x40, 0x00, 0xc0.toByte(), 0xff.toByte(), 0x7f, 0x00, 0x80.toByte()))
        val info = WavPcmValidator.validate(out.toByteArray().inputStream())
        assertTrue(info.valid)
        assertEquals(44100, info.sampleRate)
        assertEquals(8L, info.dataBytes)
    }

    @Test
    fun rejectsTruncatedOrSizeMismatchedFile() {
        val file = tmp.newFile("truncated.wav")
        file.writeBytes(ByteArray(44))
        val info = WavPcmValidator.validateWavFile(file)
        assertFalse(info.valid)
        assertTrue(info.reason != null)

        val bad = tmp.newFile("bad.wav")
        val bytes = ByteArray(44 + 16)
        bytes[0] = 'R'.code.toByte()
        bytes[1] = 'I'.code.toByte()
        bytes[2] = 'F'.code.toByte()
        bytes[3] = 'F'.code.toByte()
        bytes[8] = 'W'.code.toByte()
        bytes[9] = 'A'.code.toByte()
        bytes[10] = 'V'.code.toByte()
        bytes[11] = 'E'.code.toByte()
        bad.writeBytes(bytes)
        assertFalse(WavPcmValidator.validateWavFile(bad).valid)
    }

    @Test
    fun validatesDurationFromDataBytes() {
        val info = WavPcmInfo(
            valid = true,
            channels = 2,
            sampleRate = 44100,
            bitsPerSample = 16,
            dataBytes = 44100 * 4L,
            totalBytes = 44 + 44100 * 4L,
        )
        assertEquals(1000L, info.durationMs)
    }
}
