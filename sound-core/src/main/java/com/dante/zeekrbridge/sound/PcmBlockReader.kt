package com.dante.zeekrbridge.sound

import java.nio.ByteBuffer

/** Copy exactly the decoder's valid byte range, without modifying its shared buffer. */
internal object PcmBlockReader {
    fun read(buffer: ByteBuffer, offset: Int, size: Int): ByteArray {
        require(offset >= 0 && size >= 0 && offset <= buffer.capacity() - size)
        return ByteArray(size).also { bytes ->
            buffer.duplicate().apply { clear(); position(offset); limit(offset + size) }.get(bytes)
        }
    }
}
