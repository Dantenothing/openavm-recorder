package com.dante.zeekrbridge.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ExportOutputOwnershipTest {
    @Test fun successfulOutputTransfersOwnershipWithoutCleanup() = runBlocking {
        var cleaned = false
        val result = withOwnedExportOutput(operation = { own -> own("output"); "output" }, cleanup = { cleaned = true })
        assertEquals("output", result)
        assertFalse(cleaned)
    }

    @Test fun failedWriteRemovesTheAllocatedOutputAndPreservesTheFailure() = runBlocking {
        var cleaned: String? = null
        val failure = IllegalStateException("write failed")
        val thrown = runCatching {
            withOwnedExportOutput<String>(operation = { own -> own("partial"); throw failure }, cleanup = { cleaned = it })
        }.exceptionOrNull()
        assertSame(failure, thrown)
        assertEquals("partial", cleaned)
    }

    @Test fun cancellationAtIoResultHandoffRollsBackEvenAfterTheWriteCompleted() = runBlocking {
        var received: String? = null
        var cleaned: String? = null
        var writeCompleted = false
        val worker = launch {
            val caller = currentCoroutineContext().job
            received = withOwnedExportOutput(operation = { own ->
                withContext(Dispatchers.Default) {
                    own("published")
                    writeCompleted = true
                    caller.cancel(CancellationException("before IO result reaches main"))
                    "published"
                }
            }, cleanup = {
                assertTrue(currentCoroutineContext().isActive)
                cleaned = it
            })
        }
        worker.join()
        assertTrue(writeCompleted)
        assertTrue(worker.isCancelled)
        assertNull(received)
        assertEquals("published", cleaned)
    }
}
