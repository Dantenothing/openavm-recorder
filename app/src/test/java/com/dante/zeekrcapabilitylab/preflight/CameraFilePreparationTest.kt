package com.dante.zeekrcapabilitylab.preflight

import com.dante.zeekrcapabilitylab.preflight.continuous.CameraFilePreparation
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CameraFilePreparationTest {
    @Test fun slowUsbOpenDoesNotBlockSourcePollingOrPermitSecondOpen() {
        val started=CountDownLatch(1);val release=CountDownLatch(1);val p=CameraFilePreparation<Int>()
        try {
            p.start {started.countDown();release.await();42}
            assertTrue(started.await(1,TimeUnit.SECONDS))
            repeat(100){assertNull(p.poll())}
            assertThrows(IllegalStateException::class.java) {p.start {43}}
            assertFalse(p.finish(1)) // timeout is not cleanup confirmation
        } finally {release.countDown();assertTrue(p.finish(1_000))}
        assertEquals(42,p.poll());assertFalse(p.busy)
    }
    @Test fun failedPreparationRetainsOriginalStageFailure() {
        val p=CameraFilePreparation<Int>()
        p.start {error("USB_OPEN_FAILED")};assertTrue(p.finish(1_000))
        val error=assertThrows(IllegalStateException::class.java) {p.poll()}
        assertEquals("USB_OPEN_FAILED",error.message)
    }
}
