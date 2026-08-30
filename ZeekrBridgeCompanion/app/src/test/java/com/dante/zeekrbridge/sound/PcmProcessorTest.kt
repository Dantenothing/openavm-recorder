package com.dante.zeekrbridge.sound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.abs
import kotlin.math.max

class PcmProcessorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun ramp(rate: Int, channels: Int, frames: Int): FloatArray {
        val out = FloatArray(frames * channels)
        for (f in 0 until frames) {
            for (c in 0 until channels) {
                out[f * channels + c] = (f % 100) / 100f
            }
        }
        return out
    }

    private fun process(
        input: PcmInput,
        params: AudioEditParams,
    ): Pair<File, ProcessedSound> {
        val file = tmp.newFile("out-${System.nanoTime()}.wav")
        val result = PcmProcessor.process(input, params, out = file)
        return file to result
    }

    private fun readSamples(file: File): FloatArray {
        val bytes = file.readBytes()
        val dataSize = ((bytes[40].toLong() and 0xff) or
            ((bytes[41].toLong() and 0xff) shl 8) or
            ((bytes[42].toLong() and 0xff) shl 16) or
            ((bytes[43].toLong() and 0xff) shl 24))
        val samples = FloatArray((dataSize / 2).toInt())
        var bi = 44
        for (i in samples.indices) {
            val lo = bytes[bi++].toInt() and 0xff
            val hi = bytes[bi++].toInt() and 0xff
            val s = (lo or (hi shl 8)).toShort()
            samples[i] = s / 32768f
        }
        return samples
    }

    @Test
    fun trimBoundariesAndOutputDuration() {
        val input = ArrayPcmInput(48000, 1, ramp(48000, 1, 100))
        val (file, result) = process(
            input,
            AudioEditParams(startFrame = 10, endFrame = 30, gain = 1.0, outputChannels = 1),
        )
        val info = WavPcmValidator.validateWavFile(file)
        assertTrue(info.valid)
        assertEquals(20L, result.outFrames)
        assertEquals(40L, result.dataBytes)
        assertEquals(20 * 1000L / 48000L, info.durationMs)
        val samples = readSamples(file)
        assertEquals(20, samples.size)
        // first sample equals input frame 10 (0.10f)
        assertTrue(abs(samples[0] - 0.1f) < 0.002f)
        // last sample equals input frame 29 (0.29f)
        assertTrue(abs(samples[19] - 0.29f) < 0.002f)
    }

    @Test
    fun resamples44100To48000WithExpectedLength() {
        val input = ArrayPcmInput(44100, 1, ramp(44100, 1, 100))
        val (file, result) = process(
            input,
            AudioEditParams(startFrame = 0, endFrame = 100, gain = 1.0, outputChannels = 1),
        )
        val info = WavPcmValidator.validateWavFile(file)
        assertTrue(info.valid)
        assertEquals(48000, result.sampleRate)
        assertEquals(109L, result.outFrames)
        assertEquals(218L, result.dataBytes)
        val samples = readSamples(file)
        assertEquals(109, samples.size)
        assertTrue(abs(samples[0] - 0.0f) < 0.002f)
        // last output frame lands near input end (0.99)
        assertTrue(abs(samples[108] - 0.99f) < 0.02f)
    }

    @Test
    fun stereoToMonoAveragesAndStereoKeepsChannels() {
        val stereo = FloatArray(20)
        for (f in 0 until 10) {
            stereo[f * 2] = 0.4f
            stereo[f * 2 + 1] = 0.2f
        }
        val input = ArrayPcmInput(48000, 2, stereo)
        val (monoFile, monoResult) = process(
            input,
            AudioEditParams(startFrame = 0, endFrame = 10, gain = 1.0, outputChannels = 1),
        )
        assertEquals(1, monoResult.channels)
        val mono = readSamples(monoFile)
        assertTrue(abs(mono[0] - 0.3f) < 0.002f)

        val (stereoFile, stereoResult) = process(
            input,
            AudioEditParams(startFrame = 0, endFrame = 10, gain = 1.0, outputChannels = 2),
        )
        assertEquals(2, stereoResult.channels)
        val st = readSamples(stereoFile)
        assertEquals(20, st.size)
        assertTrue(abs(st[0] - 0.4f) < 0.002f)
        assertTrue(abs(st[1] - 0.2f) < 0.002f)
    }

    @Test
    fun gainScalesAndSaturationClamps() {
        val input = ArrayPcmInput(48000, 1, floatArrayOf(0.5f, -0.5f, 0.25f))
        val (file, result) = process(
            input,
            AudioEditParams(startFrame = 0, endFrame = 3, gain = 10.0, outputChannels = 1),
        )
        assertTrue(result.measuredPeak > 0.49)
        val samples = readSamples(file)
        assertTrue(abs(samples[0] - 1f) < 0.001f) // 0.5*10 clamped
        assertTrue(abs(samples[1] + 1f) < 0.001f)
        assertTrue(abs(samples[2] - 1f) < 0.001f) // 0.25*10 = 2.5 clamped
    }

    @Test
    fun normalizationBringsPeakToTarget() {
        val input = ArrayPcmInput(48000, 1, floatArrayOf(0.5f, -0.25f, 0.1f))
        val (file, _) = process(
            input,
            AudioEditParams(
                startFrame = 0,
                endFrame = 3,
                gain = 1.0,
                normalize = true,
                normalizeTarget = 0.89,
                outputChannels = 1,
            ),
        )
        val samples = readSamples(file)
        val peak = samples.maxOf { abs(it) }
        assertTrue(abs(peak - 0.89f) < 0.01f)
    }

    @Test
    fun fadeInOutBoundarySamples() {
        val frames = 48000 * 10
        val input = ArrayPcmInput(48000, 1, FloatArray(frames) { 1f })
        // 100 ms fades
        val (file, _) = process(
            input,
            AudioEditParams(
                startFrame = 0,
                endFrame = frames.toLong(),
                gain = 1.0,
                fadeInMs = 100,
                fadeOutMs = 100,
                outputChannels = 1,
            ),
        )
        val samples = readSamples(file)
        assertEquals(frames, samples.size)
        val fadeIn = 4800
        val fadeOut = 4800
        assertTrue(abs(samples[0]) < 0.002f)          // fade-in start ~0
        assertTrue(abs(samples[fadeIn - 1] - 1f) < 0.002f) // fade-in end ~1
        assertTrue(abs(samples[frames - fadeOut] - 1f) < 0.002f) // fade-out start ~1
        assertTrue(abs(samples[frames - 1]) < 0.002f) // fade-out end ~0
        // middle untouched
        assertTrue(abs(samples[frames / 2] - 1f) < 0.002f)
    }

    @Test
    fun emptyOrOutOfRangeSelectionRejected() {
        val input = ArrayPcmInput(48000, 1, FloatArray(100))
        val file = File(tmp.root, "empty.wav")
        try {
            PcmProcessor.process(
                input,
                AudioEditParams(startFrame = 50, endFrame = 50, outputChannels = 1),
                out = file,
            )
            assertTrue("expected rejection", false)
        } catch (t: SoundInputException) {
            assertEquals("EMPTY_SELECTION", t.code)
        }
        assertTrue(!file.exists())
    }

    @Test
    fun cancellationStopsAndReleasesOutput() {
        val frames = 48000 * 60
        val input = ArrayPcmInput(48000, 1, FloatArray(frames) { 0.5f })
        val file = File(tmp.root, "cancel.wav")
        var cancelled = false
        var calls = 0
        try {
            PcmProcessor.process(
                input,
                AudioEditParams(startFrame = 0, endFrame = frames.toLong(), outputChannels = 1),
                cancel = {
                    calls++
                    if (calls > 3) cancelled = true
                    cancelled
                },
                out = file,
            )
            assertTrue("expected cancellation", false)
        } catch (t: SoundCancelledException) {
            assertTrue(cancelled)
        }
        assertTrue(!file.exists())
    }
}
