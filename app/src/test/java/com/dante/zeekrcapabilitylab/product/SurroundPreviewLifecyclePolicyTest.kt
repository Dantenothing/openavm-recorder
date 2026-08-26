package com.dante.zeekrcapabilitylab.product

import com.dante.zeekrcapabilitylab.service.recorder.RecordingSourceRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SurroundPreviewLifecyclePolicyTest {
    @Test
    fun recordingSurroundInvalidatesSurfaceWithoutStoppingEncoder() {
        val decision = SurroundPreviewLifecyclePolicy.onAppBackground(
            RecordingSourceRole.SURROUND,
            recordingActive = true,
            currentGeneration = 4,
        )

        assertTrue(decision.invalidateSurface)
        assertTrue(decision.disableRecorderPreview)
        assertFalse(decision.stopIdlePreview)
        assertEquals(5, decision.nextGeneration)
    }

    @Test
    fun idleSurroundClosesIdlePreviewAndAdvancesGeneration() {
        val decision = SurroundPreviewLifecyclePolicy.onAppBackground(
            RecordingSourceRole.SURROUND,
            recordingActive = false,
            currentGeneration = 9,
        )

        assertTrue(decision.invalidateSurface)
        assertFalse(decision.disableRecorderPreview)
        assertTrue(decision.stopIdlePreview)
        assertEquals(10, decision.nextGeneration)
    }

    @Test
    fun cabinAndIrRemainUntouched() {
        listOf(RecordingSourceRole.CABIN, RecordingSourceRole.IR).forEach { role ->
            val decision = SurroundPreviewLifecyclePolicy.onAppBackground(role, true, 3)
            assertFalse(decision.invalidateSurface)
            assertFalse(decision.disableRecorderPreview)
            assertFalse(decision.stopIdlePreview)
            assertEquals(3, decision.nextGeneration)
        }
    }
}
