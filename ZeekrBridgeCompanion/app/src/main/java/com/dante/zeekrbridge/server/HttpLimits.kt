package com.dante.zeekrbridge.server

import java.io.InputStream

/** Bounded HTTP header/body limits for the bridge server. */
object HttpLimits {
    const val MAX_HEADER_BYTES = 16 * 1024
    const val MAX_BODY_BYTES = 4 * 1024 * 1024 + 256 * 1024

    fun bodyAllowed(length: Long?): Boolean =
        length == null || length in 0..MAX_BODY_BYTES
}

/** Reads exactly [length] bytes or returns null; never pads a truncated body. */
object HttpBodyReader {
    fun readExact(input: InputStream, length: Int): ByteArray? {
        if (length < 0) return null
        if (length == 0) return ByteArray(0)
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(bytes, read, length - read)
            if (n == -1) return null
            read += n
        }
        return bytes
    }
}
