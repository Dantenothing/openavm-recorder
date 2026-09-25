package com.dante.zeekrcapabilitylab.mirror

import org.junit.Assert.*
import org.junit.Test

class MirrorCompactGeometryTest {
    @Test fun windowStaysInsideScreenAtBothSizeLimits() {
        for ((width, height) in listOf(1920 to 1080, 1280 to 720, 320 to 600, 900 to 420)) {
            for (size in listOf(1, 300, 450, 800, 9999)) for (controls in listOf(false, true)) for (video in listOf(false, true)) {
                val result = MirrorCompactGeometry.fit(size, width, height, video, controls)
                assertTrue(result.width in 1..width)
                assertTrue(result.height in 1..height)
                if (controls && video) assertTrue(result.railWidth in listOf(48, 156))
            }
        }
    }
    @Test fun narrowWindowUsesPopupControlsAndLeavesRoomForFixedTouchTargets() {
        val narrow = MirrorCompactGeometry.fit(320, 1920, 1080, true, true)
        assertTrue(narrow.compact); assertEquals(48, narrow.railWidth); assertTrue(narrow.bodyHeight >= 332)
        val full = MirrorCompactGeometry.fit(600, 1920, 1080, true, true)
        assertFalse(full.compact); assertEquals(156, full.railWidth); assertTrue(full.bodyHeight >= 636)
        assertTrue(MirrorCompactGeometry.fit(800, 1280, 600, true, true).compact)
    }
    @Test fun centredLogoDoesNotOverlapControlsOrTheResizeCorner() {
        for (width in listOf(300, 450, 600, 720, 1100)) {
            val layout = MirrorCompactGeometry.fit(width, 1920, 1080, true, true)
            val inset = MirrorCompactGeometry.controlsInset(layout.bodyHeight, layout.compact)
            val logo = if (layout.compact) 44 else 156
            val rows = if (layout.compact) 96 else 192
            val top = (layout.bodyHeight - logo) / 2
            assertTrue(inset + rows <= top)
            assertTrue(top + logo <= layout.bodyHeight - inset - rows)
            assertTrue(inset >= 44)
        }
    }
    @Test fun foldingControlsKeepsImageAndMinimizingProducesOnlyACapsule() {
        val folded = MirrorCompactGeometry.fit(600, 1920, 1080, true, false)
        assertEquals(0, folded.railWidth); assertTrue(folded.bodyHeight > 0)
        val capsule = MirrorCompactGeometry.fit(600, 1920, 1080, false, false)
        assertEquals(52, capsule.height); assertEquals(0, capsule.bodyHeight); assertEquals(340, capsule.width)
    }
    @Test fun gridOrderAndTouchRoutingUseCalibratedDirectionsRatherThanRawLaneNumbers() {
        val order = listOf(3, 4, 2, 1)
        assertEquals(order, MirrorLayoutPolicy.grid(order))
        assertEquals(order, listOf(.25f to .25f, .75f to .25f, .25f to .75f, .75f to .75f).map { (x,y) ->
            order[MirrorLayoutPolicy.gridAt(x,y)] })
        assertNull(MirrorLayoutPolicy.grid(listOf(1, 1, 2, 3)))
        assertNull(MirrorLayoutPolicy.grid(listOf(1, 2, 3)))
        assertNull(MirrorLayoutPolicy.grid(listOf(0, 2, 3, 4)))
    }
}
