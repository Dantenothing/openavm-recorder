package com.dante.zeekrbridge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShaHexTest {

    @Test
    fun normalizesLowercase() {
        val hex = "a".repeat(64)
        assertEquals(hex, ShaHex.normalize(hex))
        assertEquals(hex, ShaHex.normalize("A".repeat(64)))
        assertEquals(hex, ShaHex.normalize("  ${hex.uppercase()}  "))
    }

    @Test
    fun rejectsInvalidFormats() {
        assertNull(ShaHex.normalize(null))
        assertNull(ShaHex.normalize(""))
        assertNull(ShaHex.normalize("abc"))
        assertNull(ShaHex.normalize("g".repeat(64)))
        assertNull(ShaHex.normalize("a".repeat(63)))
        assertNull(ShaHex.normalize("a".repeat(65)))
    }
}
