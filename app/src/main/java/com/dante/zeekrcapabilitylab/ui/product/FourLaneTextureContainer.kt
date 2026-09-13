package com.dante.zeekrcapabilitylab.ui.product

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import android.util.AttributeSet
import android.view.TextureView
import android.view.ViewGroup
import com.dante.zeekrcapabilitylab.player.FourLaneCanvasLayout
import com.dante.zeekrcapabilitylab.product.FisheyeCorrectionConfig
import com.dante.zeekrcapabilitylab.product.FourLaneLensMode
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.tan

enum class FourLaneDisplayMode(val singleLane: Int?) {
    FOUR_GRID(null),
    LANE_1(1),
    LANE_2(2),
    LANE_3(3),
    LANE_4(4),
    RAW_STRIP(null),
    ;

    fun toggleLane(lane: Int): FourLaneDisplayMode =
        if (singleLane == lane) FOUR_GRID else forLane(lane)

    companion object {
        fun forLane(lane: Int): FourLaneDisplayMode = when (lane) {
            1 -> LANE_1
            2 -> LANE_2
            3 -> LANE_3
            4 -> LANE_4
            else -> throw IllegalArgumentException("lane must be in 1..4")
        }
    }
}

/**
 * Keeps one ordinary TextureView as the only camera consumer and changes only
 * how that already-working child is drawn. FOUR_GRID draws the same child four
 * times; LANE_1..4 draw one crop across the full product preview area.
 */
class FourLaneTextureContainer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {
    val textureView = TextureView(context)

    private var compositeWidth: Int = DEFAULT_COMPOSITE_WIDTH
    private var compositeHeight: Int = DEFAULT_COMPOSITE_HEIGHT

    var displayMode: FourLaneDisplayMode = FourLaneDisplayMode.FOUR_GRID
        set(value) {
            if (field == value) return
            if (field.singleLane != value.singleLane) resetViewport(invalidateView = false)
            field = value
            invalidate()
        }

    var lensMode: FourLaneLensMode = FourLaneLensMode.FISHEYE
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var correctionConfig: FisheyeCorrectionConfig = FisheyeCorrectionConfig()
        set(value) {
            val safe = value.sanitized()
            if (field == safe) return
            field = safe
            invalidate()
        }

    var viewportZoom: Float = MIN_ZOOM
        private set
    private var viewportCenterX = 0f
    private var viewportCenterY = 0f

    private val sourceRect = RectF()
    private val destinationRect = RectF()
    private val sourceToDestination = Matrix()

    init {
        setWillNotDraw(false)
        addView(textureView)
    }

    fun setCompositeSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (compositeWidth == width && compositeHeight == height) return
        compositeWidth = width
        compositeHeight = height
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(
            resolveSize(width, widthMeasureSpec),
            resolveSize(height, heightMeasureSpec),
        )

        // The product panel is now larger, but the camera-facing child stays
        // near the successful v0.6.9 size. Only Canvas output is enlarged.
        val rawWidth = (measuredWidth * STABLE_INPUT_SIZE_FRACTION).roundToInt()
        val rawHeight = (rawWidth / RAW_VIEW_ASPECT_RATIO).roundToInt()
            .coerceAtMost(measuredHeight)
        textureView.measure(
            MeasureSpec.makeMeasureSpec(rawWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(rawHeight, MeasureSpec.EXACTLY),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val childLeft = (width - textureView.measuredWidth) / 2
        val childTop = (height - textureView.measuredHeight) / 2
        textureView.layout(
            childLeft,
            childTop,
            childLeft + textureView.measuredWidth,
            childTop + textureView.measuredHeight,
        )
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (displayMode == FourLaneDisplayMode.RAW_STRIP || !canvas.isHardwareAccelerated) {
            super.dispatchDraw(canvas)
            return
        }

        val lane = displayMode.singleLane
        val draws = if (lane == null) {
            FourLaneCanvasLayout.plan(
                videoWidth = compositeWidth,
                videoHeight = compositeHeight,
                contentLeft = textureView.left.toFloat(),
                contentTop = textureView.top.toFloat(),
                contentWidth = textureView.width.toFloat(),
                contentHeight = textureView.height.toFloat(),
                destinationWidth = width.toFloat(),
                destinationHeight = height.toFloat(),
            )
        } else {
            listOf(
                FourLaneCanvasLayout.planSingle(
                    videoWidth = compositeWidth,
                    videoHeight = compositeHeight,
                    contentLeft = textureView.left.toFloat(),
                    contentTop = textureView.top.toFloat(),
                    contentWidth = textureView.width.toFloat(),
                    contentHeight = textureView.height.toFloat(),
                    destinationWidth = width.toFloat(),
                    destinationHeight = height.toFloat(),
                    lane = lane,
                ),
            )
        }
        draws.forEach { draw ->
            if (lensMode == FourLaneLensMode.STANDARD) {
                drawCorrectedLane(canvas, draw)
            } else {
                drawOriginalLane(canvas, draw)
            }
        }
    }

    /** Applies a user gesture only while one lane is enlarged. Returns the resulting zoom. */
    fun applyViewportGesture(zoomChange: Float, panXPx: Float, panYPx: Float): Float {
        if (displayMode.singleLane == null || width <= 0 || height <= 0) return viewportZoom
        viewportZoom = (viewportZoom * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val maxCenter = 1f - 1f / viewportZoom
        viewportCenterX = (viewportCenterX - panXPx * 2f / width / viewportZoom)
            .coerceIn(-maxCenter, maxCenter)
        viewportCenterY = (viewportCenterY - panYPx * 2f / height / viewportZoom)
            .coerceIn(-maxCenter, maxCenter)
        invalidate()
        return viewportZoom
    }

    fun resetViewport(): Float {
        resetViewport(invalidateView = true)
        return viewportZoom
    }

    private fun resetViewport(invalidateView: Boolean) {
        viewportZoom = MIN_ZOOM
        viewportCenterX = 0f
        viewportCenterY = 0f
        if (invalidateView) invalidate()
    }

    private fun drawOriginalLane(canvas: Canvas, draw: com.dante.zeekrcapabilitylab.player.FourLaneCanvasDraw) {
        sourceRect.set(draw.source.left, draw.source.top, draw.source.right, draw.source.bottom)
        if (displayMode.singleLane != null && viewportZoom > MIN_ZOOM) {
            val halfWidth = sourceRect.width() / (2f * viewportZoom)
            val halfHeight = sourceRect.height() / (2f * viewportZoom)
            val centerX = sourceRect.centerX() + viewportCenterX * sourceRect.width() / 2f
            val centerY = sourceRect.centerY() + viewportCenterY * sourceRect.height() / 2f
            sourceRect.set(
                centerX - halfWidth,
                centerY - halfHeight,
                centerX + halfWidth,
                centerY + halfHeight,
            )
        }
        destinationRect.set(draw.destination.left, draw.destination.top, draw.destination.right, draw.destination.bottom)
        drawMappedChild(canvas, sourceRect, destinationRect)
    }

    /**
     * Piecewise inverse projection. Each output point is treated as a ray from
     * a rectilinear virtual camera, then mapped back into an equidistant 180°
     * fisheye source. It reuses the existing TextureView RenderNode: no bitmap
     * readback, re-encoding, or second camera/decoder Surface is introduced.
     */
    private fun drawCorrectedLane(canvas: Canvas, draw: com.dante.zeekrcapabilitylab.player.FourLaneCanvasDraw) {
        val source = draw.source
        val destination = draw.destination
        val divisions = if (displayMode.singleLane == null) GRID_MESH_DIVISIONS else SINGLE_MESH_DIVISIONS
        val halfFovTangent = tan(
            Math.toRadians(correctionConfig.targetFovDegrees.toDouble()) / 2.0,
        ).toFloat()
        for (row in 0 until divisions) {
            for (column in 0 until divisions) {
                val x0 = column.toFloat() / divisions
                val x1 = (column + 1).toFloat() / divisions
                val y0 = row.toFloat() / divisions
                val y1 = (row + 1).toFloat() / divisions
                val destinationPoints = floatArrayOf(
                    lerp(destination.left, destination.right, x0), lerp(destination.top, destination.bottom, y0),
                    lerp(destination.left, destination.right, x1), lerp(destination.top, destination.bottom, y0),
                    lerp(destination.left, destination.right, x1), lerp(destination.top, destination.bottom, y1),
                    lerp(destination.left, destination.right, x0), lerp(destination.top, destination.bottom, y1),
                )
                val sourcePoints = FloatArray(8)
                correctedSourcePoint(source, x0, y0, halfFovTangent, sourcePoints, 0)
                correctedSourcePoint(source, x1, y0, halfFovTangent, sourcePoints, 2)
                correctedSourcePoint(source, x1, y1, halfFovTangent, sourcePoints, 4)
                correctedSourcePoint(source, x0, y1, halfFovTangent, sourcePoints, 6)

                destinationRect.set(
                    destinationPoints[0],
                    destinationPoints[1],
                    destinationPoints[4],
                    destinationPoints[5],
                )
                val saveCount = canvas.save()
                canvas.clipRect(destinationRect)
                sourceToDestination.reset()
                if (sourceToDestination.setPolyToPoly(sourcePoints, 0, destinationPoints, 0, 4)) {
                    canvas.concat(sourceToDestination)
                    drawChild(canvas, textureView, drawingTime)
                }
                canvas.restoreToCount(saveCount)
            }
        }
    }

    private fun correctedSourcePoint(
        source: com.dante.zeekrcapabilitylab.player.FloatBounds,
        unitX: Float,
        unitY: Float,
        halfFovTangent: Float,
        output: FloatArray,
        offset: Int,
    ) {
        val config = correctionConfig
        val planeX = (
            (unitX * 2f - 1f) / (viewportZoom * config.cropZoom) + viewportCenterX
            ) * halfFovTangent
        val planeY = (
            (unitY * 2f - 1f) / (viewportZoom * config.cropZoom) + viewportCenterY
            ) * halfFovTangent
        val rayRadius = hypot(planeX, planeY)

        // Equidistant source model: fisheye radius is proportional to the ray
        // angle, with radius 1 representing 90° off-axis (180° full FOV).
        val sourceRadius = (atan(rayRadius) / (PI.toFloat() / 2f)).coerceIn(0f, 1f)
        val directionX = if (rayRadius > 0.00001f) planeX / rayRadius else 0f
        val directionY = if (rayRadius > 0.00001f) planeY / rayRadius else 0f
        val sourceUnitX = (config.centerX + directionX * sourceRadius * 0.5f).coerceIn(0f, 1f)
        val sourceUnitY = (config.centerY + directionY * sourceRadius * 0.5f).coerceIn(0f, 1f)
        output[offset] = source.left + sourceUnitX * source.width
        output[offset + 1] = source.top + sourceUnitY * source.height
    }

    private fun drawMappedChild(canvas: Canvas, source: RectF, destination: RectF) {
        val saveCount = canvas.save()
        canvas.clipRect(destination)
        sourceToDestination.reset()
        sourceToDestination.setRectToRect(source, destination, Matrix.ScaleToFit.FILL)
        canvas.concat(sourceToDestination)
        drawChild(canvas, textureView, drawingTime)
        canvas.restoreToCount(saveCount)
    }

    private fun lerp(start: Float, end: Float, amount: Float): Float = start + (end - start) * amount

    private companion object {
        const val STABLE_INPUT_SIZE_FRACTION = 0.62f
        const val RAW_VIEW_ASPECT_RATIO = 2f
        const val DEFAULT_COMPOSITE_WIDTH = 1280
        const val DEFAULT_COMPOSITE_HEIGHT = 5140
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 3f
        const val GRID_MESH_DIVISIONS = 6
        const val SINGLE_MESH_DIVISIONS = 10
    }
}
