package com.dante.zeekrcapabilitylab.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackTrackTextTest {
    @Test
    fun bitrateAndFrameRateAreReadableForRealCarDiagnostics() {
        assertEquals("14.0 Mbps", PlaybackTrackText.bitrate(14_000_000L))
        assertEquals("30 fps", PlaybackTrackText.frameRate(30f))
        assertEquals("29.97 fps", PlaybackTrackText.frameRate(29.97f))
    }
}
