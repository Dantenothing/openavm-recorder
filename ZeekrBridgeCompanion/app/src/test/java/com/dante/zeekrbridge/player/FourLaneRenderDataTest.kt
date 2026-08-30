package com.dante.zeekrbridge.player

import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun surfaceTextureTransformKeepsTopFileLaneAtTheTop() {
        val rawTop = FourLaneTextureLayout.windowForLane(1280, 5140, lane = 1)
        val rawBottom = FourLaneTextureLayout.windowForLane(1280, 5140, lane = 4)
        val shaderTop = rawTop.forSurfaceTextureTransform()
        val shaderBottom = rawBottom.forSurfaceTextureTransform()

        // A typical SurfaceTexture matrix applies sampledY = 1 - shaderY.
        // After that global flip, both windows must return to their original
        // top-left file coordinates instead of exchanging lanes 1 and 4.
        assertEquals(rawTop.v, 1f - (shaderTop.v + shaderTop.height), 0.000001f)
        assertEquals(rawTop.v + rawTop.height, 1f - shaderTop.v, 0.000001f)
        assertEquals(rawBottom.v, 1f - (shaderBottom.v + shaderBottom.height), 0.000001f)
        assertEquals(rawBottom.v + rawBottom.height, 1f - shaderBottom.v, 0.000001f)
        assertTrue(shaderTop.v > shaderBottom.v)
    }

    @Test
    fun fullHeightHorizontalCompositeWindowIsUnchangedForSurfaceTexture() {
        val raw = FourLaneTextureLayout.windowForLane(5120, 1280, lane = 1)
        val shader = raw.forSurfaceTextureTransform()

        assertEquals(0f, shader.v, 0.000001f)
        assertEquals(1f, shader.height, 0.000001f)
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

    @Test
    fun squareGridTapSelectsTheExpectedLane() {
        assertEquals(1, laneForGridTap(20f, 20f, 100, 100))
        assertEquals(2, laneForGridTap(80f, 20f, 100, 100))
        assertEquals(3, laneForGridTap(20f, 80f, 100, 100))
        assertEquals(4, laneForGridTap(80f, 80f, 100, 100))
    }

    @Test
    fun gridTapUsesFrozenSourceOrderAndSecondTapReturnsToGrid() {
        val reversedSourceOrder = intArrayOf(4, 3, 2, 1)

        val frontSourceLane = laneForGridTap(20f, 20f, 100, 100, reversedSourceOrder)
        assertEquals(4, frontSourceLane)
        assertEquals(4, toggleFourLaneMode(currentMode = 0, tappedLane = frontSourceLane!!))
        assertEquals(0, toggleFourLaneMode(currentMode = 4, tappedLane = frontSourceLane))
    }

    @Test
    fun enlargedViewportZoomsPansAndStaysInsideSourceBounds() {
        val zoomed = FourLaneViewport().applyGesture(
            zoomChange = 2f,
            panXPx = 25f,
            panYPx = -25f,
            viewWidth = 100,
            viewHeight = 100,
        )

        assertEquals(2f, zoomed.zoom, 0.000001f)
        assertEquals(-0.25f, zoomed.centerX, 0.000001f)
        assertEquals(-0.25f, zoomed.centerY, 0.000001f)

        val clamped = zoomed.applyGesture(10f, 10_000f, -10_000f, 100, 100)
        assertEquals(3f, clamped.zoom, 0.000001f)
        assertEquals(-(1f - 1f / 3f), clamped.centerX, 0.000001f)
        assertEquals(-(1f - 1f / 3f), clamped.centerY, 0.000001f)
    }

    @Test
    fun phoneStandardViewUsesTheValidatedCarDefaults() {
        val correction = FourLaneCorrectionConfig()

        assertEquals(110f, correction.targetFovDegrees, 0.000001f)
        assertEquals(1.25f, correction.cropZoom, 0.000001f)
        assertEquals(0.50f, correction.centerX, 0.000001f)
        assertEquals(0.47f, correction.centerY, 0.000001f)
        assertTrue(correction.halfFovTangent > 1f)
    }

    @Test
    fun frameArrivingDuringDrawRemainsPendingForTheNextDraw() {
        val signal = SurfaceFrameSignal()
        signal.markAvailable()

        assertTrue(signal.consumePending())
        signal.markAvailable()

        assertTrue(signal.consumePending())
        assertFalse(signal.consumePending())
    }
}
