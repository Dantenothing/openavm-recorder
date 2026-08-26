package com.dante.zeekrbridge.server

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class HttpLimitsTest {

    @Test
    fun bodyAllowedWithinBounds() {
        assertTrue(HttpLimits.bodyAllowed(null))
        assertTrue(HttpLimits.bodyAllowed(0))
        assertTrue(HttpLimits.bodyAllowed(HttpLimits.MAX_BODY_BYTES.toLong()))
        assertFalse(HttpLimits.bodyAllowed(HttpLimits.MAX_BODY_BYTES.toLong() + 1))
        assertFalse(HttpLimits.bodyAllowed(-1))
    }
}

class HttpBodyReaderTest {

    @Test
    fun readsExactBytes() {
        val payload = "hello".toByteArray()
        val result = HttpBodyReader.readExact(ByteArrayInputStream(payload), payload.size)
        assertArrayEquals(payload, result)
    }

    @Test
    fun truncatedBodyReturnsNull() {
        val payload = "hello".toByteArray()
        assertNull(HttpBodyReader.readExact(ByteArrayInputStream(payload), payload.size + 5))
    }

    @Test
    fun emptyAndNegativeLengths() {
        assertArrayEquals(ByteArray(0), HttpBodyReader.readExact(ByteArrayInputStream(ByteArray(0)), 0))
        assertNull(HttpBodyReader.readExact(ByteArrayInputStream(ByteArray(0)), -1))
    }
}
