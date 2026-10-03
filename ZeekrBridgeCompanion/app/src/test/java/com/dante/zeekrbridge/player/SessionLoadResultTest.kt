package com.dante.zeekrbridge.player

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SessionLoadResultTest {
    @Test fun completedInvalidSessionIsAnErrorResultNotAnAbsentLoadingResult() = runBlocking {
        val result = loadSessionResult<String, String>("invalid-session", 0) { null }
        assertTrue(result.matches("invalid-session", 0))
        assertNull(result.data)
    }

    @Test fun retryAndNewSelectionCannotDisplayACompletedOlderResult() = runBlocking {
        val before = loadSessionResult("before", 0) { "old timing" }
        assertFalse(before.matches("before", 1))
        assertFalse(before.matches("after", 0))
        val retried = loadSessionResult("before", 1) { "new timing" }
        assertTrue(retried.matches("before", 1))
        assertEquals("new timing", retried.data)
    }

    @Test fun ReadFailureCompletesButCancellationStillPropagates() = runBlocking {
        val failed = loadSessionResult<String, String>("file", 0) { error("unreadable") }
        assertNull(failed.data)
        val cancellation = CancellationException("selection changed")
        val thrown = runCatching { loadSessionResult<String, String>("file", 0) { throw cancellation } }.exceptionOrNull()
        assertSame(cancellation, thrown)
    }
}
