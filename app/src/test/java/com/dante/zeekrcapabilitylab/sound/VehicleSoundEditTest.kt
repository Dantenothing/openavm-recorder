package com.dante.zeekrcapabilitylab.sound

import com.dante.zeekrbridge.sound.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class VehicleSoundEditTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun defaultIsFiveSecondsOrWholeShortSource() {
        val long = PcmMeta(44100, 2, 44100 * 180L)
        assertEquals(44100 * 5L, VehicleSoundEdit.defaults(long).endFrame)
        assertEquals(100L, VehicleSoundEdit.defaults(long.copy(frameCount = 100)).endFrame)
    }
    @Test fun oneMegabyteBoundaryUsesActualResampledMonoWavSize() {
        val meta = PcmMeta(48000, 2, 900000)
        assertTrue(VehicleSoundEdit(0, 499977).validFor(meta))
        assertFalse(VehicleSoundEdit(0, 499978).validFor(meta))
        assertEquals(480044L, VehicleSoundEdit(0, 220500).outputBytes(44100))
    }
    @Test fun invalidTimeInputCannotEscapeTheSource() {
        val meta = PcmMeta(48000, 1, 48000)
        assertNull(VehicleSoundEdit.secondsToFrame(Double.NaN, meta))
        assertNull(VehicleSoundEdit.secondsToFrame(-.1, meta))
        assertNull(VehicleSoundEdit.secondsToFrame(1.1, meta))
        assertEquals(24000L, VehicleSoundEdit.secondsToFrame(.5, meta))
    }
    @Test fun namesDistinguishPurposeAndAvoidCaseInsensitiveCollisions() {
        assertEquals("music-lock-1.wav", VehicleSoundNames.available("../music.mp4", SoundPurpose.LOCK, listOf("Music-Lock.wav")))
        assertEquals("music-unlock.wav", VehicleSoundNames.available("music.mp3", SoundPurpose.UNLOCK, emptyList()))
        assertTrue(VehicleSoundNames.available("a".repeat(400), SoundPurpose.LOCK, emptyList()).length <= 180)
    }
    @Test fun editedPreviewAndExportUseTheSameWaveformEffectsAndMonoFormat() {
        val input = ArrayPcmInput(44100, 2, FloatArray(44100 * 2) { .2f })
        val edit = VehicleSoundEdit(0, 22050, volumePercent = 50, fadeInMs = 50, fadeOutMs = 50)
        val file = temp.newFile("clip.wav")
        val result = PcmProcessor.process(input, edit.parameters(), out = file)
        assertTrue(result.info.valid)
        assertEquals(1, result.channels); assertEquals(48000, result.sampleRate)
        assertEquals(edit.outputBytes(44100), file.length())
        val bytes = file.readBytes()
        assertEquals(0, bytes[44].toInt()); assertEquals(0, bytes[45].toInt())
    }
}
