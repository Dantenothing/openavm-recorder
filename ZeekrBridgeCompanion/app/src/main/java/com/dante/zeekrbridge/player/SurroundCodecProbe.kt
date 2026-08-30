package com.dante.zeekrbridge.player

import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Process
import java.io.File

internal data class SurroundCodecProbe(
    val mime: String,
    val width: Int,
    val height: Int,
    val process64Bit: Boolean,
    val supportedDecoders: List<String>,
    val rejectedDecoders: List<String>,
    val error: String? = null,
)

internal fun probeSurroundCodec(file: File): SurroundCodecProbe {
    val extractor = MediaExtractor()
    return try {
        extractor.setDataSource(file.absolutePath)
        val format = (0 until extractor.trackCount)
            .map { extractor.getTrackFormat(it) }
            .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            ?: return SurroundCodecProbe(
                mime = "unknown",
                width = 0,
                height = 0,
                process64Bit = Process.is64Bit(),
                supportedDecoders = emptyList(),
                rejectedDecoders = emptyList(),
                error = "No video track",
            )

        val mime = format.getString(MediaFormat.KEY_MIME) ?: "unknown"
        val width = format.integerOrZero(MediaFormat.KEY_WIDTH)
        val height = format.integerOrZero(MediaFormat.KEY_HEIGHT)
        val supported = mutableListOf<String>()
        val rejected = mutableListOf<String>()

        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .asSequence()
            .filterNot { it.isEncoder }
            .forEach { info ->
                val type = info.supportedTypes.firstOrNull { it.equals(mime, ignoreCase = true) }
                    ?: return@forEach
                val sizeSupported = runCatching {
                    info.getCapabilitiesForType(type)
                        .videoCapabilities
                        ?.isSizeSupported(width, height) == true
                }.getOrDefault(false)
                if (sizeSupported) supported += info.name else rejected += info.name
            }

        SurroundCodecProbe(
            mime = mime,
            width = width,
            height = height,
            process64Bit = Process.is64Bit(),
            supportedDecoders = supported.distinct(),
            rejectedDecoders = rejected.distinct(),
        )
    } catch (t: Throwable) {
        SurroundCodecProbe(
            mime = "unknown",
            width = 0,
            height = 0,
            process64Bit = Process.is64Bit(),
            supportedDecoders = emptyList(),
            rejectedDecoders = emptyList(),
            error = "${t.javaClass.simpleName}: ${t.message.orEmpty()}",
        )
    } finally {
        runCatching { extractor.release() }
    }
}

private fun MediaFormat.integerOrZero(key: String): Int =
    if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(0) else 0
