package com.dante.zeekrcapabilitylab.product

import android.content.Context
import com.dante.zeekrcapabilitylab.data.Categories
import com.dante.zeekrcapabilitylab.event.EventLogger
import com.dante.zeekrcapabilitylab.probe.camera.CameraProfileCatalog
import com.dante.zeekrcapabilitylab.probe.camera.ProfileSize
import com.dante.zeekrcapabilitylab.service.recorder.RecorderConfig
import java.io.File

/** Builds the recorder configuration used by the public product UI. */
object ProductRecorderConfigFactory {
    fun create(context: Context): RecorderConfig? {
        val settings = SettingsStore.get(context)
        val cameraIds = CameraRuntime.cameraIds(context)
        val cameraId = cameraIds.firstOrNull { it == "2" }
            ?: cameraIds.firstOrNull()
            ?: return null
        val declaredSizes = CameraRuntime.videoSizeCandidates(context, cameraId)
            .map { ProfileSize(it.width, it.height) }
            .toSet()
        val profile = CameraProfileCatalog.productPreferredProfile(declaredSizes) ?: return null

        val storage = RecordingStorageTargetPolicy.resolve(
            target = settings.recordingStorageTarget,
            usbRecordingsRootPath = UsbStorage.findRemovableVolume(context)?.let {
                File(it.root, RecordingStorageTargetPolicy.USB_RECORDINGS_SUBDIR).path
            },
        )
        if (storage.fellBackToInternal) {
            EventLogger.logEvent(
                Categories.SYSTEM,
                "RECORDER_STORAGE_TARGET_FALLBACK",
                payload = mapOf(
                    "target" to settings.recordingStorageTarget,
                    "resolved" to "internal",
                    "reason" to "NO_REMOVABLE_VOLUME",
                ),
            )
        }

        return RecorderConfig(
            cameraId = cameraId,
            profile = profile,
            segmentSeconds = settings.segmentSeconds,
            storageLimitBytes = settings.storageLimitBytes,
            minFreeBytes = settings.minFreeBytes,
            recordingsRootPath = storage.recordingsRootPath,
        )
    }
}
