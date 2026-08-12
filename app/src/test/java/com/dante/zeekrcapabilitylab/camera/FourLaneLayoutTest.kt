package com.dante.zeekrcapabilitylab.camera

import com.dante.zeekrcapabilitylab.probe.camera.CompositeAxis
import com.dante.zeekrcapabilitylab.probe.camera.FourLaneLayout
import org.junit.Assert.assertEquals
import org.junit.Test

class FourLaneLayoutTest {

    @Test
    fun knownWideCompositeIsNormalizedFromHorizontalToVertical() {
        val layout = FourLaneLayout.infer(width = 5120, height = 1280)

        assertEquals(CompositeAxis.HORIZONTAL, layout.axis)
        assertEquals(90, layout.normalizationRotationDegrees)
        assertEquals(1280, layout.normalizedWidth)
        assertEquals(5120, layout.normalizedHeight)
    }

    @Test
    fun legacyTallCompositeRemainsVertical() {
        val layout = FourLaneLayout.infer(width = 1280, height = 5140)

        assertEquals(CompositeAxis.VERTICAL, layout.axis)
        assertEquals(0, layout.normalizationRotationDegrees)
        assertEquals(1280, layout.normalizedWidth)
        assertEquals(5140, layout.normalizedHeight)
    }

    @Test
    fun ordinaryLandscapeFrameKeepsExistingVerticalLaneAssumption() {
        val layout = FourLaneLayout.infer(width = 3840, height = 2160)

        assertEquals(CompositeAxis.VERTICAL, layout.axis)
        assertEquals(0, layout.normalizationRotationDegrees)
    }
}
