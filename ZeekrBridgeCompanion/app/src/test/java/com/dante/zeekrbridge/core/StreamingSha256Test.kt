package com.dante.zeekrbridge.core

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest
import kotlin.random.Random

class StreamingSha256Test {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun multiMegabyteFileHashesWithoutReadBytes() {
        val file = tmp.newFile("recording.bin")
        val bytes = ByteArray(5 * 1024 * 1024 + 123)
        Random(42).nextBytes(bytes)
        file.writeBytes(bytes)

        val expected = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

        assertEquals(expected, StreamingSha256.hash(file))
    }

    @Test
    fun emptyFileHasKnownDigest() {
        val file = tmp.newFile("empty.bin")
        val expected = MessageDigest.getInstance("SHA-256").digest(ByteArray(0))
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        assertEquals(expected, StreamingSha256.hash(file))
    }
}
