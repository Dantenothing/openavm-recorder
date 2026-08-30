package com.dante.zeekrbridge.sentry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbSentryPolicyTest {
    @Test
    fun acceptsVideoMimeOrKnownExtensions() {
        assertTrue(UsbSentryPolicy.isVideo("clip.bin", "video/mp4"))
        assertTrue(UsbSentryPolicy.isVideo("SENTRY.MP4", null))
        assertTrue(UsbSentryPolicy.isVideo("clip.mov", "application/octet-stream"))
        assertFalse(UsbSentryPolicy.isVideo("sound.wav", "audio/wav"))
    }

    @Test
    fun recognizesVerticalAndHorizontalFourLaneComposites() {
        assertTrue(UsbSentryPolicy.isFourLaneComposite(1280, 5140))
        assertTrue(UsbSentryPolicy.isFourLaneComposite(5140, 1280))
        assertFalse(UsbSentryPolicy.isFourLaneComposite(3840, 2160))
        assertFalse(UsbSentryPolicy.isFourLaneComposite(null, 5140))
    }

    @Test
    fun generatedCacheNamesCannotEscapeManagedDirectory() {
        assertEquals("evil_name.mp4", UsbSentryPolicy.safeFileName("../../evil name.mp4"))
    }
}
