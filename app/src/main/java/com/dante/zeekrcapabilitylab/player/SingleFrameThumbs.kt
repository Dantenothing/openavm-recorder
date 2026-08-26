package com.dante.zeekrcapabilitylab.player

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import java.io.File

object SingleFrameThumbs {
    fun extractCover(file: File, widthPx: Int = 320, heightPx: Int = 180): Bitmap? {
        if (!file.isFile || widthPx <= 0 || heightPx <= 0) return null
        var retriever: MediaMetadataRetriever? = null
        return try {
            retriever = MediaMetadataRetriever().also { it.setDataSource(file.absolutePath) }
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 1_000L
            val frameTimeUs = minOf(durationMs / 2L, 2_000L).coerceAtLeast(0L) * 1_000L
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    frameTimeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    widthPx,
                    heightPx,
                )
            } else {
                retriever.getFrameAtTime(frameTimeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?.let { frame ->
                        Bitmap.createScaledBitmap(frame, widthPx, heightPx, true)
                            .also { if (it !== frame) frame.recycle() }
                    }
            }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever?.release() }
        }
    }
}
