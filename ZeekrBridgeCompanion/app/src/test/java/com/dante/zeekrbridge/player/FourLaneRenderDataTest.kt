package com.dante.zeekrbridge.player

import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FourLaneRenderDataTest {

    @Test
    fun vertexBufferIsDirectAndUsesNativeByteOrder() {
        val buffer = createFourLaneVertexBuffer()

        assertTrue(buffer.isDirect)
        assertEquals(ByteOrder.nativeOrder(), buffer.order())
        assertEquals(8, buffer.capacity())
    }

    @Test
    fun tall1280x5140CompositeExcludesFiveFourPixelDividerBands() {
        val windows = (1..4).map { lane ->
            FourLaneTextureLayout.windowForLane(
                videoWidth = 1280,
                videoHeight = 5140,
                lane = lane,
            )
        }

        assertEquals(listOf(4f, 1288f, 2572f, 3856f), windows.map { it.sourceTopPx })
        assertEquals(List(4) { 1280f }, windows.map { it.sourceHeightPx })
        windows.forEach {
            assertEquals(0f, it.u, 0.000001f)
            assertEquals(1f, it.width, 0.000001f)
            assertEquals(1f, it.laneAspect, 0.000001f)
        }
    }

    @Test
    fun wide5120x1280CompositeUsesFourHorizontalSquareWindows() {
        val windows = (1..4).map { lane ->
            FourLaneTextureLayout.windowForLane(
                videoWidth = 5120,
                videoHeight = 1280,
                lane = lane,
            )
        }

        assertEquals(listOf(0f, 1280f, 2560f, 3840f), windows.map { it.sourceLeftPx })
        assertEquals(List(4) { 1280f }, windows.map { it.sourceWidthPx })
        windows.forEach { assertEquals(1f, it.laneAspect, 0.000001f) }
    }
}
