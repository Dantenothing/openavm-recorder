package com.dante.zeekrcapabilitylab.mirror

import org.junit.Assert.*
import org.junit.Test
import com.dante.zeekrcapabilitylab.service.recorder.*
import com.dante.zeekrcapabilitylab.probe.camera.CameraFormatProfile
import com.dante.zeekrcapabilitylab.probe.camera.ProfileSize

class PreviewOrderRegistryTest {
    @Test fun closeComparisonFreezesRecordingConfigurationAndStopsAfterItsFirstFault() {
        val config = RecorderConfig(SessionSourceSnapshot(RecordingSourceRole.CABIN, "1",
            CameraFormatProfile(ProfileSize(640, 480), 2_000_000), RecordingLayoutKind.SINGLE_V1), 60, 5L shl 30)
        val close = TerminalCloseTrial("first", TerminalCloseOrder.DEVICE_FIRST)
        val trial = PreviewOrderTrial("first", "1", PreviewInitializationOrder.CAMERA_FIRST, RecordingSourceRole.CABIN, close, config)
        val registry = PreviewOrderRegistry(); registry.arm(trial)
        assertTrue(registry.take("first")!!.admitsRecording(config))
        assertFalse(trial.admitsRecording(null)); assertFalse(trial.admitsRecording(config.copy(segmentSeconds = 30)))
        close.fail("CAMERA_CLOSE_UNCONFIRMED"); assertFalse(trial.admitsRecording(config))
        registry.finish("first")
        assertThrows(IllegalArgumentException::class.java) { registry.arm(trial.copy(cameraId = "2")) }
        assertThrows(IllegalArgumentException::class.java) { registry.arm(trial.copy(recordingConfig = null)) }
    }
    @Test fun onlyTheArmedIntentCanUseTheOrderOnce() {
        val registry = PreviewOrderRegistry()
        val trial = PreviewOrderTrial("first", "2", PreviewInitializationOrder.SURFACE_FIRST)
        registry.arm(trial)
        assertNull(registry.take("other"))
        assertEquals(trial, registry.take("first"))
        assertNull(registry.take("first"))
        assertThrows(IllegalStateException::class.java) { registry.arm(trial.copy(id = "second")) }
        registry.finish("other")
        assertThrows(IllegalStateException::class.java) { registry.arm(trial) }
        registry.finish("first")
        registry.arm(trial.copy(id = "second"))
        assertNotNull(registry.take("second"))
    }
    @Test fun cancellationRevokesAnIntentThatHasNotReachedTheService() {
        val registry = PreviewOrderRegistry()
        registry.arm(PreviewOrderTrial("old", "2", PreviewInitializationOrder.SURFACE_FIRST))
        registry.finish("old")
        registry.arm(PreviewOrderTrial("new", "2", PreviewInitializationOrder.CAMERA_FIRST))
        assertNull(registry.take("old"))
        assertEquals(PreviewInitializationOrder.CAMERA_FIRST, registry.take("new")?.order)
    }
}
