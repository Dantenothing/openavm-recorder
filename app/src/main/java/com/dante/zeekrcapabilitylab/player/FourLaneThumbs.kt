package com.dante.zeekrcapabilitylab.player

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.os.Build
import java.io.File
import kotlin.math.roundToInt

/** One-frame product thumbnails for horizontal or vertical four-lane files. */
object FourLaneThumbs {
    private const val STRONG_FOUR_LANE_RATIO = 3.2f

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
            if (!isFourLane(width, height)) return null

            val sourceLaneSize = minOf(width, height).coerceAtLeast(1)
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
                    val window = FourLaneTextureLayout.windowForLane(frame.width, frame.height, lane)
                    val left = window.sourceLeftPx.roundToInt().coerceIn(0, frame.width - 1)
                    val top = window.sourceTopPx.roundToInt().coerceIn(0, frame.height - 1)
                    val cropWidth = window.sourceWidthPx.roundToInt()
                        .coerceAtLeast(1)
                        .coerceAtMost(frame.width - left)
                    val cropHeight = window.sourceHeightPx.roundToInt()
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
        width > 0 && height > 0 && (
            width.toFloat() / height >= STRONG_FOUR_LANE_RATIO ||
                height.toFloat() / width >= STRONG_FOUR_LANE_RATIO
            )
}
