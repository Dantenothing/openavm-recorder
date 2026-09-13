package com.dante.zeekrbridge.sound

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class PcmBlockReaderTest {
    @Test fun respectsNonzeroDecoderOffsetAndDoesNotIncludePrefixOrSuffix() {
        val buffer = ByteBuffer.wrap(byteArrayOf(99, 98, 1, 2, 3, 4, 97))
        buffer.position(2)
        buffer.limit(6)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), PcmBlockReader.read(buffer, 2, 4))
        assertEquals(2, buffer.position())
        assertEquals(6, buffer.limit())
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesAByteRangeBeyondTheCodecBuffer() {
        PcmBlockReader.read(ByteBuffer.allocate(4), 2, 3)
    }
}
