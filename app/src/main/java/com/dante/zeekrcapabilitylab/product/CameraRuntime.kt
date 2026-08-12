package com.dante.zeekrcapabilitylab.product

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.util.Size
import java.io.File

data class RuntimeTrackMetadata(
    val width: Int?,
    val height: Int?,
    val bitrateBps: Long?,
    val durationMs: Long?,
)

/** Minimal Camera2/MediaRecorder queries required by the product recorder. */
object CameraRuntime {
    fun cameraIds(context: Context): List<String> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return emptyList()
        return runCatching { manager.cameraIdList.toList() }.getOrDefault(emptyList())
    }

    fun videoSizeCandidates(context: Context, cameraId: String): List<Size> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return emptyList()
        return runCatching {
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: return emptyList()
            map.getOutputSizes(MediaRecorder::class.java)
                ?.sortedByDescending { it.width.toLong() * it.height }
                .orEmpty()
        }.getOrDefault(emptyList())
    }

    fun readTrackMetadata(file: File): RuntimeTrackMetadata? {
        if (!file.isFile) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            RuntimeTrackMetadata(
                width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull(),
                height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull(),
                bitrateBps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                    ?.toLongOrNull(),
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull(),
            )
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}
