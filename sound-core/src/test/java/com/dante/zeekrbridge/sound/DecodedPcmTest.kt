package com.dante.zeekrbridge.sound

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class DecodedPcmTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun unsignedEightBitPcmIsCenteredAndExpandedTo16Bit() {
        val pcm = DecodedPcm.to16Bit(byteArrayOf(0, 128.toByte(), 255.toByte()), 3, 1)
        val buffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(-32768, buffer.short.toInt()); assertEquals(0, buffer.short.toInt()); assertEquals(32512, buffer.short.toInt())
    }
    @Test fun floatingPcmClipsAndHandlesNonFiniteSamples() {
        val values = floatArrayOf(-2f, -.5f, 0f, .5f, 2f, Float.NaN)
        val bytes = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { values.forEach(::putFloat) }.array()
        val output = ByteBuffer.wrap(DecodedPcm.to16Bit(bytes, 4, 2)).order(ByteOrder.LITTLE_ENDIAN)
        val actual = ShortArray(values.size) { output.short }
        assertArrayEquals(shortArrayOf(-32768, -16383, 0, 16384, 32767, 0), actual)
    }
    @Test fun signed16BitBytesRemainExact() {
        val input = byteArrayOf(0, -128, -1, 127)
        assertArrayEquals(input, DecodedPcm.to16Bit(input, 2, 2))
    }
    @Test(expected = IllegalArgumentException::class) fun incompletePcmFrameIsRejected() {
        DecodedPcm.to16Bit(byteArrayOf(1, 2, 3), 2, 2)
    }
    @Test(expected = SoundInputException::class) fun unknownPcmIsNotSilentlyTreatedAs16Bit() {
        DecodedPcm.to16Bit(ByteArray(4), 999, 1)
    }
    @Test fun vehicleImportStopsAtDurationOrCacheBudget() {
        val limits = SoundDecodeLimits(1000, 4000)
        limits.check(1000, 4000)
        assertThrows(SoundInputException::class.java) { limits.check(1001, 1) }
        assertThrows(SoundInputException::class.java) { limits.check(1, 4001) }
    }
    @Test(timeout = 1000) fun truncatedWaveformInputTerminatesInsteadOfLoopingAtEof() {
        val file = temp.newFile().apply { writeBytes(ByteArray(4)) }
        assertThrows(SoundInputException::class.java) { WaveformBuilder.build(file, PcmMeta(48000, 1, 8)) }
    }
    @Test fun waveformCancellationIsObserved() {
        val file = temp.newFile().apply { writeBytes(ByteArray(100)) }
        assertThrows(SoundCancelledException::class.java) { WaveformBuilder.build(file, PcmMeta(48000, 1, 50), cancelled = { true }) }
    }
}
