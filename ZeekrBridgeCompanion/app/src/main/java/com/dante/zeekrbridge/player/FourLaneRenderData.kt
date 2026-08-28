package com.dante.zeekrbridge.player

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

internal fun createFourLaneVertexBuffer(): FloatBuffer = ByteBuffer
    .allocateDirect(8 * java.lang.Float.BYTES)
    .order(ByteOrder.nativeOrder())
    .asFloatBuffer()
    .apply {
        put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        position(0)
    }

internal data class LaneTextureWindow(
    val u: Float,
    val v: Float,
    val width: Float,
    val height: Float,
    val sourceLeftPx: Float,
    val sourceTopPx: Float,
    val sourceWidthPx: Float,
    val sourceHeightPx: Float,
) {
    val laneAspect: Float get() = sourceWidthPx / sourceHeightPx
}

/** Maps both known Zeekr four-camera composite layouts into one source lane. */
internal object FourLaneTextureLayout {
    private const val STRONG_FOUR_LANE_RATIO = 3.2f
    private const val MAX_SEPARATOR_FRACTION = 0.125f

    fun windowForLane(videoWidth: Int, videoHeight: Int, lane: Int): LaneTextureWindow {
        require(videoWidth > 0) { "videoWidth must be positive" }
        require(videoHeight > 0) { "videoHeight must be positive" }
        require(lane in 1..4) { "lane must be in 1..4" }

        val index = lane - 1
        val horizontal = videoWidth.toFloat() / videoHeight >= STRONG_FOUR_LANE_RATIO
        return if (horizontal) {
            val segment = segmentAlongAxis(videoWidth, videoHeight, index)
            LaneTextureWindow(
                u = segment.startPx / videoWidth,
                v = 0f,
                width = segment.sizePx / videoWidth,
                height = 1f,
                sourceLeftPx = segment.startPx,
                sourceTopPx = 0f,
                sourceWidthPx = segment.sizePx,
                sourceHeightPx = videoHeight.toFloat(),
            )
        } else {
            val segment = segmentAlongAxis(videoHeight, videoWidth, index)
            LaneTextureWindow(
                u = 0f,
                v = segment.startPx / videoHeight,
                width = 1f,
                height = segment.sizePx / videoHeight,
                sourceLeftPx = 0f,
                sourceTopPx = segment.startPx,
                sourceWidthPx = videoWidth.toFloat(),
                sourceHeightPx = segment.sizePx,
            )
        }
    }

    private fun segmentAlongAxis(totalPx: Int, squareLanePx: Int, index: Int): AxisSegment {
        val excess = totalPx - squareLanePx * 4
        val separatorFits = excess >= 0 && excess <= squareLanePx * MAX_SEPARATOR_FRACTION
        if (separatorFits) {
            val separator = excess / 5f
            return AxisSegment(
                startPx = separator + index * (squareLanePx + separator),
                sizePx = squareLanePx.toFloat(),
            )
        }

        val laneSize = totalPx / 4f
        return AxisSegment(startPx = index * laneSize, sizePx = laneSize)
    }

    private data class AxisSegment(val startPx: Float, val sizePx: Float)
}
