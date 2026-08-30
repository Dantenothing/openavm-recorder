package com.dante.zeekrbridge.core

import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

object BtProtocol {
    val SERVICE_UUID: UUID = UUID.fromString("7a2f4b3e-8c1d-4e5f-9a0b-1c2d3e4f5a6b")
    const val TYPE_JSON = 1
    const val TYPE_BINARY = 2
    const val MAX_FRAME_SIZE = 8 * 1024 * 1024

    fun writeFrame(out: OutputStream, type: Int, payload: ByteArray) {
        out.write(type)
        out.write((payload.size shr 24) and 0xff)
        out.write((payload.size shr 16) and 0xff)
        out.write((payload.size shr 8) and 0xff)
        out.write(payload.size and 0xff)
        out.write(payload)
        out.flush()
    }

    fun readFrame(input: InputStream): Pair<Int, ByteArray>? {
        val type = input.read()
        if (type == -1) return null
        if (type != TYPE_JSON && type != TYPE_BINARY) throw IOException("unknown frame type $type")
        val len = readInt(input)
        if (len < 0 || len > MAX_FRAME_SIZE) throw IOException("invalid frame length $len")
        val payload = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = input.read(payload, read, len - read)
            if (n == -1) throw IOException("truncated frame")
            read += n
        }
        return type to payload
    }

    private fun readInt(input: InputStream): Int {
        var value = 0
        repeat(4) {
            val b = input.read()
            if (b == -1) throw IOException("truncated frame header")
            value = (value shl 8) or b
        }
        return value
    }
}
