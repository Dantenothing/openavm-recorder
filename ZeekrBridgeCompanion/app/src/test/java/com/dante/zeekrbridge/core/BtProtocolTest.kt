package com.dante.zeekrbridge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class BtProtocolTest {

    @Test
    fun frameRoundTrip() {
        val out = ByteArrayOutputStream()
        val payload = "hello".toByteArray()
        BtProtocol.writeFrame(out, BtProtocol.TYPE_JSON, payload)
        val (type, body) = BtProtocol.readFrame(ByteArrayInputStream(out.toByteArray()))!!
        assertEquals(BtProtocol.TYPE_JSON, type)
        assertEquals("hello", String(body, Charsets.UTF_8))
    }

    @Test
    fun negativeLengthIsRejected() {
        val stream = ByteArrayInputStream(byteArrayOf(BtProtocol.TYPE_JSON.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte()))
        try {
            BtProtocol.readFrame(stream)
            throw AssertionError("expected IOException")
        } catch (expected: IOException) {
            // expected
        }
    }

    @Test
    fun oversizeLengthIsRejected() {
        val bytes = ByteArray(5)
        bytes[0] = BtProtocol.TYPE_JSON.toByte()
        bytes[1] = 0x20
        bytes[2] = 0
        bytes[3] = 0
        bytes[4] = 0
        try {
            BtProtocol.readFrame(ByteArrayInputStream(bytes))
            throw AssertionError("expected IOException")
        } catch (expected: IOException) {
            // expected
        }
    }

    @Test
    fun cleanEofReturnsNull() {
        assertNull(BtProtocol.readFrame(ByteArrayInputStream(ByteArray(0))))
    }
}
