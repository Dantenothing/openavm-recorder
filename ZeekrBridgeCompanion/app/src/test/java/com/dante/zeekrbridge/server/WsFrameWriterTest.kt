package com.dante.zeekrbridge.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread

class WsFrameWriterTest {

    @Test
    fun concurrentWritesNeverInterleaveFrames() {
        val target = ByteArrayOutputStream()
        val writer = WsFrameWriter(target)
        val frameA = ByteArray(64) { 'A'.code.toByte() }
        val frameB = ByteArray(64) { 'B'.code.toByte() }
        val threads = listOf(
            thread { repeat(200) { writer.write(frameA) } },
            thread { repeat(200) { writer.write(frameB) } },
        )
        threads.forEach { it.join() }

        val bytes = target.toByteArray()
        assertEquals(400 * 64, bytes.size)
        var block = 0
        while (block < bytes.size) {
            val slice = bytes.copyOfRange(block, block + 64)
            val first = slice[0]
            assertTrue(first == 'A'.code.toByte() || first == 'B'.code.toByte())
            assertTrue(slice.all { it == first })
            block += 64
        }
    }
}
