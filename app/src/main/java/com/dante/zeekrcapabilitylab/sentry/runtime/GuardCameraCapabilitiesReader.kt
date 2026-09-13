package com.dante.zeekrcapabilitylab.sentry.runtime

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaCodec
import com.dante.zeekrcapabilitylab.probe.camera.ProfileSize
import com.dante.zeekrcapabilitylab.service.recorder.SessionSourceSnapshot

internal class GuardCameraPreparationException(val source: SessionSourceSnapshot, val stage: String, cause: Throwable) :
    Exception("$stage: ${cause.message.orEmpty().take(250)}", cause)

/** Reads all metadata before NORMAL opens; no CameraDevice, encoder or image reader is created. */
internal object GuardCameraCapabilitiesReader {
    fun read(context: Context, source: SessionSourceSnapshot): GuardCameraCapabilities {
        var stage = "CAMERA_CHARACTERISTICS"
        try {
            val camera = context.getSystemService(CameraManager::class.java).getCameraCharacteristics(source.cameraId)
            stage = "CAMERA_STREAM_MAP"
            val streams = camera.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            stage = "CAMERA_ENCODER_SURFACE_SIZES"
            val declared = streams?.getOutputSizes(MediaCodec::class.java).orEmpty().any {
                it.width == source.profile.size.width && it.height == source.profile.size.height }
            check(declared) { "ENCODER_SURFACE_SIZE_UNDECLARED" }
            stage = "CAMERA_FPS_RANGES"
            val range = camera.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                .orEmpty().filter { it.contains(15) }.minByOrNull { it.upper - it.lower }
                ?: error("NO_15_FPS_CAMERA_RANGE")
            stage = "CAMERA_TIMESTAMP_SOURCE"
            val realtime = camera.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
                CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
            val analysis = runCatching {
                GuardAnalysisSizePolicy.choose(streams?.getOutputSizes(ImageFormat.YUV_420_888).orEmpty()
                    .map { ProfileSize(it.width, it.height) }, source)
            }
            return GuardCameraCapabilities(source.cameraId, source.profile.size.width, source.profile.size.height,
                declared, range.lower, range.upper, realtime, analysis.getOrNull(),
                analysis.exceptionOrNull()?.let { "CAMERA_YUV_SIZES: ${GuardExceptionSummary.describe(it)}" }
                    ?: if (analysis.getOrNull() == null) "NO_MATCHING_YUV_OUTPUT" else null,
                System.currentTimeMillis()).also { it.requireMatches(source) }
        } catch (error: Exception) {
            throw GuardCameraPreparationException(source, stage, error)
        }
    }
}
