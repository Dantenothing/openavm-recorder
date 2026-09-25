package com.dante.zeekrcapabilitylab.player

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import io.github.dantenothing.avmtransfer.protocol.RecordingRasterMetadata
import io.github.dantenothing.avmtransfer.protocol.PixelRectangle
import io.github.dantenothing.avmtransfer.protocol.StripBitmapDrawing
import android.media.MediaMetadataRetriever
import android.os.Build
import java.io.File
import kotlin.math.roundToInt

/** One-frame product thumbnails for horizontal or vertical four-lane files. */
object FourLaneThumbs {
    fun extract(file: File, laneSizePx: Int = 240): List<Bitmap>? {
        if (!file.isFile || laneSizePx <= 0) return null
        var retriever: MediaMetadataRetriever? = null
        return try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: 1000L
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull()
                ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull()
                ?: 0
            val raster = RecordingRasterReader.read(file)
            if (raster.trackError(width, height) != null) return null
            val repack = (raster as? RecordingRasterMetadata.Repacked)?.contract
            val logicalWidth = repack?.inputWidth ?: width
            val logicalHeight = repack?.inputHeight ?: height
            if (!isFourLane(logicalWidth, logicalHeight)) return null

            val sourceLaneSize = minOf(logicalWidth, logicalHeight).coerceAtLeast(1)
            val scale = laneSizePx.toFloat() / sourceLaneSize
            val scaledWidth = (width * scale).roundToInt().coerceAtLeast(1)
            val scaledHeight = (height * scale).roundToInt().coerceAtLeast(1)
            val frameTimeUs = minOf(durationMs / 2L, 2_000L).coerceAtLeast(0L) * 1000L
            val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    frameTimeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    scaledWidth,
                    scaledHeight,
                )
            } else {
                retriever.getFrameAtTime(
                    frameTimeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                )
            } ?: return null

            try {
                (1..4).map { lane ->
                    val window = FourLaneTextureLayout.windowForLane(logicalWidth, logicalHeight, lane)
                    if (repack != null) {
                        return@map Bitmap.createBitmap(laneSizePx, laneSizePx, Bitmap.Config.ARGB_8888).also { output ->
                            StripBitmapDrawing.drawCrop(Canvas(output), frame, repack,
                                PixelRectangle(window.sourceLeftPx.roundToInt(), window.sourceTopPx.roundToInt(),
                                    window.sourceWidthPx.roundToInt(), window.sourceHeightPx.roundToInt()),
                                RectF(0f, 0f, laneSizePx.toFloat(), laneSizePx.toFloat()),
                                Paint(Paint.FILTER_BITMAP_FLAG))
                        }
                    }
                    // Derive legacy crop fractions from original track geometry, not a scaled
                    // bitmap whose rounded size can lose the 4px separators or the Sentry grid.
                    val scaledWindow = window.copy(sourceLeftPx = window.u * frame.width,
                        sourceTopPx = window.v * frame.height, sourceWidthPx = window.width * frame.width,
                        sourceHeightPx = window.height * frame.height)
                    val left = scaledWindow.sourceLeftPx.roundToInt().coerceIn(0, frame.width - 1)
                    val top = scaledWindow.sourceTopPx.roundToInt().coerceIn(0, frame.height - 1)
                    val cropWidth = scaledWindow.sourceWidthPx.roundToInt()
                        .coerceAtLeast(1)
                        .coerceAtMost(frame.width - left)
                    val cropHeight = scaledWindow.sourceHeightPx.roundToInt()
                        .coerceAtLeast(1)
                        .coerceAtMost(frame.height - top)
                    Bitmap.createBitmap(frame, left, top, cropWidth, cropHeight).let { crop ->
                        Bitmap.createScaledBitmap(crop, laneSizePx, laneSizePx, true)
                            .also { if (it !== crop) crop.recycle() }
                    }
                }
            } finally {
                frame.recycle()
            }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever?.release() }
        }
    }

    fun extractCover(file: File, laneSizePx: Int = 160): Bitmap? {
        val lanes = extract(file, laneSizePx) ?: return null
        return try {
            val cover = Bitmap.createBitmap(laneSizePx * 2, laneSizePx * 2, Bitmap.Config.RGB_565)
            val canvas = Canvas(cover)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            lanes.forEachIndexed { index, lane ->
                canvas.drawBitmap(
                    lane,
                    (index % 2 * laneSizePx).toFloat(),
                    (index / 2 * laneSizePx).toFloat(),
                    paint,
                )
            }
            cover
        } finally {
            lanes.forEach { runCatching { it.recycle() } }
        }
    }

    private fun isFourLane(width: Int, height: Int): Boolean =
        FourLaneTextureLayout.isKnownFourLane(width, height)
}
