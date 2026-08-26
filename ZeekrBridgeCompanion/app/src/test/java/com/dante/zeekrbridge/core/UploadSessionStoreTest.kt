package com.dante.zeekrbridge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UploadSessionStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun request(size: Long = 10, name: String = "a.mp4") = UploadCreateRequest(
        fileName = name,
        mimeType = "video/mp4",
        sizeBytes = size,
        sha256 = "a".repeat(64),
        carId = "car-1",
    )

    @Test
    fun createPersistsMetadataAndReloads() {
        val root = tmp.newFolder("uploads")
        UploadSessionStore.initForTests(root)
        val session = UploadSessionStore.create(request(size = 10), chunkSize = 4)
        assertTrue(java.io.File(root, "${session.uploadId}/metadata.json").exists())
        assertEquals(3, session.totalChunks)
        assertEquals(4, session.chunkSize)
        assertEquals("a.mp4", session.request.fileName)
        assertEquals("car-1", session.request.carId)

        // Simulate a process restart: a fresh store instance over the same root.
        UploadSessionStore.initForTests(root)
        val reloaded = UploadSessionStore.get(session.uploadId)
        assertNotNull(reloaded)
        assertEquals(session, reloaded)
    }

    @Test
    fun corruptMetadataIsQuarantinedAndReported() {
        val root = tmp.newFolder("uploads")
        val store = UploadSessionStore
        store.initForTests(root)
        val session = store.create(request(size = 10), chunkSize = 4)
        java.io.File(root, "${session.uploadId}/metadata.json").writeText("{ not json ")

        store.initForTests(root)
        assertNull(store.get(session.uploadId))
        assertTrue(store.isCorrupt(session.uploadId))
        assertTrue(java.io.File(root.parentFile, "uploads-corrupt/${session.uploadId}").exists())
    }

    @Test
    fun chunkWriteIsPartialThenAtomicAndIndexedNumerically() {
        val root = tmp.newFolder("uploads")
        UploadSessionStore.initForTests(root)
        val session = UploadSessionStore.create(request(size = 9), chunkSize = 4)

        // Out of order on purpose: chunk-2 first, then chunk-10-sized names must not collide.
        assertTrue(UploadSessionStore.storeChunk(session, 2, byteArrayOf(9)).ok)
        assertTrue(UploadSessionStore.storeChunk(session, 0, byteArrayOf(1, 2, 3, 4)).ok)
        assertTrue(UploadSessionStore.storeChunk(session, 1, byteArrayOf(5, 6, 7, 8)).ok)

        val dir = java.io.File(root, session.uploadId)
        assertTrue(java.io.File(dir, "chunk-0").exists())
        assertTrue(java.io.File(dir, "chunk-2").exists())
        assertFalse(java.io.File(dir, "chunk-0.partial").exists())
        assertEquals(listOf(0, 1, 2), UploadSessionStore.receivedChunks(session))
    }

    @Test
    fun outOfRangeAndWrongSizeChunksAreRejected() {
        val root = tmp.newFolder("uploads")
        UploadSessionStore.initForTests(root)
        val session = UploadSessionStore.create(request(size = 8), chunkSize = 4)

        val badIndex = UploadSessionStore.storeChunk(session, 3, byteArrayOf(1, 2, 3, 4))
        assertFalse(badIndex.ok)
        assertEquals("chunk index out of range", badIndex.error)

        val badSize = UploadSessionStore.storeChunk(session, 0, byteArrayOf(1, 2, 3))
        assertFalse(badSize.ok)
        assertEquals("chunk size mismatch expected=4 actual=3", badSize.error)
    }

    @Test
    fun negativeSizeIsRejectedAtCreate() {
        val root = tmp.newFolder("uploads")
        UploadSessionStore.initForTests(root)
        try {
            UploadSessionStore.create(request(size = -1), chunkSize = 4)
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun traversalUploadIdsNeverResolveToDirectories() {
        val root = tmp.newFolder("uploads")
        UploadSessionStore.initForTests(root)
        assertNull(UploadSessionStore.get("../escape"))
        assertNull(UploadSessionStore.get("a/b"))
    }

    @Test
    fun createRejectsInvalidSha() {
        val root = tmp.newFolder("uploads")
        UploadSessionStore.initForTests(root)
        try {
            UploadSessionStore.create(request().copy(sha256 = "not-a-sha"), 4)
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun createNormalizesShaToLowercase() {
        val root = tmp.newFolder("uploads")
        UploadSessionStore.initForTests(root)
        val session = UploadSessionStore.create(request().copy(sha256 = "B".repeat(64)), 4)
        assertEquals("b".repeat(64), session.request.sha256)
    }

    @Test
    fun completedSessionPersistsResultAndRejectsChunks() {
        val root = tmp.newFolder("uploads")
        UploadSessionStore.initForTests(root)
        val session = UploadSessionStore.create(request(size = 9), 4)
        assertTrue(UploadSessionStore.storeChunk(session, 0, byteArrayOf(1, 2, 3, 4)).ok)
        assertTrue(UploadSessionStore.storeChunk(session, 1, byteArrayOf(5, 6, 7, 8)).ok)
        assertTrue(UploadSessionStore.storeChunk(session, 2, byteArrayOf(9)).ok)

        UploadSessionStore.markCompleted(session, "/received/car-1/a.mp4", "a".repeat(64))
        UploadSessionStore.initForTests(root)
        val reloaded = UploadSessionStore.get(session.uploadId)!!
        assertEquals("COMPLETED", reloaded.status)
        assertEquals("/received/car-1/a.mp4", reloaded.completedPath)
        assertEquals("a".repeat(64), reloaded.completedSha256)
        assertEquals(listOf(0, 1, 2), UploadSessionStore.receivedChunks(reloaded))

        val rejected = UploadSessionStore.storeChunk(reloaded, 0, byteArrayOf(1, 2, 3, 4))
        assertFalse(rejected.ok)
        assertEquals("upload already completed", rejected.error)

        assertTrue(java.io.File(root, "${session.uploadId}/chunk-0").exists().not())
        UploadSessionStore.delete(session.uploadId)
        assertNull(UploadSessionStore.get(session.uploadId))
    }

    @Test
    fun completedSessionTtlCleanup() {
        val root = tmp.newFolder("uploads")
        UploadSessionStore.initForTests(root)
        val completed = UploadSessionStore.create(request(size = 4), 4)
        assertTrue(UploadSessionStore.storeChunk(completed, 0, byteArrayOf(1, 2, 3, 4)).ok)
        UploadSessionStore.markCompleted(completed, "/received/car-1/a.mp4", "a".repeat(64), now = 0L)
        val uploading = UploadSessionStore.create(request(size = 4, name = "b.mp4"), 4)

        UploadSessionStore.cleanupExpiredCompleted(now = 1_000L, ttlMs = 500L)

        assertNull(UploadSessionStore.get(completed.uploadId))
        assertNotNull(UploadSessionStore.get(uploading.uploadId))
    }
}
