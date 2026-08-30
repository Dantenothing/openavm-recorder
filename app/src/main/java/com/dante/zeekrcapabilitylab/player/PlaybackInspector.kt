package com.dante.zeekrcapabilitylab.player

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File

data class PlaybackDiagnostics(
    val mime: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val bitrateBps: Int? = null,
    val frameRateFps: Float? = null,
    val decoderNames: List<String> = emptyList(),
    val sizeSupported: Boolean? = null,
    val error: String? = null,
) {
    val codecLabel: String
        get() = when (mime) {
            MediaFormat.MIMETYPE_VIDEO_AVC -> "H.264 / AVC"
            MediaFormat.MIMETYPE_VIDEO_HEVC -> "H.265 / HEVC"
            null -> "未知"
            else -> mime
        }
}

/** Reads the real MP4 track and the decoders advertised by this head unit. */
object PlaybackInspector {
    fun inspect(file: File): PlaybackDiagnostics {
        var extractor: MediaExtractor? = null
        return try {
            extractor = MediaExtractor()
            extractor.setDataSource(file.absolutePath)
            val format = (0 until extractor.trackCount)
                .map { extractor.getTrackFormat(it) }
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ?: return PlaybackDiagnostics(error = "文件中没有视频轨道")

            val mime = format.getString(MediaFormat.KEY_MIME)
            val width = format.intOrNull(MediaFormat.KEY_WIDTH)
            val height = format.intOrNull(MediaFormat.KEY_HEIGHT)
            val durationMs = format.longOrNull(MediaFormat.KEY_DURATION)?.div(1_000L)
            val bitrate = format.intOrNull(MediaFormat.KEY_BIT_RATE)
            val frameRate = format.intOrNull(MediaFormat.KEY_FRAME_RATE)?.toFloat()
            if (mime.isNullOrBlank()) {
                return PlaybackDiagnostics(
                    width = width,
                    height = height,
                    durationMs = durationMs,
                    bitrateBps = bitrate,
                    frameRateFps = frameRate,
                    error = "视频轨道没有编码类型",
                )
            }

            val decoders = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                .asSequence()
                .filter { !it.isEncoder && it.supportedTypes.any { type -> type.equals(mime, true) } }
                .toList()
            val supportResults = if (width != null && height != null) {
                decoders.mapNotNull { codec -> codec.supportsSize(mime, width, height) }
            } else {
                emptyList()
            }
            PlaybackDiagnostics(
                mime = mime,
                width = width,
                height = height,
                durationMs = durationMs,
                bitrateBps = bitrate,
                frameRateFps = frameRate,
                decoderNames = decoders.map { it.name },
                sizeSupported = when {
                    decoders.isEmpty() -> false
                    supportResults.any { it } -> true
                    supportResults.isNotEmpty() -> false
                    else -> null
                },
            )
        } catch (t: Throwable) {
            PlaybackDiagnostics(error = t.message ?: t.javaClass.simpleName)
        } finally {
            runCatching { extractor?.release() }
        }
    }

    private fun MediaFormat.intOrNull(key: String): Int? =
        runCatching { if (containsKey(key)) getInteger(key) else null }.getOrNull()

    private fun MediaFormat.longOrNull(key: String): Long? =
        runCatching { if (containsKey(key)) getLong(key) else null }.getOrNull()

    private fun MediaCodecInfo.supportsSize(mime: String, width: Int, height: Int): Boolean? =
        runCatching {
            getCapabilitiesForType(mime).videoCapabilities?.isSizeSupported(width, height)
        }.getOrNull()
}
