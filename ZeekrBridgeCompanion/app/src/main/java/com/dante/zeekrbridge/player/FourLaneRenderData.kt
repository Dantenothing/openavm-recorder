package com.dante.zeekrbridge.player

import com.dante.zeekrbridge.core.FourLaneLayoutClassifier
import com.dante.zeekrbridge.core.IndexedLayoutKind
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.tan

internal enum class FourLaneLensMode {
    FISHEYE,
    STANDARD,
}

internal data class FourLaneCorrectionConfig(
    val targetFovDegrees: Float = 110f,
    val cropZoom: Float = 1.25f,
    val centerX: Float = 0.50f,
    val centerY: Float = 0.47f,
) {
    val halfFovTangent: Float
        get() = tan(Math.toRadians(targetFovDegrees.toDouble()) / 2.0).toFloat()
}

internal data class FourLaneViewport(
    val zoom: Float = MIN_ZOOM,
    val centerX: Float = 0f,
    val centerY: Float = 0f,
) {
    fun applyGesture(
        zoomChange: Float,
        panXPx: Float,
        panYPx: Float,
        viewWidth: Int,
        viewHeight: Int,
    ): FourLaneViewport {
        if (viewWidth <= 0 || viewHeight <= 0) return this
        val nextZoom = (zoom * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val maxCenter = 1f - 1f / nextZoom
        return FourLaneViewport(
            zoom = nextZoom,
            centerX = (centerX - panXPx * 2f / viewWidth / nextZoom)
                .coerceIn(-maxCenter, maxCenter),
            // SurfaceTexture's transform flips the source Y axis. Match the
            // user's finger instead of mirroring the horizontal pan formula.
            centerY = (centerY + panYPx * 2f / viewHeight / nextZoom)
                .coerceIn(-maxCenter, maxCenter),
        )
    }

    companion object {
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 3f
    }
}

internal fun toggleFourLaneMode(currentMode: Int, tappedLane: Int): Int =
    if (currentMode == tappedLane) FourLaneGlView.MODE_GRID else tappedLane

/**
 * Coalesces SurfaceTexture notifications without losing a frame that arrives
 * while the GL thread is consuming the previous notification.
 */
internal class SurfaceFrameSignal {
    private val pending = AtomicBoolean(false)

    fun markAvailable() {
        pending.set(true)
    }

    fun consumePending(): Boolean = pending.getAndSet(false)

    fun clear() {
        pending.set(false)
    }
}

internal fun laneForGridTap(
    x: Float,
    y: Float,
    width: Int,
    height: Int,
    order: IntArray = intArrayOf(1, 2, 3, 4),
): Int? {
    if (width <= 0 || height <= 0 || x !in 0f..width.toFloat() || y !in 0f..height.toFloat()) {
        return null
    }
    if (order.size != 4) return null
    val column = if (x < width / 2f) 0 else 1
    val row = if (y < height / 2f) 0 else 1
    return order[row * 2 + column].takeIf { it in 1..4 }
}

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

/**
 * Sidecar/file windows use top-left pixel coordinates. SurfaceTexture's
 * transform matrix expects the pre-transform OpenGL Y axis (bottom to top),
 * so convert only the window origin before applying that global matrix.
 */
internal fun LaneTextureWindow.forSurfaceTextureTransform(): LaneTextureWindow = copy(
    v = 1f - v - height,
)

/** Maps both known Zeekr four-camera composite layouts into one source lane. */
internal object FourLaneTextureLayout {
    private const val STRONG_FOUR_LANE_RATIO = 3.2f
    private const val MAX_SEPARATOR_FRACTION = 0.125f

    fun windowForLane(videoWidth: Int, videoHeight: Int, lane: Int,
        lanes: List<com.dante.zeekrbridge.core.IndexedLane> = emptyList()): LaneTextureWindow {
        require(videoWidth > 0) { "videoWidth must be positive" }
        require(videoHeight > 0) { "videoHeight must be positive" }
        require(lane in 1..4) { "lane must be in 1..4" }

        if (lanes.isNotEmpty()) {
            val source = lanes.single { it.lane == lane }
            require(source.x0 >= 0 && source.y0 >= 0 && source.x1 > source.x0 && source.y1 > source.y0 &&
                source.x1 <= videoWidth && source.y1 <= videoHeight)
            return LaneTextureWindow(source.x0.toFloat() / videoWidth, source.y0.toFloat() / videoHeight,
                (source.x1 - source.x0).toFloat() / videoWidth, (source.y1 - source.y0).toFloat() / videoHeight,
                source.x0.toFloat(), source.y0.toFloat(), (source.x1 - source.x0).toFloat(), (source.y1 - source.y0).toFloat())
        }

        val classified = FourLaneLayoutClassifier.classify(videoWidth, videoHeight)
        if (classified?.kind == IndexedLayoutKind.FOUR_LANE_GRID_2X2) {
            val source = classified.lanes.first { it.lane == lane }
            return LaneTextureWindow(
                u = source.x0.toFloat() / videoWidth,
                v = source.y0.toFloat() / videoHeight,
                width = (source.x1 - source.x0).toFloat() / videoWidth,
                height = (source.y1 - source.y0).toFloat() / videoHeight,
                sourceLeftPx = source.x0.toFloat(),
                sourceTopPx = source.y0.toFloat(),
                sourceWidthPx = (source.x1 - source.x0).toFloat(),
                sourceHeightPx = (source.y1 - source.y0).toFloat(),
            )
        }

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
