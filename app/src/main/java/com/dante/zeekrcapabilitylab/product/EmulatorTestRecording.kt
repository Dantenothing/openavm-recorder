package com.dante.zeekrcapabilitylab.product

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import com.dante.zeekrcapabilitylab.BuildConfig
import com.dante.zeekrcapabilitylab.probe.camera.CameraFormatProfile
import com.dante.zeekrcapabilitylab.probe.camera.ProfileSize
import com.dante.zeekrcapabilitylab.service.recorder.RecorderConfig
import com.dante.zeekrcapabilitylab.service.recorder.RecordingMode
import com.dante.zeekrcapabilitylab.service.recorder.RecordingSourceKind

/** Pure emulator detection kept separate so JVM tests can cover false positives. */
object EmulatorDevicePolicy {
    fun isEmulator(
        fingerprint: String,
        model: String,
        manufacturer: String,
        brand: String,
        device: String,
        hardware: String,
        product: String,
    ): Boolean {
        val values = listOf(fingerprint, model, manufacturer, brand, device, hardware, product)
            .map(String::lowercase)
        return values.any {
            it.contains("emulator") ||
                it.contains("android sdk built for") ||
                it.contains("goldfish") ||
                it.contains("ranchu") ||
                it.contains("sdk_gphone")
        } || fingerprint.startsWith("generic")
    }
}

/** Selects one conservative, widely supported Camera2/MediaRecorder emulator profile. */
object EmulatorRecordingPolicy {
    val preferredSizes = listOf(
        ProfileSize(1280, 720),
        ProfileSize(640, 480),
        ProfileSize(320, 240),
    )

    fun selectSource(sources: List<RuntimeCameraSource>): RuntimeCameraSource? =
        sources
            .filter { source -> preferredSizes.any { it in source.mediaRecorderSizes } }
            .sortedWith(
                compareBy<RuntimeCameraSource> {
                    if (it.lensFacing == CameraCharacteristics.LENS_FACING_BACK) 0 else 1
                }.thenBy { it.cameraId },
            )
            .firstOrNull()

    fun selectSize(source: RuntimeCameraSource): ProfileSize? =
        preferredSizes.firstOrNull { it in source.mediaRecorderSizes }

    fun sourceKindFor(mode: RecordingMode): RecordingSourceKind = when (mode) {
        RecordingMode.FRONT_ONLY -> RecordingSourceKind.DIRECT_FRONT
        RecordingMode.SURROUND_360 -> RecordingSourceKind.COMPOSITE
    }
}

/**
 * Debug-only runtime adapter for ordinary AVD cameras.
 *
 * It exercises the real Camera2 -> MediaRecorder -> segment/sidecar/library path,
 * but it is not a four-lane AVM source and is never accepted by release builds.
 */
object EmulatorTestRecording {
    const val BITRATE_BPS = 4_000_000

    fun isAvailable(): Boolean = BuildConfig.DEBUG && EmulatorDevicePolicy.isEmulator(
        fingerprint = Build.FINGERPRINT,
        model = Build.MODEL,
        manufacturer = Build.MANUFACTURER,
        brand = Build.BRAND,
        device = Build.DEVICE,
        hardware = Build.HARDWARE,
        product = Build.PRODUCT,
    )

    fun source(context: Context): RuntimeCameraSource? =
        if (isAvailable()) EmulatorRecordingPolicy.selectSource(CameraRuntime.sourceCatalog(context)) else null

    fun effectiveMode(selectedMode: RecordingMode?): RecordingMode =
        selectedMode ?: RecordingMode.SURROUND_360

    fun config(context: Context, settings: SettingsStore): RecorderConfig? {
        val source = source(context) ?: return null
        val size = EmulatorRecordingPolicy.selectSize(source) ?: return null
        val mode = effectiveMode(settings.recordingMode)
        val profile = CameraFormatProfile(
            size = size,
            bitrateBps = BITRATE_BPS,
            source = "debug-emulator-camera",
        )
        return RecorderConfig(
            cameraId = source.cameraId,
            profile = profile,
            segmentSeconds = settings.segmentSeconds,
            storageLimitBytes = settings.storageLimitBytes,
            minFreeBytes = RecorderConfig.EMULATOR_TEST_MIN_FREE_BYTES,
            recordingMode = mode,
            sourceFingerprint = "debug-emulator:${source.fingerprint}",
            sourceKind = EmulatorRecordingPolicy.sourceKindFor(mode),
            sourceProfile = profile,
            emulatorTestSource = true,
        )
    }
}
