package io.github.dantenothing.openavmreceiver

import io.github.dantenothing.avmtransfer.protocol.TransferProtocol
import io.github.dantenothing.avmtransfer.protocol.UploadCreateRequest
import java.io.File
import java.security.MessageDigest
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PhoneUploadStoreTest {
    private lateinit var root: File

    @Before fun setUp() {
        root = Files.createTempDirectory("phone-upload-test").toFile()
        PhoneUploadStore.initForTests(root)
    }

    @After fun tearDown() { root.deleteRecursively() }

    @Test fun chunksCommitOnceAndLateCancelCannotDeleteFinal() {
        val bytes = ByteArray(TransferProtocol.CHUNK_SIZE + 3) { (it % 251).toByte() }
        val request = UploadCreateRequest(
            clientTransferId = "task-1",
            fileName = "drive.mp4",
            sizeBytes = bytes.size.toLong(),
            sha256 = sha256(bytes),
            carId = "car-1",
            sidecarJson = "{\"sourceRole\":\"SURROUND\"}",
        )
        val created = PhoneUploadStore.create(request, "car-1")!!
        assertEquals(2, created.totalChunks)
        assertTrue(PhoneUploadStore.storeChunk(created.uploadId, "car-1", 1, bytes.copyOfRange(TransferProtocol.CHUNK_SIZE, bytes.size)))
        // Reinitialize from disk to model a receiver process restart.
        PhoneUploadStore.initForTests(root)
        assertEquals(created.uploadId, PhoneUploadStore.create(request, "car-1")!!.uploadId)
        assertTrue(PhoneUploadStore.storeChunk(created.uploadId, "car-1", 0, bytes.copyOfRange(0, TransferProtocol.CHUNK_SIZE)))
        assertEquals(listOf(0, 1), PhoneUploadStore.status(created.uploadId, "car-1")!!.receivedChunks)

        val first = PhoneUploadStore.complete(created.uploadId, "car-1", request.sha256, request.fileName) as CommitResult.Success
        val second = PhoneUploadStore.complete(created.uploadId, "car-1", request.sha256, request.fileName) as CommitResult.Success
        assertEquals(first.response.fileName, second.response.fileName)
        assertEquals(1, PhoneUploadStore.received.value.size)
        assertTrue(File(root, "received/drive.json").isFile)
        assertFalse(PhoneUploadStore.cancel(created.uploadId, "car-1"))
        assertTrue(File(root, "received/${first.response.fileName}").isFile)
    }

    @Test fun repeatedCreateReusesSameUploadAndCancelRemovesPartials() {
        val bytes = byteArrayOf(1, 2, 3)
        val request = UploadCreateRequest("task-2", "short.mp4", sizeBytes = 3, sha256 = sha256(bytes), carId = "car-1")
        val first = PhoneUploadStore.create(request, "car-1")!!
        val second = PhoneUploadStore.create(request, "car-1")!!
        assertEquals(first.uploadId, second.uploadId)
        assertTrue(PhoneUploadStore.storeChunk(first.uploadId, "car-1", 0, bytes))
        assertTrue(PhoneUploadStore.cancel(first.uploadId, "car-1"))
        assertTrue(PhoneUploadStore.status(first.uploadId, "car-1") == null)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
