package com.dante.zeekrcapabilitylab.preflight

import com.dante.zeekrcapabilitylab.preflight.continuous.CameraCaptureInterval
import com.dante.zeekrcapabilitylab.preflight.continuous.CameraCaptureSample
import org.junit.Assert.*
import org.junit.Test

class CameraCaptureIntervalTest {
    private val samples=listOf(CameraCaptureSample(10,100),CameraCaptureSample(12,300),CameraCaptureSample(13,400))
    @Test fun delayedLiveFailureCannotBeReclassifiedAsNormalShutdown() {
        val result=CameraCaptureInterval.inspect(samples,listOf(100,300),listOf(11,14),listOf(12,15))
        assertTrue(result.confirmed)
        assertEquals(1,result.failures)
        assertEquals(1,result.lostBuffers)
        assertEquals(10L,result.firstFrame)
        assertEquals(12L,result.lastFrame)
    }
    @Test fun missingBoundsAndUnknownFailureIdsRemainInconclusive() {
        val result=CameraCaptureInterval.inspect(samples,listOf(50,100,300),listOf(-1),emptyList())
        assertFalse(result.confirmed)
        assertNull(result.failures)
        assertNull(result.lostBuffers)
        assertEquals(1,result.acquiredWithoutResult)
        assertEquals(1,result.unattributableFailures)
    }
    @Test fun skippedCompletedFramesAreSeparateFromShutdownAbortCallbacks() {
        val result=CameraCaptureInterval.inspect(samples,listOf(100,400),listOf(14),listOf(15))
        assertTrue(result.confirmed)
        assertEquals(0,result.failures)
        assertEquals(0,result.lostBuffers)
        assertEquals(1,result.sensorFramesNotAcquired)
        assertEquals(0,result.acquiredWithoutResult)
    }
}
