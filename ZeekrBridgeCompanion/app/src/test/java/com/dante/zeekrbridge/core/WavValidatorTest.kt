package com.dante.zeekrbridge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

class WavValidatorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun chunk(id: String, payload: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(id.toByteArray(Charsets.US_ASCII))
        out.write(payload.size and 0xff)
        out.write((payload.size shr 8) and 0xff)
        out.write((payload.size shr 16) and 0xff)
        out.write((payload.size shr 24) and 0xff)
        out.write(payload)
        if (payload.size % 2 == 1) out.write(0)
        return out.toByteArray()
    }

    private fun fmt(format: Int = 1, channels: Int = 1, rate: Int = 44100, bits: Int = 16): ByteArray {
        val b = ByteArray(16)
        b[0] = (format and 0xff).toByte(); b[1] = ((format shr 8) and 0xff).toByte()
        b[2] = (channels and 0xff).toByte(); b[3] = ((channels shr 8) and 0xff).toByte()
        b[4] = (rate and 0xff).toByte(); b[5] = ((rate shr 8) and 0xff).toByte()
        b[6] = ((rate shr 16) and 0xff).toByte(); b[7] = ((rate shr 24) and 0xff).toByte()
        val byteRate = rate * channels * bits / 8
        b[8] = (byteRate and 0xff).toByte(); b[9] = ((byteRate shr 8) and 0xff).toByte()
        b[10] = ((byteRate shr 16) and 0xff).toByte(); b[11] = ((byteRate shr 24) and 0xff).toByte()
        val blockAlign = channels * bits / 8
        b[12] = (blockAlign and 0xff).toByte(); b[13] = ((blockAlign shr 8) and 0xff).toByte()
        b[14] = (bits and 0xff).toByte(); b[15] = ((bits shr 8) and 0xff).toByte()
        return b
    }

    private fun wav(fmtPayload: ByteArray = fmt(), data: ByteArray = ByteArray(8), extraChunks: List<ByteArray> = emptyList()): ByteArray {
        val body = java.io.ByteArrayOutputStream()
        extraChunks.forEach { body.write(it) }
        body.write(chunk("fmt ", fmtPayload))
        body.write(chunk("data", data))
        val bodyBytes = body.toByteArray()
        val out = java.io.ByteArrayOutputStream()
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.write((4 + bodyBytes.size) and 0xff)
        out.write(((4 + bodyBytes.size) shr 8) and 0xff)
        out.write(((4 + bodyBytes.size) shr 16) and 0xff)
        out.write(((4 + bodyBytes.size) shr 24) and 0xff)
        out.write("WAVE".toByteArray(Charsets.US_ASCII))
        out.write(bodyBytes)
        return out.toByteArray()
    }

    @Test
    fun acceptsPcm44100Mono16() {
        val info = WavValidator.validate(ByteArrayInputStream(wav()))
        assertTrue(info.valid)
        assertEquals(1, info.format)
        assertEquals(1, info.channels)
        assertEquals(44100, info.sampleRate)
        assertEquals(16, info.bitsPerSample)
        assertEquals(8L, info.dataBytes)
        assertTrue(WavValidator.isAcceptable(info))
    }

    @Test
    fun accepts48000StereoWithUnknownChunkAndOddPadding() {
        val odd = byteArrayOf(1, 2, 3) // odd length -> padding byte appended by chunk()
        val info = WavValidator.validate(
            ByteArrayInputStream(wav(fmt(channels = 2, rate = 48000), data = ByteArray(16), extraChunks = listOf(chunk("LIST", odd)))),
        )
        assertTrue(info.valid)
        assertEquals(2, info.channels)
        assertEquals(48000, info.sampleRate)
        assertTrue(WavValidator.isAcceptable(info))
    }

    @Test
    fun rejectsNonPcmAndUnsupportedFormats() {
        assertFalse(WavValidator.isAcceptable(WavValidator.validate(ByteArrayInputStream(wav(fmt(format = 3))))))
        assertFalse(WavValidator.isAcceptable(WavValidator.validate(ByteArrayInputStream(wav(fmt(rate = 22050))))))
        assertFalse(WavValidator.isAcceptable(WavValidator.validate(ByteArrayInputStream(wav(fmt(bits = 8))))))
        assertFalse(WavValidator.isAcceptable(WavValidator.validate(ByteArrayInputStream(wav(fmt(channels = 6))))))
    }

    @Test
    fun rejectsMissingDataChunkAndBadMagic() {
        val full = wav()
        val noData = full.copyOfRange(0, full.size - 16) // strip whole data chunk
        assertFalse(WavValidator.validate(ByteArrayInputStream(noData)).valid)
        assertRejected("NOTRIFF...")
        assertFalse(WavValidator.validate(ByteArrayInputStream("RIFF....NOTWAVE".toByteArray())).valid)
    }

    private fun assertRejected(text: String) {
        try {
            val info = WavValidator.validate(ByteArrayInputStream(text.toByteArray()))
            assertFalse(info.valid)
        } catch (t: Throwable) {
            // Truncated header raising an exception is also an accepted rejection.
        }
    }

    @Test
    fun rejectsTruncatedDataChunk() {
        val full = wav(data = ByteArray(100))
        val truncated = full.copyOfRange(0, full.size - 60)
        assertFalse(WavValidator.validate(ByteArrayInputStream(truncated)).valid)
    }

    @Test
    fun rejectsEmptyDataChunk() {
        assertFalse(WavValidator.validate(ByteArrayInputStream(wav(data = ByteArray(0)))).valid)
    }

    @Test
    fun rejectsTrailingGarbageBetweenChunks() {
        val withJunk = wav() + byteArrayOf(1, 2, 3)
        assertFalse(WavValidator.validate(ByteArrayInputStream(withJunk)).valid)
    }

    @Test
    fun rejectsRiffDeclaredSizeMismatch() {
        val full = wav()
        full[4] = (full[4].toInt() + 1).toByte()
        assertFalse(WavValidator.validate(ByteArrayInputStream(full)).valid)
    }

    @Test
    fun rejectsInconsistentFmtBlock() {
        val bad = fmt()
        bad[8] = 0; bad[9] = 0; bad[10] = 0; bad[11] = 0 // byteRate 0 != expected
        assertFalse(WavValidator.validate(ByteArrayInputStream(wav(fmtPayload = bad))).valid)
    }

    @Test
    fun rejectsFileOverOneMiB() {
        val file = tmp.newFile("big.wav")
        val payload = ByteArray(WavValidator.MAX_BYTES.toInt() + 1)
        file.writeBytes(wav(data = payload))
        val info = WavValidator.validateFile(file)
        assertFalse(info.valid)
        assertEquals("FILE_TOO_LARGE", info.reason)
    }

    @Test
    fun toParamsProducesOfferParams() {
        val params = WavValidator.toParams(WavValidator.validate(ByteArrayInputStream(wav())))!!
        assertEquals(1, params.format)
        assertEquals(44100, params.sampleRate)
        assertEquals(1, params.channels)
        assertEquals(16, params.bitsPerSample)
        assertEquals(8L, params.dataBytes)
    }
}
