package com.dante.zeekrbridge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class UploadCompleterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun createCompletedUpload(
        uploadsRoot: File,
        content: ByteArray,
        chunkSize: Int = 4,
    ): UploadSession {
        val store = UploadSessionStore
        store.initForTests(uploadsRoot)
        val session = store.create(
            UploadCreateRequest(
                fileName = "a.mp4",
                mimeType = "video/mp4",
                sizeBytes = content.size.toLong(),
                sha256 = sha(content),
                carId = "car-1",
            ),
            chunkSize,
        )
        val total = session.totalChunks
        for (index in 0 until total) {
            val start = index * chunkSize
            val end = minOf(start + chunkSize, content.size)
            val ok = store.storeChunk(session, index, content.copyOfRange(start, end))
            check(ok.ok) { "chunk $index failed: ${ok.error}" }
        }
        return session
    }

    @Test
    fun wrongCompleteHashCreatesNoFile() {
        val uploadsRoot = tmp.newFolder("uploads")
        val receivedRoot = tmp.newFolder("received")
        val session = createCompletedUpload(uploadsRoot, ByteArray(8) { it.toByte() })

        val outcome = UploadCompleter.complete(session, "f".repeat(64), "a.mp4", uploadsRoot, receivedRoot)

        assertEquals(MergeOutcome.ERR_SHA, outcome.error)
        assertNull(outcome.file)
        assertEquals(0, receivedRoot.walkTopDown().count { it.isFile })
    }

    @Test
    fun replayAfterResponseLossDoesNotDuplicateFile() {
        val uploadsRoot = tmp.newFolder("uploads")
        val receivedRoot = tmp.newFolder("received")
        val content = ByteArray(8) { it.toByte() }
        val session = createCompletedUpload(uploadsRoot, content)
        val goodSha = sha(content)

        val first = UploadCompleter.complete(session, goodSha, "a.mp4", uploadsRoot, receivedRoot)
        assertEquals("a.mp4", first.file!!.name)

        // Simulate the client re-issuing status/complete after a lost response.
        val reloaded = UploadSessionStore.get(session.uploadId)!!
        assertEquals("COMPLETED", reloaded.status)
        assertEquals(first.file!!.absolutePath, reloaded.completedPath)
        assertEquals(goodSha, reloaded.completedSha256)

        val replay = UploadCompleter.complete(reloaded, goodSha, "a.mp4", uploadsRoot, receivedRoot)
        assertEquals(first.file!!.name, replay.file!!.name)
        assertEquals(first.file!!.absolutePath, replay.file!!.absolutePath)
        assertEquals(1, receivedRoot.walkTopDown().count { it.isFile })
    }

    @Test
    fun wrongHashOnReplayIsRejectedWithoutNewFile() {
        val uploadsRoot = tmp.newFolder("uploads")
        val receivedRoot = tmp.newFolder("received")
        val content = ByteArray(8) { it.toByte() }
        val session = createCompletedUpload(uploadsRoot, content)
        UploadCompleter.complete(session, sha(content), "a.mp4", uploadsRoot, receivedRoot)

        val reloaded = UploadSessionStore.get(session.uploadId)!!
        val outcome = UploadCompleter.complete(reloaded, "f".repeat(64), "a.mp4", uploadsRoot, receivedRoot)

        assertEquals(MergeOutcome.ERR_SHA, outcome.error)
        assertEquals(1, receivedRoot.walkTopDown().count { it.isFile })
    }

    @Test
    fun concurrentCompletesProduceExactlyOneReceivedFile() {
        val uploadsRoot = tmp.newFolder("uploads-concurrent")
        val receivedRoot = tmp.newFolder("received-concurrent")
        val content = ByteArray(16) { it.toByte() }
        val session = createCompletedUpload(uploadsRoot, content)
        val goodSha = sha(content)
        val results = java.util.concurrent.ConcurrentLinkedQueue<MergeOutcome>()
        val threads = (1..2).map {
            Thread {
                results.add(
                    UploadCompleter.completeForUpload(
                        session.uploadId,
                        goodSha,
                        "a.mp4",
                        { UploadSessionStore.get(it) },
                        uploadsRoot,
                        receivedRoot,
                    ),
                )
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals(1, receivedRoot.walkTopDown().count { it.isFile })
        assertEquals(2, results.size)
        assertTrue(results.all { it.file != null && it.error == null })
        assertEquals(results.first().file!!.absolutePath, results.last().file!!.absolutePath)
    }
}
