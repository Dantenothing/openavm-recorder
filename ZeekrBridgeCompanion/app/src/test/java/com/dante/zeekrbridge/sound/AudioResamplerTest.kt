package com.dante.zeekrbridge.sound

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioResamplerTest {
    @Test
    fun zeekrTargetIs48kHz() {
        assertEquals(48000, AudioResampler.TARGET_RATE)
        assertEquals(48000L, AudioResampler.outputFrameCount(48000L, 48000))
    }

    @Test
    fun fortyFourOneKTo48kExactFullSecond() {
        assertEquals(48000L, AudioResampler.outputFrameCount(44100L, 44100))
    }

    @Test
    fun fortyFourOneKTo48kRoundsPartialSelectionUp() {
        assertEquals(109L, AudioResampler.outputFrameCount(100L, 44100))
    }

    @Test
    fun lowRatesMapTo48k() {
        assertEquals(6L, AudioResampler.outputFrameCount(1L, 8000))
        assertEquals(48000L, AudioResampler.outputFrameCount(8000L, 8000))
        assertEquals(48000L, AudioResampler.outputFrameCount(16000L, 16000))
        assertEquals(48000L, AudioResampler.outputFrameCount(96000L, 96000))
    }

    @Test
    fun sourceWindowCoversInterpolationNeighbours() {
        val win = AudioResampler.sourceWindowForOutput(0L, 109L, 44100, 48000, 100L)
        assertEquals(0L, win[0])
        assertEquals(100L, win[1])
    }
}
