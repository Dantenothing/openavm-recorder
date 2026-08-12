package com.dante.zeekrcapabilitylab.validate

import android.media.MediaMetadataRetriever
import com.dante.zeekrcapabilitylab.data.Categories
import com.dante.zeekrcapabilitylab.event.EventLogger
import java.io.File

enum class ValidationStatus { VALID, PARTIAL, CORRUPTED, UNKNOWN }

data class ValidationReport(
    val fileName: String,
    val exists: Boolean,
    val sizeBytes: Long,
    val durationMs: Long?,
    val hasVideo: Boolean,
    val hasAudio: Boolean,
    val mime: String?,
    val width: Int?,
    val height: Int?,
    val rotation: Int?,
    val bitrate: Long?,
    val firstFrameOk: Boolean,
    val midFrameOk: Boolean,
    val lastFrameOk: Boolean,
    val status: ValidationStatus,
)

object RecordingValidator {

    fun validate(file: File): ValidationReport {
        val exists = file.exists()
        val size = if (exists) file.length() else 0L
        if (!exists || size == 0L) {
            return ValidationReport(
                fileName = file.name,
                exists = exists,
                sizeBytes = size,
                durationMs = null,
                hasVideo = false,
                hasAudio = false,
                mime = null,
                width = null,
                height = null,
                rotation = null,
                bitrate = null,
                firstFrameOk = false,
                midFrameOk = false,
                lastFrameOk = false,
                status = if (!exists) ValidationStatus.UNKNOWN else ValidationStatus.CORRUPTED,
            )
        }
        var retriever: MediaMetadataRetriever? = null
        return try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            val mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()
            val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()
            val first = duration?.let { retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) } != null
            val mid = duration?.let {
                retriever.getFrameAtTime(it * 500, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } != null
            val last = duration?.let {
                retriever.getFrameAtTime((it - 1).coerceAtLeast(0), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } != null
            val valid = duration != null && duration > 0 && hasVideo && first && mid && last
            val partial = duration != null && duration > 0 && (hasVideo || first || mid || last)
            val status = when {
                valid -> ValidationStatus.VALID
                partial -> ValidationStatus.PARTIAL
                else -> ValidationStatus.CORRUPTED
            }
            ValidationReport(
                fileName = file.name,
                exists = true,
                sizeBytes = size,
                durationMs = duration,
                hasVideo = hasVideo,
                hasAudio = hasAudio,
                mime = mime,
                width = width,
                height = height,
                rotation = rotation,
                bitrate = bitrate,
                firstFrameOk = first,
                midFrameOk = mid,
                lastFrameOk = last,
                status = status,
            )
        } catch (t: Throwable) {
            ValidationReport(
                fileName = file.name,
                exists = true,
                sizeBytes = size,
                durationMs = null,
                hasVideo = false,
                hasAudio = false,
                mime = null,
                width = null,
                height = null,
                rotation = null,
                bitrate = null,
                firstFrameOk = false,
                midFrameOk = false,
                lastFrameOk = false,
                status = ValidationStatus.CORRUPTED,
            )
        } finally {
            try {
                retriever?.release()
            } catch (t: Throwable) {
                // Ignore.
            }
        }
    }

    fun log(file: File, report: ValidationReport) {
        EventLogger.logEvent(
            category = Categories.SYSTEM,
            eventName = "RECORDING_VALIDATED",
            payload = mapOf(
                "file" to file.name,
                "status" to report.status.name,
                "durationMs" to (report.durationMs?.toString() ?: "-"),
                "size" to report.sizeBytes.toString(),
                "video" to report.hasVideo.toString(),
                "audio" to report.hasAudio.toString(),
                "resolution" to "${report.width}x${report.height}",
                "rotation" to (report.rotation?.toString() ?: "-"),
            ),
        )
    }
}
