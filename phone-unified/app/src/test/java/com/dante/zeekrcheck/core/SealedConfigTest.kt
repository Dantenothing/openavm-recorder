package com.dante.zeekrcheck.core

import org.junit.Assert.*
import org.junit.Test
import javax.crypto.KeyGenerator

class SealedConfigTest {
    @Test fun sessionAndProtocolHaveDifferentAuthenticatedPurposes() {
        val key = key()
        val session = SealedConfig.seal("synthetic session".toByteArray(), key, SealedConfig.Purpose.SESSION)
        assertThrows(Exception::class.java) { SealedConfig.open(session, key) }
        assertEquals("synthetic session", SealedConfig.open(session, key, SealedConfig.Purpose.SESSION).toString(Charsets.UTF_8))
    }
    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    @Test fun roundTripUsesFreshIvAndDoesNotStorePlaintext() {
        val key = key()
        val plain = "synthetic protocol credential".toByteArray()
        val first = SealedConfig.seal(plain, key)
        val second = SealedConfig.seal(plain, key)
        assertArrayEquals(plain, SealedConfig.open(first, key))
        assertFalse(first.contentEquals(second))
        assertFalse(first.toString(Charsets.UTF_8).contains("synthetic"))
    }
    @Test fun tamperingWrongKeyAndUnknownFormatFailClosed() {
        val key = key()
        val sealed = SealedConfig.seal("private synthetic config".toByteArray(), key)
        for (index in listOf(0, 1, 13, sealed.lastIndex)) {
            val tampered = sealed.copyOf().apply { this[index] = (this[index].toInt() xor 1).toByte() }
            assertThrows(Exception::class.java) { SealedConfig.open(tampered, key) }
        }
        assertThrows(Exception::class.java) { SealedConfig.open(sealed, key()) }
        assertThrows(Exception::class.java) { SealedConfig.open(sealed.copyOf(20), key) }
        assertThrows(Exception::class.java) { SealedConfig.seal(ByteArray(65_537), key) }
    }
}
