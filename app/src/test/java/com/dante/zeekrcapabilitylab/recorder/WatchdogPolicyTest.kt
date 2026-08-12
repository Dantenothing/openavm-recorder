package com.dante.zeekrcapabilitylab.recorder

import com.dante.zeekrcapabilitylab.service.recorder.WatchdogPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchdogPolicyTest {

    @Test
    fun timeoutsStayInsideTenToFifteenSeconds() {
        assertTrue(WatchdogPolicy.OPEN_TIMEOUT_MS in 10_000L..15_000L)
        assertTrue(WatchdogPolicy.SETUP_TIMEOUT_MS in 10_000L..15_000L)
    }

    @Test
    fun watchdogFiresOnlyAfterTimeoutElapsed() {
        assertFalse(WatchdogPolicy.shouldFire(0L, WatchdogPolicy.OPEN_TIMEOUT_MS))
        assertFalse(WatchdogPolicy.shouldFire(WatchdogPolicy.OPEN_TIMEOUT_MS - 1, WatchdogPolicy.OPEN_TIMEOUT_MS))
        assertTrue(WatchdogPolicy.shouldFire(WatchdogPolicy.OPEN_TIMEOUT_MS, WatchdogPolicy.OPEN_TIMEOUT_MS))
        assertTrue(WatchdogPolicy.shouldFire(WatchdogPolicy.OPEN_TIMEOUT_MS + 1, WatchdogPolicy.OPEN_TIMEOUT_MS))
    }

    @Test
    fun staleWatchdogTokenMustNotFireAgainstNewerState() {
        assertTrue(WatchdogPolicy.isCurrent(token = 5, currentToken = 5))
        assertFalse(WatchdogPolicy.isCurrent(token = 4, currentToken = 5))
        assertFalse(WatchdogPolicy.isCurrent(token = 6, currentToken = 5))
    }

    @Test
    fun setupAndOpenShareTokenOwnershipSemantics() {
        // A stale open watchdog must not tear down a newer camera/session.
        assertFalse(WatchdogPolicy.isCurrent(token = 1, currentToken = 2))
        // A current token may fire.
        assertTrue(WatchdogPolicy.isCurrent(token = 2, currentToken = 2))
    }
}
