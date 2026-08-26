package com.dante.zeekrbridge.sound

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioResamplerTest {
    @Test
    fun sameRateKeepsLength() {
        assertEquals(44100L, AudioResampler.outputFrameCount(44100L, 44100, 44100))
        assertEquals(0L, AudioResampler.outputFrameCount(0L, 44100, 44100))
    }

    @Test
    fun halfRateDoublesLength() {
        assertEquals(200L, AudioResampler.outputFrameCount(100L, 22050, 44100))
        assertEquals(88200L, AudioResampler.outputFrameCount(44100L, 22050, 44100))
    }

    @Test
    fun fortyEightKTo44100ExactFullSecond() {
        // 48000 input frames at 48k == 1s == 44100 output frames
        assertEquals(44100L, AudioResampler.outputFrameCount(48000L, 48000, 44100))
    }

    @Test
    fun fortyEightKTo44100CeilForPartial() {
        // ceil(100 * 44100 / 48000) = ceil(91.875) = 92
        assertEquals(92L, AudioResampler.outputFrameCount(100L, 48000, 44100))
    }

    @Test
    fun lowRatesMapTo44100() {
        // 1 frame at 8k -> ceil(44100/8000) = 6
        assertEquals(6L, AudioResampler.outputFrameCount(1L, 8000, 44100))
        // 8000 frames at 8k (1s) -> 44100
        assertEquals(44100L, AudioResampler.outputFrameCount(8000L, 8000, 44100))
        // 16000 frames at 16k (1s) -> 44100
        assertEquals(44100L, AudioResampler.outputFrameCount(16000L, 16000, 44100))
        // 96000 frames at 96k (1s) -> 44100
        assertEquals(44100L, AudioResampler.outputFrameCount(96000L, 96000, 44100))
    }

    @Test
    fun sourceWindowCoversInterpolationNeighbours() {
        val win = AudioResampler.sourceWindowForOutput(0L, 92L, 48000, 44100, 100L)
        assertEquals(0L, win[0])
        // Last output frame 91 needs input floor(91*48000/44100)+1 = floor(99.04)+1 = 100 -> clamp 99
        assertEquals(100L, win[1])
    }
}
