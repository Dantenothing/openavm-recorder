package com.dante.zeekrbridge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class UploadMergerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun session(
        uploadsRoot: File,
        bytes: ByteArray,
        chunkSize: Int = 4,
        carId: String = "car-1",
    ): UploadSession {
        val id = "session-1"
        File(uploadsRoot, id).mkdirs()
        return UploadSession(
            uploadId = id,
            request = UploadCreateRequest(
                fileName = "a.mp4",
                mimeType = "video/mp4",
                sizeBytes = bytes.size.toLong(),
                sha256 = sha(bytes),
                carId = carId,
            ),
            chunkSize = chunkSize,
            totalChunks = if (bytes.isEmpty()) 0 else (bytes.size - 1) / chunkSize + 1,
        )
    }

    private fun writeChunk(uploadsRoot: File, session: UploadSession, index: Int, data: ByteArray) {
        File(uploadsRoot, "${session.uploadId}/chunk-$index").writeBytes(data)
    }

    @Test
    fun mergesShuffledChunksInNumericOrder() {
        val uploadsRoot = tmp.newFolder("uploads")
        val receivedRoot = tmp.newFolder("received")
        val content = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)
        val s = session(uploadsRoot, content)
        writeChunk(uploadsRoot, s, 2, content.copyOfRange(8, 12))
        writeChunk(uploadsRoot, s, 0, content.copyOfRange(0, 4))
        writeChunk(uploadsRoot, s, 1, content.copyOfRange(4, 8))

        val outcome = UploadMerger.merge(s, uploadsRoot, receivedRoot, null)
        assertNull(outcome.error)
        val file = outcome.file!!
        assertEquals("a.mp4", file.name)
        assertArrayEquals(content, file.readBytes())
        assertFalse(File(file.parentFile, "a.mp4.partial").exists())
    }

    @Test
    fun missingChunkIsRejectedWithoutOutput() {
        val uploadsRoot = tmp.newFolder("uploads")
        val receivedRoot = tmp.newFolder("received")
        val content = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val s = session(uploadsRoot, content)
        writeChunk(uploadsRoot, s, 0, content.copyOfRange(0, 4))

        val outcome = UploadMerger.merge(s, uploadsRoot, receivedRoot, null)
        assertEquals(MergeOutcome.ERR_INCOMPLETE, outcome.error)
        assertNull(outcome.file)
        assertEquals(0, receivedRoot.walkTopDown().count { it.isFile })
    }

    @Test
    fun hashMismatchRemovesPartialAndReportsSha() {
        val uploadsRoot = tmp.newFolder("uploads")
        val receivedRoot = tmp.newFolder("received")
        val content = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val s = session(uploadsRoot, content)
        writeChunk(uploadsRoot, s, 0, byteArrayOf(9, 9, 9, 9))
        writeChunk(uploadsRoot, s, 1, content.copyOfRange(4, 8))

        val outcome = UploadMerger.merge(s, uploadsRoot, receivedRoot, null)
        assertEquals(MergeOutcome.ERR_HASH, outcome.error)
        assertNull(outcome.file)
        assertEquals(sha(byteArrayOf(9, 9, 9, 9, 5, 6, 7, 8)), outcome.sha256)
        assertEquals(0, receivedRoot.walkTopDown().count { it.isFile })
    }

    @Test
    fun declaredSizeMismatchIsRejected() {
        val uploadsRoot = tmp.newFolder("uploads")
        val receivedRoot = tmp.newFolder("received")
        val s = session(uploadsRoot, byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9))
        writeChunk(uploadsRoot, s, 0, byteArrayOf(1, 2, 3, 4))
        writeChunk(uploadsRoot, s, 1, byteArrayOf(5, 6, 7, 8))
        writeChunk(uploadsRoot, s, 2, byteArrayOf(9, 9))

        val outcome = UploadMerger.merge(s, uploadsRoot, receivedRoot, null)
        assertEquals(MergeOutcome.ERR_SIZE, outcome.error)
        assertNull(outcome.file)
        assertEquals(0, receivedRoot.walkTopDown().count { it.isFile })
    }

    @Test
    fun duplicateNamesGetNumericSuffix() {
        val uploadsRoot = tmp.newFolder("uploads")
        val receivedRoot = tmp.newFolder("received")
        val content = byteArrayOf(1, 2, 3, 4)
        val s = session(uploadsRoot, content)
        writeChunk(uploadsRoot, s, 0, content)
        File(receivedRoot, "car-1/a.mp4").apply { parentFile!!.mkdirs() }.writeBytes(ByteArray(1))

        val first = UploadMerger.merge(s, uploadsRoot, receivedRoot, null)
        val second = UploadMerger.merge(s, uploadsRoot, receivedRoot, null)
        assertEquals("a-1.mp4", first.file!!.name)
        assertEquals("a-2.mp4", second.file!!.name)
    }
}
