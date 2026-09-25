package com.dante.zeekrcapabilitylab.ui.product

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import android.util.AttributeSet
import android.view.TextureView
import android.view.ViewGroup
import com.dante.zeekrcapabilitylab.player.FourLaneCanvasLayout
import com.dante.zeekrcapabilitylab.player.FourLaneTextureLayout
import com.dante.zeekrcapabilitylab.mirror.MirrorGeometry
import com.dante.zeekrcapabilitylab.mirror.MirrorViewport
import com.dante.zeekrcapabilitylab.mirror.MirrorPanel
import com.dante.zeekrcapabilitylab.mirror.MirrorPanTransform
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
 * Keeps one TextureView (direct camera or GL display sink) and changes only
 * how that already-working child is drawn. FOUR_GRID draws the same child four
 * times; LANE_1..4 draw one crop across the full product preview area.
 */
class FourLaneTextureContainer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {
    val textureView = TextureView(context)
    /** A render pass is not evidence of a new image; diagnostics compare it with texture timestamps. */
    var diagnosticDrawPasses: Long = 0; private set
    var diagnosticLastDrawElapsedMs: Long? = null; private set
    /** A retained recorder preview must not resize its camera-facing child on window moves. */
    var fixedInputViewSize: android.util.Size? = null
        set(value) { field = value; requestLayout() }

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
    /** Opt-in presentation only. Never changes the camera buffer or recording pixels. */
    var fitSingleLane: Boolean = false
        set(value) { if (field != value) { field = value; invalidate() } }
    /** Exclusive Cabin preview: draw the whole frame, with its real aspect, without lane splitting. */
    var singleSource: Boolean = false
        set(value) { if (field != value) { field = value; invalidate() } }
    var singleLaneRotation: Int = 0
        set(value) { require(value in setOf(0, 90, 180, 270)); if (field != value) { field = value; invalidate() } }
    var mirrorSingleLane: Boolean = false
        set(value) { if (field != value) { field = value; invalidate() } }
    /** Three strip crops or four grid crops from one TextureView. Encoded output is unchanged. */
    var mirrorPanels: List<MirrorPanel> = emptyList()
        set(value) {
            require(value.isEmpty() || (value.size in 3..4 && value.map { it.lane }.distinct().size == value.size && value.all { it.lane in 1..4 }))
            if (field != value) { field = value.toList(); invalidate() }
        }
    private var viewportCenterX = 0f
    private var viewportCenterY = 0f

    private val sourceRect = RectF()
    private val destinationRect = RectF()
    private val sourceToDestination = Matrix()
    // Playback-only reconstruction. Live camera input remains the original logical raster.
    private var playbackRaster: io.github.dantenothing.avmtransfer.protocol.StripRepackContract? = null
    private var playbackBlocked = false
    private val reconstructionMatrix = Matrix()
    private var reconstructionKey: List<Any>? = null
    private var reconstructionPieces = emptyList<com.dante.zeekrcapabilitylab.player.StripCanvasPiece>()

    fun setPlaybackRaster(metadata: io.github.dantenothing.avmtransfer.protocol.RecordingRasterMetadata,
        encodedWidth: Int, encodedHeight: Int): Boolean {
        val contract = (metadata as? io.github.dantenothing.avmtransfer.protocol.RecordingRasterMetadata.Repacked)?.contract
        val logicalWidth = contract?.inputWidth ?: encodedWidth
        val logicalHeight = contract?.inputHeight ?: encodedHeight
        playbackBlocked = metadata.trackError(encodedWidth, encodedHeight) != null ||
            !FourLaneTextureLayout.isKnownFourLane(logicalWidth, logicalHeight)
        playbackRaster = contract.takeUnless { playbackBlocked }
        if (!playbackBlocked) setCompositeSize(logicalWidth, logicalHeight)
        reconstructionKey = null
        invalidate()
        return !playbackBlocked
    }

    fun blockPlaybackRaster() { playbackBlocked = true; invalidate() }

    init {
        setWillNotDraw(false)
        addView(textureView)
    }

    fun setCompositeSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val logicalWidth = playbackRaster?.inputWidth ?: width
        val logicalHeight = playbackRaster?.inputHeight ?: height
        if (compositeWidth == logicalWidth && compositeHeight == logicalHeight) return
        compositeWidth = logicalWidth
        compositeHeight = logicalHeight
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
        val rawWidth = fixedInputViewSize?.width ?: (measuredWidth * STABLE_INPUT_SIZE_FRACTION).roundToInt()
        val rawHeight = fixedInputViewSize?.height ?: (rawWidth / RAW_VIEW_ASPECT_RATIO).roundToInt()
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
        diagnosticDrawPasses++
        diagnosticLastDrawElapsedMs = android.os.SystemClock.elapsedRealtime()
        if (playbackBlocked) {
            // TextureView creates its SurfaceTexture on its first draw. Skipping the
            // child here deadlocks cold playback: the decoder needs that Surface
            // before it can report the video format which opens this shutter.
            // Keep consuming buffers, but cover unvalidated pixels in the same pass.
            super.dispatchDraw(canvas)
            canvas.drawColor(android.graphics.Color.BLACK)
            return
        }
        if (width <= 0 || height <= 0 || textureView.width <= 0 || textureView.height <= 0) return
        if (singleSource) {
            val saved = canvas.save()
            val target = floatArrayOf(0f, 0f, width.toFloat(), 0f, width.toFloat(), height.toFloat(), 0f, height.toFloat())
            sourceToDestination.setPolyToPoly(target, 0, MirrorGeometry.corners(width.toFloat(), height.toFloat(),
                compositeWidth.toFloat() / compositeHeight, 0, false), 0, 4)
            canvas.concat(sourceToDestination)
            sourceRect.set(textureView.left.toFloat(), textureView.top.toFloat(), textureView.right.toFloat(), textureView.bottom.toFloat())
            destinationRect.set(0f, 0f, width.toFloat(), height.toFloat())
            drawMappedChild(canvas, sourceRect, destinationRect)
            canvas.restoreToCount(saved)
            return
        }
        if (mirrorPanels.isNotEmpty() && canvas.isHardwareAccelerated) {
            mirrorPanels.forEachIndexed { index, panel ->
                val grid = mirrorPanels.size == 4
                val left = if (grid) width * (index % 2) / 2f else width * listOf(0f, 0.25f, 0.75f)[index]
                val top = if (grid) height * (index / 2) / 2f else 0f
                val cellWidth = width * if (grid || index == 1) 0.5f else 0.25f
                val cellHeight = if (grid) height / 2f else height.toFloat()
                val saved = canvas.save()
                canvas.clipRect(left, top, left + cellWidth, top + cellHeight)
                canvas.translate(left, top)
                val aspect = FourLaneTextureLayout.windowForLane(compositeWidth, compositeHeight, panel.lane).laneAspect
                val corners = MirrorGeometry.corners(cellWidth, cellHeight, aspect, panel.rotation, panel.mirrored)
                val transform = Matrix()
                transform.setPolyToPoly(floatArrayOf(0f, 0f, cellWidth, 0f, cellWidth, cellHeight, 0f, cellHeight), 0, corners, 0, 4)
                canvas.concat(transform)
                val draw = FourLaneCanvasLayout.planSingle(compositeWidth, compositeHeight,
                    textureView.left.toFloat(), textureView.top.toFloat(), textureView.width.toFloat(), textureView.height.toFloat(),
                    cellWidth, cellHeight, panel.lane)
                if (!grid || lensMode == FourLaneLensMode.STANDARD) drawCorrectedLane(canvas, draw, panel.viewport, panel.fovDegrees)
                else drawOriginalLane(canvas, draw)
                canvas.restoreToCount(saved)
            }
            return
        }
        if (displayMode == FourLaneDisplayMode.RAW_STRIP || !canvas.isHardwareAccelerated) {
            if (playbackRaster != null) drawLogicalChild(canvas) else super.dispatchDraw(canvas)
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
        val save = canvas.save()
        if (lane != null && fitSingleLane) {
            val aspect = FourLaneTextureLayout.windowForLane(compositeWidth, compositeHeight, lane).laneAspect
            val source = floatArrayOf(0f, 0f, width.toFloat(), 0f, width.toFloat(), height.toFloat(), 0f, height.toFloat())
            val mapped = MirrorGeometry.corners(width.toFloat(), height.toFloat(), aspect, singleLaneRotation, mirrorSingleLane)
            val presentation = Matrix()
            presentation.setPolyToPoly(source, 0, mapped, 0, 4)
            canvas.concat(presentation)
        }
        draws.forEach { draw ->
            if (lensMode == FourLaneLensMode.STANDARD) {
                drawCorrectedLane(canvas, draw)
            } else {
                drawOriginalLane(canvas, draw)
            }
        }
        canvas.restoreToCount(save)
    }

    /** Applies a user gesture only while one lane is enlarged. Returns the resulting zoom. */
    fun applyViewportGesture(zoomChange: Float, panXPx: Float, panYPx: Float): Float {
        if (displayMode.singleLane == null || width <= 0 || height <= 0) return viewportZoom
        if (fitSingleLane) {
            val aspect = FourLaneTextureLayout.windowForLane(compositeWidth, compositeHeight, displayMode.singleLane!!).laneAspect
            val (x, y) = MirrorPanTransform.sourceDelta(width.toFloat(), height.toFloat(), aspect,
                singleLaneRotation, mirrorSingleLane, panXPx, panYPx)
            val crop = if (lensMode == FourLaneLensMode.STANDARD) correctionConfig.cropZoom else 1f
            setMirrorViewport(mirrorViewport().gesture(zoomChange, x, y, crop))
            return viewportZoom
        }
        viewportZoom = (viewportZoom * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val maxCenter = 1f - 1f / viewportZoom
        viewportCenterX = (viewportCenterX - panXPx * 2f / width / viewportZoom)
            .coerceIn(-maxCenter, maxCenter)
        viewportCenterY = (viewportCenterY - panYPx * 2f / height / viewportZoom)
            .coerceIn(-maxCenter, maxCenter)
        invalidate()
        return viewportZoom
    }

    fun mirrorViewport() = MirrorViewport(viewportZoom, viewportCenterX, viewportCenterY)

    fun setMirrorViewport(value: MirrorViewport) {
        val safe = value.sanitized(if (lensMode == FourLaneLensMode.STANDARD) correctionConfig.cropZoom else 1f)
        if (mirrorViewport() == safe) return
        viewportZoom = safe.zoom; viewportCenterX = safe.centerX; viewportCenterY = safe.centerY
        invalidate()
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
    private fun drawCorrectedLane(canvas: Canvas, draw: com.dante.zeekrcapabilitylab.player.FourLaneCanvasDraw,
        viewport: MirrorViewport = mirrorViewport(), fovDegrees: Float = correctionConfig.targetFovDegrees) {
        val source = draw.source
        val destination = draw.destination
        val divisions = if (displayMode.singleLane == null) GRID_MESH_DIVISIONS else SINGLE_MESH_DIVISIONS
        val halfFovTangent = tan(
            Math.toRadians(fovDegrees.toDouble()) / 2.0,
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
                correctedSourcePoint(source, x0, y0, halfFovTangent, sourcePoints, 0, viewport)
                correctedSourcePoint(source, x1, y0, halfFovTangent, sourcePoints, 2, viewport)
                correctedSourcePoint(source, x1, y1, halfFovTangent, sourcePoints, 4, viewport)
                correctedSourcePoint(source, x0, y1, halfFovTangent, sourcePoints, 6, viewport)

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
                    drawLogicalChild(canvas)
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
        viewport: MirrorViewport,
    ) {
        val config = correctionConfig
        val planeX = (
            (unitX * 2f - 1f) / (viewport.zoom * config.cropZoom) + viewport.centerX
            ) * halfFovTangent
        val planeY = (
            (unitY * 2f - 1f) / (viewport.zoom * config.cropZoom) + viewport.centerY
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
        drawLogicalChild(canvas)
        canvas.restoreToCount(saveCount)
    }

    private fun drawLogicalChild(canvas: Canvas) {
        val layout = playbackRaster ?: run { drawChild(canvas, textureView, drawingTime); return }
        val key = listOf(layout, textureView.left, textureView.top, textureView.width, textureView.height)
        if (key != reconstructionKey) {
            reconstructionPieces = com.dante.zeekrcapabilitylab.player.StripCanvasLayout.plan(layout,
                com.dante.zeekrcapabilitylab.player.FloatBounds(textureView.left.toFloat(), textureView.top.toFloat(),
                    textureView.right.toFloat(), textureView.bottom.toFloat()))
            reconstructionKey = key
        }
        for (piece in reconstructionPieces) {
            val logical = RectF(piece.logical.left, piece.logical.top, piece.logical.right, piece.logical.bottom)
            @Suppress("DEPRECATION")
            if (canvas.quickReject(logical, Canvas.EdgeType.AA)) continue
            val save = canvas.save()
            canvas.clipRect(logical)
            reconstructionMatrix.setRectToRect(RectF(piece.encoded.left, piece.encoded.top,
                piece.encoded.right, piece.encoded.bottom), logical, Matrix.ScaleToFit.FILL)
            canvas.concat(reconstructionMatrix)
            drawChild(canvas, textureView, drawingTime)
            canvas.restoreToCount(save)
        }
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
