package com.dante.zeekrcapabilitylab.transfer

import io.github.dantenothing.avmtransfer.protocol.TransferTaskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferEnqueuePolicyTest {
    @Test
    fun connectedPhoneStartsQueuedTransferImmediately() {
        val decision = TransferEnqueuePolicy.decide(connected = true)

        assertEquals(TransferTaskState.QUEUED, decision.initialState)
        assertEquals(null, decision.reason)
        assertTrue(decision.startServiceImmediately)
    }

    @Test
    fun offlinePhoneCreatesVisibleCancellableWaitingTask() {
        val decision = TransferEnqueuePolicy.decide(connected = false)

        assertEquals(TransferTaskState.WAITING_RETRY, decision.initialState)
        assertEquals("Waiting for phone connection", decision.reason)
        assertFalse(decision.startServiceImmediately)
    }

    @Test
    fun queuedTransferWithoutRemoteUploadCancelsLocally() {
        assertEquals(TransferCancelAction.FINISH_LOCALLY, TransferCancelPolicy.action(uploadId = null))
    }

    @Test
    fun partialRemoteUploadIsCleanedUpWhenConnectionReturns() {
        assertEquals(
            TransferCancelAction.REMOVE_REMOTE_PARTIAL,
            TransferCancelPolicy.action(uploadId = "upload-123"),
        )
    }
}
