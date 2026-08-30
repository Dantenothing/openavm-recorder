package com.dante.zeekrbridge.core

import io.github.dantenothing.avmtransfer.protocol.TransferProtocol
import io.github.dantenothing.avmtransfer.protocol.UploadCreateRequest
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ReliableUploadStoreTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("reliable-upload-test").toFile()
        ReliableUploadStore.initForTests(root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun restartResumeCommitAndLateCancelPreserveFinalFile() {
        val bytes = ByteArray(TransferProtocol.CHUNK_SIZE + 3) { (it % 251).toByte() }
        val request = UploadCreateRequest(
            clientTransferId = "task-1",
            fileName = "drive.mp4",
            sizeBytes = bytes.size.toLong(),
            sha256 = sha256(bytes),
            carId = "car-1",
            sidecarJson = "{\"sourceRole\":\"SURROUND\"}",
        )
        val created = ReliableUploadStore.create(request, "car-1")!!
        assertTrue(
            ReliableUploadStore.storeChunk(
                created.uploadId,
                "car-1",
                1,
                bytes.copyOfRange(TransferProtocol.CHUNK_SIZE, bytes.size),
            ),
        )
        ReliableUploadStore.initForTests(root)
        assertEquals(created.uploadId, ReliableUploadStore.create(request, "car-1")!!.uploadId)
        assertTrue(
            ReliableUploadStore.storeChunk(
                created.uploadId,
                "car-1",
                0,
                bytes.copyOfRange(0, TransferProtocol.CHUNK_SIZE),
            ),
        )
        val first = ReliableUploadStore.complete(
            created.uploadId,
            "car-1",
            request.sha256,
            request.fileName,
        ) as ReliableCommitResult.Success
        val second = ReliableUploadStore.complete(
            created.uploadId,
            "car-1",
            request.sha256,
            request.fileName,
        ) as ReliableCommitResult.Success
        assertEquals(first.response.fileName, second.response.fileName)
        assertTrue(File(root, "received/drive.json").isFile)
        assertFalse(ReliableUploadStore.cancel(created.uploadId, "car-1"))
        assertTrue(File(root, "received/${first.response.fileName}").isFile)
    }

    @Test
    fun repeatedCreateIsIdempotentAndCancelRemovesPartialUpload() {
        val bytes = byteArrayOf(1, 2, 3)
        val request = UploadCreateRequest(
            clientTransferId = "task-2",
            fileName = "short.mp4",
            sizeBytes = bytes.size.toLong(),
            sha256 = sha256(bytes),
            carId = "car-1",
        )
        val first = ReliableUploadStore.create(request, "car-1")!!
        assertEquals(first.uploadId, ReliableUploadStore.create(request, "car-1")!!.uploadId)
        assertTrue(ReliableUploadStore.storeChunk(first.uploadId, "car-1", 0, bytes))
        assertTrue(ReliableUploadStore.cancel(first.uploadId, "car-1"))
        assertTrue(ReliableUploadStore.status(first.uploadId, "car-1") == null)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
