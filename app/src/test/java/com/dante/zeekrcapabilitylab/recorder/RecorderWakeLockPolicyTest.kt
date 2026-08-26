package com.dante.zeekrcapabilitylab.recorder

import com.dante.zeekrcapabilitylab.service.recorder.RecorderStatus
import com.dante.zeekrcapabilitylab.service.recorder.RecorderWakeLockPolicy
import com.dante.zeekrcapabilitylab.service.recorder.WakeLockAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecorderWakeLockPolicyTest {

    @Test
    fun heldOnlyWhileActivelyRecording() {
        for (status in listOf(
            RecorderStatus.STARTING,
            RecorderStatus.RECORDING,
            RecorderStatus.FINALIZING,
            RecorderStatus.RESUMING,
        )) {
            assertTrue("should hold for $status", RecorderWakeLockPolicy.shouldHold(status))
        }
        for (status in listOf(
            RecorderStatus.IDLE,
            RecorderStatus.STOPPED,
            RecorderStatus.WAITING_CAMERA,
            RecorderStatus.CAMERA_UNAVAILABLE,
            RecorderStatus.ERROR,
        )) {
            assertFalse("should release for $status", RecorderWakeLockPolicy.shouldHold(status))
        }
    }

    @Test
    fun repeatedStateUpdatesDoNotReacquire() {
        assertEquals(WakeLockAction.ACQUIRE, RecorderWakeLockPolicy.decide(held = false, status = RecorderStatus.RECORDING))
        assertEquals(WakeLockAction.NONE, RecorderWakeLockPolicy.decide(held = true, status = RecorderStatus.RECORDING))
        assertEquals(WakeLockAction.NONE, RecorderWakeLockPolicy.decide(held = true, status = RecorderStatus.STARTING))
        assertEquals(WakeLockAction.RELEASE, RecorderWakeLockPolicy.decide(held = true, status = RecorderStatus.STOPPED))
        assertEquals(WakeLockAction.RELEASE, RecorderWakeLockPolicy.decide(held = true, status = RecorderStatus.CAMERA_UNAVAILABLE))
        assertEquals(WakeLockAction.RELEASE, RecorderWakeLockPolicy.decide(held = true, status = RecorderStatus.ERROR))
        assertEquals(WakeLockAction.NONE, RecorderWakeLockPolicy.decide(held = false, status = RecorderStatus.IDLE))
    }

    @Test
    fun renewalHappensBeforePlatformTimeout() {
        val timeout = RecorderWakeLockPolicy.TIMEOUT_MS
        val margin = RecorderWakeLockPolicy.RENEW_MARGIN_MS
        assertFalse(RecorderWakeLockPolicy.shouldRenew(0L))
        assertFalse(RecorderWakeLockPolicy.shouldRenew(timeout - margin - 1))
        assertTrue(RecorderWakeLockPolicy.shouldRenew(timeout - margin))
        assertTrue(RecorderWakeLockPolicy.shouldRenew(timeout))
    }
}
