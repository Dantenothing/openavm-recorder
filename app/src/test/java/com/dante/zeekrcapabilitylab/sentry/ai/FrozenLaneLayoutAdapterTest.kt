package com.dante.zeekrcapabilitylab.sentry.ai

import com.dante.zeekrcapabilitylab.service.recorder.SegmentLaneLayoutFactory
import org.junit.Assert.*
import org.junit.Test

class FrozenLaneLayoutAdapterTest {
    @Test fun realCarGeometryUsesFrozenLayoutAndExcludesAllFiveSeparatorBands() {
        val source = SegmentLaneLayoutFactory.forProfile(1280, 5140, listOf("a", "b", "c", "d"),
            listOf(3, 1, 4, 2), listOf(90, 0, 180, 270))!!
        val frozen = FrozenLaneLayoutAdapter.freeze(source, "measured-layout-v1", setOf(3))
        assertEquals(listOf(2572, 4, 3856, 1288), frozen.lanes.map { it.y0 })
        assertEquals(listOf(3852, 1284, 5136, 2568), frozen.lanes.map { it.y1 })
        assertEquals(listOf(90, 0, 180, 270), frozen.lanes.map { it.rotationDegrees })
        assertTrue(frozen.lanes.first().mirrorHorizontal)
        assertEquals(1280, frozen.sourceWidth)
        assertEquals(5140, frozen.sourceHeight)
    }
}
