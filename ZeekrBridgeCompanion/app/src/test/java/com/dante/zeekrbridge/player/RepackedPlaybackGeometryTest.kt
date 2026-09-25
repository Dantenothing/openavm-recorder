package com.dante.zeekrbridge.player

import com.dante.zeekrbridge.core.IndexedLane
import org.junit.Assert.*
import org.junit.Test

class RepackedPlaybackGeometryTest {
    private val lanes = listOf(4, 1288, 2572, 3856).mapIndexed { index, top ->
        IndexedLane("View ${index + 1}", 0, 1280, top, top + 1280, 4 - index, index + 1)
    }

    @Test fun renderingUsesPhysicalLaneIdentityBeforeConvertingToGlOrigin() {
        for (lane in lanes) {
            val topLeft = FourLaneTextureLayout.windowForLane(1280, 5140, lane.lane, lanes)
            assertEquals(lane.y0.toFloat(), topLeft.sourceTopPx, 0f)
            assertEquals(1280f, topLeft.sourceHeightPx, 0f)
            val bottomLeft = topLeft.forSurfaceTextureTransform()
            assertEquals(1f - lane.y1 / 5140f, bottomLeft.v, 0.000001f)
            assertEquals(1280f / 5140f, bottomLeft.height, 0.000001f)
        }
    }

    @Test fun encodedWidthMustNotBeSubstitutedForLogicalDimensions() {
        assertThrows(IllegalArgumentException::class.java) {
            FourLaneTextureLayout.windowForLane(3840, 1728, 3, lanes)
        }
    }
}
