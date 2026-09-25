package com.dante.zeekrbridge.core

import io.github.dantenothing.avmtransfer.protocol.UploadCreateRequest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class UploadRevocationTest {
    @get:Rule val temp = TemporaryFolder()
    private val bytes = "non-production-file".toByteArray()
    private val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private val request get() = UploadCreateRequest("task", "fixture.mp4", "video/mp4", bytes.size.toLong(), sha, "car")
    private fun setup(): String { ReliableUploadStore.initForTests(temp.root); return ReliableUploadStore.create(request, "car")!!.uploadId }

    @Test fun revokedGrantCannotCreateStoreChunkOrCancel() {
        ReliableUploadStore.initForTests(temp.root)
        assertNull(ReliableUploadStore.create(request, "car") { false })
        val id = ReliableUploadStore.create(request, "car")!!.uploadId
        assertFalse(ReliableUploadStore.storeChunk(id, "car", 0, bytes) { false })
        assertTrue(ReliableUploadStore.status(id, "car")!!.receivedChunks.isEmpty())
        assertFalse(ReliableUploadStore.cancel(id, "car") { false })
        assertNotNull(ReliableUploadStore.status(id, "car"))
    }
    @Test fun revocationDuringHashCannotCommitButNewGrantCanResume() {
        val id = setup()
        assertTrue(ReliableUploadStore.storeChunk(id, "car", 0, bytes))
        var boundaries = 0
        val rejected = ReliableUploadStore.complete(id, "car", sha, request.fileName) { action ->
            if (++boundaries == 1) { action(); true } else false
        }
        assertEquals(401, (rejected as ReliableCommitResult.Rejected).code)
        assertFalse(File(temp.root, "received/fixture.mp4").exists())
        assertEquals(listOf(0), ReliableUploadStore.status(id, "car")!!.receivedChunks)
        assertTrue(ReliableUploadStore.complete(id, "car", sha, request.fileName) is ReliableCommitResult.Success)
        assertArrayEquals(bytes, File(temp.root, "received/fixture.mp4").readBytes())
    }
}
