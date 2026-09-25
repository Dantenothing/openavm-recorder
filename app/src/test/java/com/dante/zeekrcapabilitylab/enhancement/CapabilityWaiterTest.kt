package com.dante.zeekrcapabilitylab.enhancement

import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CapabilityWaiterTest {
    @Test fun timedOutDialogDoesNotCancelQueryOrRetainObserversForEveryRetry() = runTest {
        val query = CompletableFuture<String>()
        repeat(3) {
            var timedOut = false
            launch { try { awaitCapabilityResult(query) } catch (_: TimeoutCancellationException) { timedOut = true } }
            advanceUntilIdle()
            assertTrue(timedOut); assertFalse(query.isDone)
            assertEquals(0, query.numberOfDependents)
        }
        query.complete("finished")
        assertEquals("finished", awaitCapabilityResult(query))
    }
    @Test fun closingOneDialogDoesNotCancelTheSharedResultForAnother() = runTest {
        val query = CompletableFuture<String>()
        val first = launch { awaitCapabilityResult(query) }
        var second: String? = null
        launch { second = awaitCapabilityResult(query) }
        runCurrent(); first.cancel(); runCurrent()
        assertFalse(query.isCancelled)
        query.complete("ready"); advanceUntilIdle()
        assertEquals("ready", second)
        assertEquals(0, query.numberOfDependents)
    }
}
