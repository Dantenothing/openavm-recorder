package com.dante.zeekrbridge.sound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZeekrSoundPresetTest {
    @Test
    fun auNzPresetsUseDocumentedDirectories() {
        assertEquals("Lock Status Tones", ZeekrSoundPreset.ZEEKR_7X_AUNZ_OS_2_1.targetDirectoryName)
        assertEquals("解闭锁音效", ZeekrSoundPreset.ZEEKR_7X_AUNZ_LEGACY.targetDirectoryName)
        assertEquals(5, ZeekrSoundPreset.ZEEKR_7X_AUNZ_OS_2_1.maxWavFiles)
        assertEquals(5, ZeekrSoundPreset.ZEEKR_7X_AUNZ_LEGACY.maxWavFiles)
    }

    @Test
    fun zeekrSizeLimitIsStrictlyUnderOneMillionBytes() {
        val preset = ZeekrSoundPreset.ZEEKR_7X_AUNZ_OS_2_1
        assertTrue(preset.acceptsSize(999_999L))
        assertFalse(preset.acceptsSize(1_000_000L))
    }

    @Test
    fun genericWavDoesNotImposeZeekrFolderOrSizeRules() {
        val preset = ZeekrSoundPreset.GENERIC_WAV
        assertNull(preset.targetDirectoryName)
        assertNull(preset.maxWavFiles)
        assertTrue(preset.acceptsSize(Long.MAX_VALUE))
    }
}
