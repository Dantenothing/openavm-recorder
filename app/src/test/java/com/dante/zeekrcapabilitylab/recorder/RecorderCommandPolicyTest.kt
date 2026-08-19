package com.dante.zeekrcapabilitylab.recorder

import com.dante.zeekrcapabilitylab.service.recorder.RecorderCommandPolicy
import com.dante.zeekrcapabilitylab.service.recorder.RecorderStatus
import com.dante.zeekrcapabilitylab.service.recorder.RecorderTimeoutPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecorderCommandPolicyTest {

    @Test
    fun startIsAllowedOnlyWhenIdleOrStopped() {
        assertTrue(RecorderCommandPolicy.canStart(RecorderStatus.IDLE, serviceRunning = false))
        assertTrue(RecorderCommandPolicy.canStart(RecorderStatus.STOPPED, serviceRunning = false))
        assertTrue(RecorderCommandPolicy.canStart(RecorderStatus.IDLE, serviceRunning = true))

        assertFalse(RecorderCommandPolicy.canStart(RecorderStatus.STARTING, serviceRunning = true))
        assertFalse(RecorderCommandPolicy.canStart(RecorderStatus.RECORDING, serviceRunning = true))
        assertFalse(RecorderCommandPolicy.canStart(RecorderStatus.FINALIZING, serviceRunning = true))
        assertFalse(RecorderCommandPolicy.canStart(RecorderStatus.CAMERA_UNAVAILABLE, serviceRunning = true))
        assertFalse(RecorderCommandPolicy.canStart(RecorderStatus.ERROR, serviceRunning = true))
    }

    @Test
    fun stopIsAllowedForEveryActiveServiceState() {
        for (status in listOf(
            RecorderStatus.STARTING,
            RecorderStatus.RECORDING,
            RecorderStatus.FINALIZING,
            RecorderStatus.CAMERA_UNAVAILABLE,
            RecorderStatus.ERROR,
        )) {
            assertTrue("stop should be allowed for $status", RecorderCommandPolicy.canStop(status, serviceRunning = true))
        }
        assertFalse(RecorderCommandPolicy.canStop(RecorderStatus.IDLE, serviceRunning = true))
        assertFalse(RecorderCommandPolicy.canStop(RecorderStatus.STOPPED, serviceRunning = false))
    }

    @Test
    fun retryIsOnlyForCameraUnavailable() {
        assertFalse(RecorderCommandPolicy.canRetry(RecorderStatus.CAMERA_UNAVAILABLE, serviceRunning = true))
        assertFalse(RecorderCommandPolicy.canRetry(RecorderStatus.ERROR, serviceRunning = true))
        assertFalse(RecorderCommandPolicy.canRetry(RecorderStatus.RECORDING, serviceRunning = true))
        assertFalse(RecorderCommandPolicy.canRetry(RecorderStatus.CAMERA_UNAVAILABLE, serviceRunning = false))
    }

    @Test
    fun activeStatusesCoverServiceLifecycle() {
        assertTrue(RecorderCommandPolicy.isActive(RecorderStatus.STARTING))
        assertTrue(RecorderCommandPolicy.isActive(RecorderStatus.RECORDING))
        assertTrue(RecorderCommandPolicy.isActive(RecorderStatus.FINALIZING))
        assertTrue(RecorderCommandPolicy.isActive(RecorderStatus.CAMERA_UNAVAILABLE))
        assertTrue(RecorderCommandPolicy.isActive(RecorderStatus.ERROR))
        assertFalse(RecorderCommandPolicy.isActive(RecorderStatus.IDLE))
        assertFalse(RecorderCommandPolicy.isActive(RecorderStatus.STOPPED))
    }

    @Test
    fun staleTimeoutTokenDoesNotOwnCurrentSegment() {
        assertTrue(
            RecorderTimeoutPolicy.ownsSegment(
                token = 3,
                currentGeneration = 3,
                recording = true,
                hasCurrentPartial = true,
            ),
        )
        assertFalse(
            RecorderTimeoutPolicy.ownsSegment(
                token = 2,
                currentGeneration = 3,
                recording = true,
                hasCurrentPartial = true,
            ),
        )
        assertFalse(
            RecorderTimeoutPolicy.ownsSegment(
                token = 3,
                currentGeneration = 3,
                recording = false,
                hasCurrentPartial = true,
            ),
        )
        assertFalse(
            RecorderTimeoutPolicy.ownsSegment(
                token = 3,
                currentGeneration = 3,
                recording = true,
                hasCurrentPartial = false,
            ),
        )
    }
}
