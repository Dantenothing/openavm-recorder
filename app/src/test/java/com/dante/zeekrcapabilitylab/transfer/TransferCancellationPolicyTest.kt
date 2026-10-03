package com.dante.zeekrcapabilitylab.transfer

import io.github.dantenothing.avmtransfer.protocol.TransferTaskState
import io.github.dantenothing.avmtransfer.protocol.UploadCreateResponse
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TransferCancellationPolicyTest {
    private fun task(state: TransferTaskState = TransferTaskState.UPLOADING, upload: String? = "remote") =
        TransferTask("task", "/recording.mp4", "recording.mp4", 3, state = state, uploadId = upload,
            chunkSize = 4, totalChunks = 1, includeDriveData = true)

    @Test fun cancelledAndTerminalTasksRejectEveryFutureUploadMutation() {
        val stopped = listOf(TransferTaskState.CANCEL_PENDING, TransferTaskState.CANCELLED, TransferTaskState.COMPLETED, TransferTaskState.FAILED)
        for (state in stopped) {
            for ((method, path) in listOf("POST" to "/api/uploads", "PUT" to "/api/uploads/remote/drive-data",
                "PUT" to "/api/uploads/remote/chunks/0", "POST" to "/api/uploads/remote/complete", "GET" to "/health")) {
                assertFalse(TransferCancellationPolicy.requestAllowed(task(state), method, path))
            }
        }
    }
    @Test fun cleanupMayOnlyReadOrDeleteTheKnownRemoteSession() {
        val pending = task(TransferTaskState.CANCEL_PENDING)
        assertTrue(TransferCancellationPolicy.requestAllowed(pending, "GET", "/api/uploads/remote"))
        assertTrue(TransferCancellationPolicy.requestAllowed(pending, "DELETE", "/api/uploads/remote"))
        assertFalse(TransferCancellationPolicy.requestAllowed(pending, "DELETE", "/api/uploads/other"))
        assertFalse(TransferCancellationPolicy.requestAllowed(pending.copy(uploadId = null), "DELETE", "/api/uploads/remote"))
    }
    @Test fun cancelBeforeRegistrationPreventsTheCallFromBeingRegistered() {
        val lock = Any()
        var current = task()
        var registered = false
        synchronized(lock) { current = current.copy(state = TransferTaskState.CANCEL_PENDING) }
        assertFalse(TransferCancellationPolicy.admit(lock, { current }, "PUT", "/api/uploads/remote/drive-data") { registered = true })
        assertFalse(registered)
        assertFalse(TransferCancellationPolicy.admit(lock, { null }, "POST", "/api/uploads") { registered = true })
    }
    @Test fun registrationBeforeCancelIsObservedUnderTheSameLock() {
        val lock = Any()
        var current = task()
        var registered = false
        var requestCancelled = false
        val registeredInsideLock = CountDownLatch(1)
        val releaseRegistration = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val request = executor.submit<Boolean> {
                TransferCancellationPolicy.admit(lock, { current }, "PUT", "/api/uploads/remote/drive-data") {
                    registered = true
                    registeredInsideLock.countDown()
                    check(releaseRegistration.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(registeredInsideLock.await(5, TimeUnit.SECONDS))
            val cancel = executor.submit {
                synchronized(lock) {
                    current = current.copy(state = TransferTaskState.CANCEL_PENDING)
                    requestCancelled = registered
                }
            }
            releaseRegistration.countDown()
            assertTrue(request.get(5, TimeUnit.SECONDS))
            cancel.get(5, TimeUnit.SECONDS)
            assertTrue(requestCancelled)
            assertFalse(TransferCancellationPolicy.admit(lock, { current }, "PUT", "/api/uploads/remote/drive-data") { fail("late registration") })
        } finally { releaseRegistration.countDown(); executor.shutdownNow() }
    }
    @Test fun staleProgressCannotReviveCancelOrTerminalState() {
        val stale = task().copy(reason = "uploading again")
        assertNull(TransferCancellationPolicy.workerUpdate(task(TransferTaskState.CANCEL_PENDING), stale))
        for (state in listOf(TransferTaskState.CANCELLED, TransferTaskState.COMPLETED, TransferTaskState.FAILED)) {
            assertNull(TransferCancellationPolicy.workerUpdate(task(state), stale))
        }
        val pending = task(TransferTaskState.CANCEL_PENDING)
        val updated = TransferCancellationPolicy.workerUpdate(pending, pending.copy(uploadId = "wrong", reason = "cleanup waiting"))!!
        assertEquals("remote", updated.uploadId)
        assertEquals(TransferTaskState.CANCEL_PENDING, updated.state)
    }
    @Test fun ordinaryUpdatesCannotReplaceFrozenChoiceOrLearnARemoteId() {
        val current = task().copy(includeDriveData = false)
        val stale = current.copy(includeDriveData = true, uploadId = "wrong", filePath = "/different.mp4",
            sidecarJson = "wrong", chunkSize = 999, uploadedChunks = 1)
        val updated = TransferCancellationPolicy.workerUpdate(current, stale)!!
        assertFalse(updated.includeDriveData)
        assertEquals(current.filePath, updated.filePath)
        assertEquals(current.sidecarJson, updated.sidecarJson)
        assertEquals("remote", updated.uploadId)
        assertEquals(4, updated.chunkSize)
        assertEquals(1, updated.uploadedChunks)
    }
    @Test fun lateCreatePreservesCleanupHandleWithoutResumingCancelledUpload() {
        val response = UploadCreateResponse("remote-new", 4, 1)
        for (state in listOf(TransferTaskState.CANCEL_PENDING, TransferTaskState.CANCELLED)) {
            val result = TransferCancellationPolicy.created(task(state, null), response)
            assertEquals("remote-new", result.uploadId)
            assertEquals(TransferTaskState.CANCEL_PENDING, result.state)
            assertFalse(TransferCancellationPolicy.requestAllowed(result, "PUT", "/api/uploads/remote-new/drive-data"))
            assertTrue(TransferCancellationPolicy.requestAllowed(result, "DELETE", "/api/uploads/remote-new"))
        }
    }
    @Test fun confirmedCompletionCanResolveCancelPendingButFailuresCannot() {
        assertTrue(TransferCancellationPolicy.canFinish(TransferTaskState.CANCEL_PENDING, TransferTaskState.COMPLETED))
        assertTrue(TransferCancellationPolicy.canFinish(TransferTaskState.CANCEL_PENDING, TransferTaskState.CANCELLED))
        assertFalse(TransferCancellationPolicy.canFinish(TransferTaskState.CANCEL_PENDING, TransferTaskState.FAILED))
        assertFalse(TransferCancellationPolicy.canFinish(TransferTaskState.CANCELLED, TransferTaskState.COMPLETED))
        assertFalse(TransferCancellationPolicy.canFinish(TransferTaskState.COMPLETED, TransferTaskState.FAILED))
    }
    @Test fun onlyExplicitRetryReopensAdmissionAndCompletedCreateDoesNotReplaceHandle() {
        val failed = task(TransferTaskState.FAILED)
        assertFalse(TransferCancellationPolicy.requestAllowed(failed, "PUT", "/api/uploads/remote/drive-data"))
        val explicitlyRetried = failed.copy(state = TransferTaskState.QUEUED)
        assertTrue(TransferCancellationPolicy.requestAllowed(explicitlyRetried, "PUT", "/api/uploads/remote/drive-data"))
        val completed = task(TransferTaskState.COMPLETED)
        assertEquals(completed, TransferCancellationPolicy.created(completed, UploadCreateResponse("other", 4, 1)))
    }
}
