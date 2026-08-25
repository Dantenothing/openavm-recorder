package com.dante.zeekrcapabilitylab.product

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaMetadataRetriever
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaRecorder
import android.util.Size
import com.dante.zeekrcapabilitylab.probe.camera.ProfileSize
import com.dante.zeekrcapabilitylab.service.recorder.CameraSourceFacts
import com.dante.zeekrcapabilitylab.service.recorder.CameraSourceFingerprint
import com.dante.zeekrcapabilitylab.service.recorder.FrontEncoderProfilePolicy
import com.dante.zeekrcapabilitylab.service.recorder.EncoderProfile
import com.dante.zeekrcapabilitylab.service.recorder.VideoEncoderCapability
import com.dante.zeekrcapabilitylab.service.recorder.RecordingSourceKind
import java.io.File

data class RuntimeTrackMetadata(
    val width: Int?,
    val height: Int?,
    val bitrateBps: Long?,
    val durationMs: Long?,
)

data class RuntimeCameraSource(
    val cameraId: String,
    val fingerprint: String,
    val mediaRecorderSizes: List<ProfileSize>,
    val surfaceTextureSizes: List<ProfileSize>,
    val mediaCodecSizes: List<ProfileSize>,
) {
    /** Legacy name retained for display/360 callers. */
    val recordSizes: List<ProfileSize> get() = mediaRecorderSizes

    fun sizesFor(sourceKind: RecordingSourceKind): List<ProfileSize> = when (sourceKind) {
        RecordingSourceKind.COMPOSITE -> mediaRecorderSizes
        RecordingSourceKind.COMPOSITE_CROP -> surfaceTextureSizes
        RecordingSourceKind.DIRECT_FRONT -> mediaCodecSizes
    }
}

/** Minimal Camera2/MediaRecorder queries required by the product recorder. */
object CameraRuntime {
    fun cameraIds(context: Context): List<String> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return emptyList()
        return runCatching { manager.cameraIdList.toList() }.getOrDefault(emptyList())
    }

    fun videoSizeCandidates(
        context: Context,
        cameraId: String,
        sourceKind: RecordingSourceKind,
    ): List<Size> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return emptyList()
        return runCatching {
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: return emptyList()
            val outputClass = when (sourceKind) {
                RecordingSourceKind.COMPOSITE -> MediaRecorder::class.java
                RecordingSourceKind.COMPOSITE_CROP -> SurfaceTexture::class.java
                RecordingSourceKind.DIRECT_FRONT -> MediaCodec::class.java
            }
            map.getOutputSizes(outputClass)
                ?.sortedByDescending { it.width.toLong() * it.height }
                .orEmpty()
        }.getOrDefault(emptyList())
    }

    fun sourceCatalog(context: Context): List<RuntimeCameraSource> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return emptyList()
        return runCatching { manager.cameraIdList.toList() }
            .getOrDefault(emptyList())
            .mapNotNull { cameraId ->
                runCatching {
                    val characteristics = manager.getCameraCharacteristics(cameraId)
                    val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    fun sizesFor(outputClass: Class<*>): List<ProfileSize> = runCatching {
                        map?.getOutputSizes(outputClass)
                            ?.map { ProfileSize(it.width, it.height) }
                            ?.filter { it.width > 0 && it.height > 0 }
                            ?.distinct()
                            .orEmpty()
                    }.getOrDefault(emptyList())
                    val mediaRecorderSizes = sizesFor(MediaRecorder::class.java)
                    val surfaceTextureSizes = sizesFor(SurfaceTexture::class.java)
                    val mediaCodecSizes = sizesFor(MediaCodec::class.java)
                    val activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                    val facts = CameraSourceFacts(
                        cameraId = cameraId,
                        lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING),
                        hardwareLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL),
                        sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION),
                        activeArray = activeArray?.flattenToString(),
                        capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                            ?.toList()
                            .orEmpty(),
                        recordSizes = mediaRecorderSizes,
                        surfaceTextureSizes = surfaceTextureSizes,
                        mediaCodecSizes = mediaCodecSizes,
                    )
                    RuntimeCameraSource(
                        cameraId = cameraId,
                        fingerprint = CameraSourceFingerprint.create(facts),
                        mediaRecorderSizes = mediaRecorderSizes,
                        surfaceTextureSizes = surfaceTextureSizes,
                        mediaCodecSizes = mediaCodecSizes,
                    )
                }.getOrNull()
            }
    }

    /** Runtime H.264 surface-encoder gate used before FRONT_ONLY is offered. */
    fun frontEncoderProfile(): EncoderProfile? {
        val capabilities = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .asSequence()
                .filter { it.isEncoder && it.supportedTypes.any { type -> type.equals("video/avc", true) } }
                .mapNotNull { info ->
                    runCatching {
                        val codec = info.getCapabilitiesForType("video/avc")
                        val video = codec.videoCapabilities ?: return@runCatching null
                        val surfaceInput = codec.colorFormats.any {
                            it == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                        }
                        VideoEncoderCapability(
                            codecName = info.name,
                            widthAlignment = video.widthAlignment,
                            heightAlignment = video.heightAlignment,
                            widthRange = video.supportedWidths.lower..video.supportedWidths.upper,
                            heightRange = video.supportedHeights.lower..video.supportedHeights.upper,
                            frameRateRange = video.supportedFrameRates.lower.toInt()..
                                video.supportedFrameRates.upper.toInt(),
                            bitrateRange = video.bitrateRange.lower..video.bitrateRange.upper,
                            surfaceInput = surfaceInput && video.areSizeAndRateSupported(
                                FrontEncoderProfilePolicy.TARGET_SIZE,
                                FrontEncoderProfilePolicy.TARGET_SIZE,
                                FrontEncoderProfilePolicy.TARGET_FRAME_RATE.toDouble(),
                            ),
                            hardwareAccelerated = if (android.os.Build.VERSION.SDK_INT >=
                                android.os.Build.VERSION_CODES.Q
                            ) {
                                info.isHardwareAccelerated
                            } else {
                                !info.name.startsWith("OMX.google", ignoreCase = true) &&
                                    !info.name.startsWith("c2.android", ignoreCase = true)
                            },
                        )
                    }.getOrNull()
                }
                .toList()
        }.getOrDefault(emptyList())
        return FrontEncoderProfilePolicy.select(capabilities)
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
