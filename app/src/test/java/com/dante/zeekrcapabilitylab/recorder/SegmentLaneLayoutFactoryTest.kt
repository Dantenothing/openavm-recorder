package com.dante.zeekrcapabilitylab.recorder

import com.dante.zeekrcapabilitylab.service.recorder.SegmentLaneLayoutFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SegmentLaneLayoutFactoryTest {

    @Test
    fun horizontalCompositeGetsFourLaneLayout() {
        val layout = SegmentLaneLayoutFactory.forProfile(
            width = 5120,
            height = 1280,
            labels = listOf("前", "后", "左", "右"),
            displayOrder = listOf(1, 2, 3, 4),
            rotations = listOf(0, 0, 90, 0),
        )
        assertNotNull(layout)
        assertEquals("HORIZONTAL_4X1", layout?.layoutType)
        assertEquals(4, layout?.lanes?.size)
        assertEquals(1280, layout?.lanes?.first()?.x1)
        assertEquals("前", layout?.lanes?.first()?.label)
        assertEquals(90, layout?.lanes?.get(2)?.rotationDegrees)
    }

    @Test
    fun nonHorizontalFrameReturnsNull() {
        assertNull(
            SegmentLaneLayoutFactory.forProfile(
                width = 1920,
                height = 1080,
                labels = listOf("a", "b", "c", "d"),
                displayOrder = listOf(1, 2, 3, 4),
                rotations = listOf(0, 0, 0, 0),
            ),
        )
    }

    @Test
    fun verticalRealCarCompositeGetsFourLaneLayoutWithoutGreenBands() {
        val layout = SegmentLaneLayoutFactory.forProfile(
            width = 1280,
            height = 5140,
            labels = listOf("前", "后", "左", "右"),
            displayOrder = listOf(1, 2, 3, 4),
            rotations = listOf(0, 0, 0, 0),
        )

        assertNotNull(layout)
        assertEquals("VERTICAL_1X4", layout?.layoutType)
        assertEquals(4, layout?.lanes?.size)
        assertEquals(4, layout?.lanes?.first()?.y0)
        assertEquals(1284, layout?.lanes?.first()?.y1)
        assertEquals(1288, layout?.lanes?.get(1)?.y0)
    }

    @Test
    fun invalidOrderFallsBackToIdentityAndLabelsToGeneric() {
        val layout = SegmentLaneLayoutFactory.forProfile(
            width = 5120,
            height = 1280,
            labels = emptyList(),
            displayOrder = listOf(2, 2, 2, 2),
            rotations = emptyList(),
        )
        assertNotNull(layout)
        assertEquals(listOf(1, 2, 3, 4), layout?.lanes?.map { it.lane })
        assertEquals(listOf("视角1", "视角2", "视角3", "视角4"), layout?.lanes?.map { it.label })
    }
}
