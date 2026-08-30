package com.dante.zeekrbridge.server

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

object WsCodec {
    /** Envelope-size cap: a larger declared payload is a protocol error, never allocated. */
    const val MAX_WS_PAYLOAD = 256 * 1024
    const val MAX_CONTROL_PAYLOAD = 125

    data class Frame(val fin: Boolean, val opcode: Int, val payload: ByteArray)

    class ProtocolException(message: String) : IOException(message)

    fun encodeText(text: String): ByteArray {
        val payload = text.toByteArray(Charsets.UTF_8)
        val header = ByteArrayOutputStream()
        header.write(0x81)
        val len = payload.size
        when {
            len < 126 -> header.write(len)
            len < 65536 -> {
                header.write(126)
                header.write(len shr 8)
                header.write(len and 0xff)
            }
            else -> {
                header.write(127)
                for (i in 7 downTo 0) header.write((len ushr (8 * i)) and 0xff)
            }
        }
        return header.toByteArray() + payload
    }

    fun encodePong(payload: ByteArray): ByteArray {
        val header = ByteArrayOutputStream()
        header.write(0x8A)
        header.write(payload.size)
        return header.toByteArray() + payload
    }

    fun encodeClose(code: Int): ByteArray {
        val payload = byteArrayOf(((code shr 8) and 0xff).toByte(), (code and 0xff).toByte())
        val header = ByteArrayOutputStream()
        header.write(0x88)
        header.write(payload.size)
        return header.toByteArray() + payload
    }

    fun readFrame(input: InputStream): Frame? {
        val first = input.read()
        if (first == -1) return null
        val second = input.read()
        if (second == -1) return null
        val fin = (first and 0x80) != 0
        val opcode = first and 0x0f
        if (opcode !in intArrayOf(0x0, 0x1, 0x2, 0x8, 0x9, 0xA)) {
            throw ProtocolException("reserved opcode $opcode")
        }
        val masked = (second and 0x80) != 0
        if (!masked) {
            // RFC 6455: clients must mask every frame.
            throw ProtocolException("unmasked client frame")
        }
        var length: Long = (second and 0x7f).toLong()
        if (length == 126L) {
            val b1 = input.read()
            val b2 = input.read()
            if (b1 == -1 || b2 == -1) return null
            length = ((b1 shl 8) or b2).toLong()
        } else if (length == 127L) {
            var len = 0L
            repeat(8) {
                val b = input.read()
                if (b == -1) return null
                len = (len shl 8) or b.toLong()
            }
            length = len
        }
        if (length < 0 || length > MAX_WS_PAYLOAD) {
            throw ProtocolException("frame length $length exceeds $MAX_WS_PAYLOAD")
        }
        val isControl = opcode >= 0x8
        if (isControl && (length > MAX_CONTROL_PAYLOAD || !fin)) {
            throw ProtocolException("invalid control frame")
        }
        val mask = if (masked) {
            ByteArray(4).also { maskKey ->
                var i = 0
                while (i < 4) {
                    val b = input.read()
                    if (b == -1) return null
                    maskKey[i] = b.toByte()
                    i++
                }
            }
        } else {
            throw ProtocolException("unmasked client frame")
        }
        val payload = ByteArray(length.toInt())
        var read = 0
        while (read < length) {
            val n = input.read(payload, read, (length - read).toInt())
            if (n == -1) return null
            read += n
        }
        for (i in payload.indices) {
            payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        }
        return Frame(fin, opcode, payload)
    }
}
