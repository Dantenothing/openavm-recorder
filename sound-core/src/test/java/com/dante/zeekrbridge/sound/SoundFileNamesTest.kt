package com.dante.zeekrbridge.sound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundFileNamesTest {
    @Test
    fun stripsFatWindowsForbiddenCharacters() {
        val cleaned = SoundFileNames.cleanBase("a<b>:c\"d/e\\f|g?h*.wav")
        // path separators strip directory components; remaining bad chars become spaces
        assertEquals("f g h", cleaned)
    }

    @Test
    fun appendsWavSuffix() {
        assertEquals("tone.wav", SoundFileNames.wavFileName("tone"))
        assertEquals("tone.wav", SoundFileNames.wavFileName("tone.mp3"))
        assertEquals("a.b.wav", SoundFileNames.wavFileName("a.b.c"))
        assertEquals("tone.wav", SoundFileNames.wavFileName("tone.wav"))
    }

    @Test
    fun trimsDotsSpacesAndControlCharacters() {
        assertEquals("tone", SoundFileNames.cleanBase("  tone...  "))
        assertEquals("a b", SoundFileNames.cleanBase("a\u0000\u0001b"))
        assertNull(SoundFileNames.cleanBase("   "))
        assertNull(SoundFileNames.cleanBase("..."))
        assertNull(SoundFileNames.cleanBase(".."))
    }

    @Test
    fun keepsChineseAndNormalizesWhitespace() {
        assertEquals("我的铃声 v2", SoundFileNames.cleanBase("  我的铃声   v2  "))
    }

    @Test
    fun fallsBackForEmptyOrDangerousInput() {
        assertEquals("sound.wav", SoundFileNames.wavFileName(""))
        assertEquals("sound.wav", SoundFileNames.wavFileName(".."))
        assertEquals("myfallback.wav", SoundFileNames.wavFileName("", "myfallback"))
        assertEquals("a b c.wav", SoundFileNames.wavFileName("a?b*c", "myfallback"))
    }

    @Test
    fun longNamesAreTruncatedToSafeLength() {
        val long = "x".repeat(500) + ".wav"
        val cleaned = SoundFileNames.cleanBase(long)!!
        assertTrue(cleaned.length <= 180)
        assertEquals(180, cleaned.length)
        assertEquals("${"x".repeat(180)}.wav", SoundFileNames.wavFileName(long))
    }
}
