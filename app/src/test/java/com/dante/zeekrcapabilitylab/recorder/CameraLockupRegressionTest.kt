package com.dante.zeekrcapabilitylab.recorder

import com.dante.zeekrcapabilitylab.sentry.CanaryCameraInterlock
import com.dante.zeekrcapabilitylab.service.recorder.TimeLapseTeardownPolicy
import com.dante.zeekrcapabilitylab.service.recorder.TimeLapseTeardownStage
import org.junit.Assert.*
import org.junit.Test

class CameraLockupRegressionTest {
    @Test fun waitingWithoutAnyProducerCallbackNeverConfirmsAProducerFence() {
        var stage = TimeLapseTeardownStage.DRAINING
        repeat(3) { stage = TimeLapseTeardownPolicy.onTimeout(stage) }
        assertNotEquals(TimeLapseTeardownStage.READY_TO_STOP_RECORDER, stage)
    }

    @Test fun previewAndRecorderCannotBothReserveTheSameDefaultCamera() {
        val preview = Any()
        val recorder = Any()
        try {
            assertTrue(CanaryCameraInterlock.beginNormalOpen(preview))
            assertFalse(CanaryCameraInterlock.beginNormalOpen(recorder))
        } finally {
            CanaryCameraInterlock.normalClosed(preview)
            CanaryCameraInterlock.normalClosed(recorder)
        }
    }
}
