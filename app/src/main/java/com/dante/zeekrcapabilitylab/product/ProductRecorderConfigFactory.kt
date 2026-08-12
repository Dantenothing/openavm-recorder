package com.dante.zeekrcapabilitylab.product

import android.content.Context
import com.dante.zeekrcapabilitylab.probe.camera.CameraProfileCatalog
import com.dante.zeekrcapabilitylab.probe.camera.ProfileSize
import com.dante.zeekrcapabilitylab.service.recorder.RecorderConfig

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

        return RecorderConfig(
            cameraId = cameraId,
            profile = profile,
            segmentSeconds = settings.segmentSeconds,
            storageLimitBytes = settings.storageLimitBytes,
            minFreeBytes = settings.minFreeBytes,
        )
    }
}
