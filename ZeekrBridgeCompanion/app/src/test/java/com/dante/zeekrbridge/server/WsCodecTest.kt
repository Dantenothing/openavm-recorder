package com.dante.zeekrbridge.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class WsCodecTest {

    private fun maskedFrame(
        fin: Boolean,
        opcode: Int,
        payload: ByteArray,
        mask: ByteArray = byteArrayOf(1, 2, 3, 4),
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write((if (fin) 0x80 else 0) or opcode)
        val len = payload.size
        when {
            len < 126 -> out.write(0x80 or len)
            len < 65536 -> {
                out.write(0x80 or 126)
                out.write(len shr 8)
                out.write(len and 0xff)
            }
            else -> {
                out.write(0x80 or 127)
                val len64 = len.toLong()
                for (i in 7 downTo 0) out.write(((len64 ushr (8 * i)) and 0xff).toInt())
            }
        }
        out.write(mask)
        val masked = ByteArray(payload.size) { (payload[it].toInt() xor mask[it % 4].toInt()).toByte() }
        out.write(masked)
        return out.toByteArray()
    }

    private fun expectProtocolError(bytes: ByteArray) {
        try {
            WsCodec.readFrame(ByteArrayInputStream(bytes))
            fail("expected WsCodec.ProtocolException")
        } catch (e: WsCodec.ProtocolException) {
            assertTrue(e.message!!.isNotBlank())
        }
    }

    @Test
    fun maskedTextFrameDecodes() {
        val frame = WsCodec.readFrame(ByteArrayInputStream(maskedFrame(true, 0x1, "hello".toByteArray())))!!
        assertTrue(frame.fin)
        assertEquals(0x1, frame.opcode)
        assertEquals("hello", String(frame.payload, Charsets.UTF_8))
    }

    @Test
    fun eofReturnsNullAndTruncatedHeaderReturnsNull() {
        assertNull(WsCodec.readFrame(ByteArrayInputStream(ByteArray(0))))
        assertNull(WsCodec.readFrame(ByteArrayInputStream(byteArrayOf(0x81.toByte()))))
    }

    @Test
    fun unmaskedClientFrameIsRejected() {
        expectProtocolError(byteArrayOf(0x81.toByte(), 0x03.toByte(), 1, 2, 3))
    }

    @Test
    fun huge127LengthIsRejectedWithoutAllocation() {
        val out = ByteArrayOutputStream()
        out.write(0x81)
        out.write(0x80 or 127)
        val len = WsCodec.MAX_WS_PAYLOAD.toLong() + 1
        for (i in 7 downTo 0) out.write(((len ushr (8 * i)) and 0xff).toInt())
        out.write(byteArrayOf(1, 2, 3, 4))
        expectProtocolError(out.toByteArray())
    }

    @Test
    fun maxPayloadFrameIsAccepted() {
        val payload = ByteArray(WsCodec.MAX_WS_PAYLOAD) { (it % 251).toByte() }
        val frame = WsCodec.readFrame(ByteArrayInputStream(maskedFrame(true, 0x2, payload)))!!
        assertEquals(payload.size, frame.payload.size)
        assertTrue(frame.payload.contentEquals(payload))
    }

    @Test
    fun controlFrameMustBeFinalAndAtMost125Bytes() {
        expectProtocolError(maskedFrame(false, 0x9, ByteArray(5)))
        expectProtocolError(maskedFrame(true, 0x9, ByteArray(126)))
        val ok = WsCodec.readFrame(ByteArrayInputStream(maskedFrame(true, 0x9, ByteArray(5))))!!
        assertEquals(0x9, ok.opcode)
        assertEquals(5, ok.payload.size)
    }

    @Test
    fun reservedOpcodeIsRejected() {
        expectProtocolError(maskedFrame(true, 0x3, ByteArray(1)))
        expectProtocolError(maskedFrame(true, 0xB, ByteArray(1)))
    }

    @Test
    fun byteZeroInHeaderIsNotTreatedAsEof() {
        // Mask key contains a 0 byte; header bytes may also be 0.
        val frame = WsCodec.readFrame(ByteArrayInputStream(maskedFrame(true, 0x1, byteArrayOf(0, 65), byteArrayOf(0, 0, 0, 0))))!!
        assertEquals("\u0000A", String(frame.payload, Charsets.UTF_8))
    }
}
