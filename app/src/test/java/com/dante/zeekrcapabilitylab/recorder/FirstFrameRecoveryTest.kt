package com.dante.zeekrcapabilitylab.recorder

import com.dante.zeekrcapabilitylab.service.recorder.FirstFrameRecovery
import org.junit.Assert.*
import org.junit.Test

class FirstFrameRecoveryTest {
    @Test fun aTimerDelayedBySleepCannotRetryAfterTheSourceDeadline() {
        val recovery = FirstFrameRecovery(1_000)
        assertFalse(recovery.request(16_000, 0, true))
        assertFalse(recovery.request(5_000, 0, true))
    }
    @Test fun oneRequestBeforeTheExistingTimeoutAndNoRepeatedRetries() {
        val recovery = FirstFrameRecovery(1_000)
        assertFalse(recovery.request(4_999, 0, true))
        assertTrue(recovery.request(5_000, 0, true))
        assertFalse(recovery.request(9_000, 0, true))
        assertFalse(recovery.request(90_000, 0, true))
    }
    @Test fun realFramePermanentlyDisarmsStartupRecovery() {
        val recovery = FirstFrameRecovery(1_000)
        assertFalse(recovery.request(2_000, 1, true))
        assertFalse(recovery.request(5_000, 0, true)) // a counter reset is not a fresh start
    }
    @Test fun revokedAuthorityCannotBeRevivedByALateTimer() {
        val recovery = FirstFrameRecovery(1_000)
        assertFalse(recovery.request(4_000, 0, false))
        assertFalse(recovery.request(5_000, 0, true))
        val cancelled = FirstFrameRecovery(1_000)
        cancelled.cancel()
        assertFalse(cancelled.request(5_000, 0, true))
    }
    @Test fun clockRollbackCannotRearmARequest() {
        val recovery = FirstFrameRecovery(1_000)
        assertFalse(recovery.request(900, 0, true))
        assertTrue(recovery.request(5_000, 0, true))
    }
}
