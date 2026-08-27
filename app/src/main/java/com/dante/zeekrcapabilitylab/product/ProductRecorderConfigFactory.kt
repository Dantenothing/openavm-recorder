package com.dante.zeekrcapabilitylab.product

import android.content.Context
import com.dante.zeekrcapabilitylab.probe.camera.CameraProfileCatalog
import com.dante.zeekrcapabilitylab.service.recorder.FrontCropPolicy
import com.dante.zeekrcapabilitylab.service.recorder.RecorderConfig
import com.dante.zeekrcapabilitylab.service.recorder.RecordingMode
import com.dante.zeekrcapabilitylab.service.recorder.RecordingSourceKind

sealed interface RecorderConfigResolution {
    data class Ready(val config: RecorderConfig) : RecorderConfigResolution
    data class Blocked(val reason: String) : RecorderConfigResolution
}

/** Builds only configurations tied to an explicitly confirmed, unchanged source. */
object ProductRecorderConfigFactory {
    fun resolve(context: Context): RecorderConfigResolution {
        val settings = SettingsStore.get(context)
        if (EmulatorTestRecording.isAvailable()) {
            val emulatorConfig = EmulatorTestRecording.config(context, settings)
                ?: return RecorderConfigResolution.Blocked("EMULATOR_CAMERA_UNAVAILABLE")
            val emulatorErrors = emulatorConfig.validate(allowEmulatorTestSource = true)
            return if (emulatorErrors.isEmpty()) {
                RecorderConfigResolution.Ready(emulatorConfig)
            } else {
                RecorderConfigResolution.Blocked("EMULATOR_CONFIG_INVALID: ${emulatorErrors.joinToString("; ")}")
            }
        }
        val mode = settings.recordingMode
            ?: return RecorderConfigResolution.Blocked("MODE_CONFIRMATION_REQUIRED")
        val cameraId = settings.selectedCameraId
            ?: return RecorderConfigResolution.Blocked("SOURCE_CONFIRMATION_REQUIRED")
        val expectedFingerprint = settings.sourceFingerprint
            ?: return RecorderConfigResolution.Blocked("SOURCE_CONFIRMATION_REQUIRED")
        val sourceKind = settings.sourceKind
            ?: return RecorderConfigResolution.Blocked("SOURCE_CONFIRMATION_REQUIRED")
        val source = CameraRuntime.sourceCatalog(context).singleOrNull {
            it.cameraId == cameraId && it.fingerprint == expectedFingerprint
        } ?: return RecorderConfigResolution.Blocked("SOURCE_CHANGED_OR_UNAVAILABLE")
        val compositeSize = source.sizesFor(sourceKind)
            .filter(CameraProfileCatalog::isFourLaneComposite)
            .sortedWith(
                compareBy<com.dante.zeekrcapabilitylab.probe.camera.ProfileSize> {
                    if (it.height > it.width) 0 else 1
                }.thenByDescending { it.totalPixels },
            )
            .firstOrNull()
        val config = when (mode) {
            RecordingMode.SURROUND_360 -> {
                if (sourceKind != RecordingSourceKind.COMPOSITE) {
                    return RecorderConfigResolution.Blocked("SURROUND_REQUIRES_COMPOSITE_SOURCE")
                }
                val profile = compositeSize
                    ?.let { CameraProfileCatalog.productPreferredProfile(listOf(it)) }
                    ?: return RecorderConfigResolution.Blocked("VERIFIED_COMPOSITE_PROFILE_UNAVAILABLE")
                RecorderConfig(
                    cameraId = cameraId,
                    profile = profile,
                    segmentSeconds = settings.segmentSeconds,
                    storageLimitBytes = settings.storageLimitBytes,
                    minFreeBytes = settings.minFreeBytes,
                    recordingMode = mode,
                    sourceFingerprint = expectedFingerprint,
                    sourceKind = sourceKind,
                    sourceProfile = profile,
                )
            }

            RecordingMode.FRONT_ONLY -> {
                val encoder = CameraRuntime.frontEncoderProfile()
                    ?: return RecorderConfigResolution.Blocked("VALIDATED_H264_ENCODER_PROFILE_UNAVAILABLE")
                val output = com.dante.zeekrcapabilitylab.probe.camera.CameraFormatProfile(
                    size = com.dante.zeekrcapabilitylab.probe.camera.ProfileSize(
                        encoder.width,
                        encoder.height,
                    ),
                    bitrateBps = encoder.requestedBitrateBps,
                )
                when (sourceKind) {
                    RecordingSourceKind.COMPOSITE_CROP -> {
                        val sourceProfile = compositeSize
                            ?.let { CameraProfileCatalog.productPreferredProfile(listOf(it)) }
                            ?: return RecorderConfigResolution.Blocked("VERIFIED_COMPOSITE_PROFILE_UNAVAILABLE")
                        val frontSelection = FrontCropPolicy.fixedFrontSelection(
                            fingerprint = expectedFingerprint,
                            size = sourceProfile.size,
                        ) ?: return RecorderConfigResolution.Blocked("FIXED_FRONT_CROP_UNAVAILABLE")
                        RecorderConfig(
                            cameraId = cameraId,
                            profile = output,
                            segmentSeconds = settings.segmentSeconds,
                            storageLimitBytes = settings.storageLimitBytes,
                            minFreeBytes = settings.minFreeBytes,
                            recordingMode = mode,
                            sourceFingerprint = expectedFingerprint,
                            sourceKind = sourceKind,
                            sourceProfile = sourceProfile,
                            frontCalibration = frontSelection,
                            encoderProfile = encoder,
                            calibrationVersion = frontSelection.calibrationVersion,
                        )
                    }

                    RecordingSourceKind.DIRECT_FRONT -> {
                        val exact = directFrontSourceSize(source, output.size)
                            ?: return RecorderConfigResolution.Blocked("VERIFIED_DIRECT_FRONT_PROFILE_UNAVAILABLE")
                        RecorderConfig(
                            cameraId = cameraId,
                            profile = output,
                            segmentSeconds = settings.segmentSeconds,
                            storageLimitBytes = settings.storageLimitBytes,
                            minFreeBytes = settings.minFreeBytes,
                            recordingMode = mode,
                            sourceFingerprint = expectedFingerprint,
                            sourceKind = sourceKind,
                            sourceProfile = com.dante.zeekrcapabilitylab.probe.camera.CameraFormatProfile(
                                exact,
                                output.bitrateBps,
                            ),
                            encoderProfile = encoder,
                        )
                    }

                    RecordingSourceKind.COMPOSITE ->
                        return RecorderConfigResolution.Blocked("FRONT_SOURCE_NOT_CALIBRATED")
                }
            }
        }
        val errors = config.validate()
        return if (errors.isEmpty()) {
            RecorderConfigResolution.Ready(config)
        } else {
            RecorderConfigResolution.Blocked("CONFIG_INVALID: ${errors.joinToString("; ")}")
        }
    }

    fun create(context: Context): RecorderConfig? =
        (resolve(context) as? RecorderConfigResolution.Ready)?.config

    internal fun directFrontSourceSize(
        source: RuntimeCameraSource,
        outputSize: com.dante.zeekrcapabilitylab.probe.camera.ProfileSize,
    ): com.dante.zeekrcapabilitylab.probe.camera.ProfileSize? =
        source.sizesFor(RecordingSourceKind.DIRECT_FRONT).singleOrNull { it == outputSize }
}
