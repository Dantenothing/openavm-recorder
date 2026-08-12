package com.dante.zeekrcapabilitylab

import com.dante.zeekrcapabilitylab.util.Utils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TrackFingerprintTest {

    @Test
    fun normalizationIsCaseAndWhitespaceInsensitive() {
        val a = Utils.trackFingerprint("com.player", "  Hello   World ", "Artist", 180_000L, "id-1")
        val b = Utils.trackFingerprint("com.player", "hello world", "artist", 180_000L, "id-1")
        assertEquals(a, b)
    }

    @Test
    fun differentMediaIdChangesFingerprint() {
        val a = Utils.trackFingerprint("com.player", "Song", "Artist", 180_000L, "id-1")
        val b = Utils.trackFingerprint("com.player", "Song", "Artist", 180_000L, "id-2")
        assertNotEquals(a, b)
    }

    @Test
    fun durationBucketGroupsSimilarDurations() {
        val a = Utils.trackFingerprint("pkg", "Song", "Artist", 185_000L, "id")
        val b = Utils.trackFingerprint("pkg", "Song", "Artist", 279_000L, "id")
        assertEquals(a, b)
    }
}
