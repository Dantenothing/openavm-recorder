package com.dante.zeekrcapabilitylab.player

import com.dante.zeekrcapabilitylab.probe.camera.CompositeAxis

/** Normalized source window and pixel provenance for one composite-camera lane. */
data class LaneTextureWindow(
    val axis: CompositeAxis,
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

/**
 * Maps Zeekr's single four-camera texture into four source windows.
 *
 * Both layouts seen in the project are supported:
 * - 5120x1280: four square lanes laid left-to-right;
 * - 1280x5140: four 1280px square lanes stacked top-to-bottom, separated and
 *   surrounded by five 4px green bands (5120 content rows + 20 band rows).
 *
 * The math is normalized, so it also applies when the HAL advertises a small
 * 640x480 Surface hint while internally delivering the same composite content.
 */
object FourLaneTextureLayout {
    private const val STRONG_FOUR_LANE_RATIO = 3.2f
    private const val MAX_SEPARATOR_FRACTION = 0.125f

    fun windowForLane(
        videoWidth: Int,
        videoHeight: Int,
        lane: Int,
    ): LaneTextureWindow {
        require(videoWidth > 0) { "videoWidth must be positive" }
        require(videoHeight > 0) { "videoHeight must be positive" }
        require(lane in 1..4) { "lane must be in 1..4" }

        val index = lane - 1
        val horizontal = videoWidth.toFloat() / videoHeight >= STRONG_FOUR_LANE_RATIO
        return if (horizontal) {
            val segment = segmentAlongAxis(
                totalPx = videoWidth,
                squareLanePx = videoHeight,
                index = index,
            )
            LaneTextureWindow(
                axis = CompositeAxis.HORIZONTAL,
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
            val segment = segmentAlongAxis(
                totalPx = videoHeight,
                squareLanePx = videoWidth,
                index = index,
            )
            LaneTextureWindow(
                axis = CompositeAxis.VERTICAL,
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

    private fun segmentAlongAxis(
        totalPx: Int,
        squareLanePx: Int,
        index: Int,
    ): AxisSegment {
        val excess = totalPx - squareLanePx * 4
        val separatorFits = excess >= 0 &&
            excess <= squareLanePx * MAX_SEPARATOR_FRACTION
        if (separatorFits) {
            // Five equal bands: outer edge, three internal dividers, outer edge.
            val separator = excess / 5f
            return AxisSegment(
                startPx = separator + index * (squareLanePx + separator),
                sizePx = squareLanePx.toFloat(),
            )
        }

        // Unknown/legacy geometry: preserve all pixels with an equal four-way split.
        val laneSize = totalPx / 4f
        return AxisSegment(startPx = index * laneSize, sizePx = laneSize)
    }

    private data class AxisSegment(val startPx: Float, val sizePx: Float)
}
