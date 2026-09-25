package com.dante.zeekrcapabilitylab.recorder

import com.dante.zeekrcapabilitylab.service.recorder.PlaybackPinRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class PlaybackLeaseTest {
    @Test fun closingPlayerDoesNotReleaseAnIndependentDownloadLease() {
        val file = File("build/test-playing-and-sharing.mp4")
        PlaybackPinRegistry.acquire(file)
        PlaybackPinRegistry.acquire(file)
        try {
            PlaybackPinRegistry.release(file)
            assertTrue(PlaybackPinRegistry.isPinned(file))
        } finally { PlaybackPinRegistry.release(file) }
        assertFalse(PlaybackPinRegistry.isPinned(file))
    }
}
