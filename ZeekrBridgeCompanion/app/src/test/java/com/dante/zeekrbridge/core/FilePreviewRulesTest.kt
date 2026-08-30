package com.dante.zeekrbridge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FilePreviewRulesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun previewableExtensionsOnly() {
        assertTrue(FilePreviewRules.isPreviewable("a.txt"))
        assertTrue(FilePreviewRules.isPreviewable("a.JSON"))
        assertTrue(FilePreviewRules.isPreviewable("a.csv"))
        assertTrue(FilePreviewRules.isPreviewable("a.log"))
        assertFalse(FilePreviewRules.isPreviewable("a.mp4"))
        assertFalse(FilePreviewRules.isPreviewable("a.wav"))
        assertFalse(FilePreviewRules.isPreviewable(null))
    }

    @Test
    fun oversizedPreviewIsRefused() {
        val big = tmp.newFile("big.json")
        big.writeBytes(ByteArray(FilePreviewRules.MAX_PREVIEW_BYTES + 1))
        assertNull(FilePreviewRules.readPreviewOrNull(big))
    }

    @Test
    fun previewIsCappedToLimit() {
        val big = tmp.newFile("big.log")
        big.writeBytes(ByteArray(FilePreviewRules.MAX_PREVIEW_BYTES * 2))
        val preview = FilePreviewRules.readPreview(big)
        assertTrue(preview.length <= FilePreviewRules.MAX_PREVIEW_BYTES)
    }

    @Test
    fun smallTextReturnsContentAndOthersNull() {
        val small = tmp.newFile("small.txt")
        small.writeText("hello preview")
        assertEquals("hello preview", FilePreviewRules.readPreviewOrNull(small))
        val mp4 = tmp.newFile("video.mp4")
        mp4.writeBytes(ByteArray(4))
        assertNull(FilePreviewRules.readPreviewOrNull(mp4))
    }
}
