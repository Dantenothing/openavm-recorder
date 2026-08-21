package com.dante.zeekrcapabilitylab.product

import android.content.Context
import com.dante.zeekrcapabilitylab.data.Categories
import com.dante.zeekrcapabilitylab.event.EventLogger
import com.dante.zeekrcapabilitylab.probe.camera.CameraFormatProfile
import com.dante.zeekrcapabilitylab.probe.camera.CameraProfileCatalog
import com.dante.zeekrcapabilitylab.probe.camera.ProfileSize
import com.dante.zeekrcapabilitylab.service.recorder.RecorderConfig

/** Builds the recorder configuration used by the public product UI. */
object ProductRecorderConfigFactory {

    /** A camera that can drive the current recording pipeline. */
    data class RecordingCameraOption(
        val cameraId: String,
        val profile: CameraFormatProfile,
        val isFourLaneComposite: Boolean,
    )

    /**
     * Cameras with at least one usable declared recording profile, in
     * CameraManager order. Cameras the pipeline cannot drive are omitted, so
     * the settings UI can only offer choices that will actually record.
     */
    fun listRecordingCameraOptions(context: Context): List<RecordingCameraOption> =
        CameraRuntime.cameraIds(context).mapNotNull { cameraId ->
            val declaredSizes = CameraRuntime.videoSizeCandidates(context, cameraId)
                .map { ProfileSize(it.width, it.height) }
                .toSet()
            val profile = CameraProfileCatalog.productPreferredProfile(declaredSizes)
                ?: return@mapNotNull null
            RecordingCameraOption(
                cameraId = cameraId,
                profile = profile,
                isFourLaneComposite = CameraProfileCatalog.isFourLaneComposite(profile.size),
            )
        }

    fun create(context: Context): RecorderConfig? {
        val settings = SettingsStore.get(context)
        val options = listRecordingCameraOptions(context)
        val selection = RecordingCameraPolicy.choose(
            requestedId = settings.recordingCameraId,
            usableIds = options.map { it.cameraId },
        ) ?: return null
        if (selection.fallbackFromRequested) {
            EventLogger.logEvent(
                category = Categories.SYSTEM,
                eventName = "RECORDER_CAMERA_SELECTION_FALLBACK",
                payload = mapOf(
                    "requested" to settings.recordingCameraId,
                    "selected" to selection.cameraId,
                ),
            )
        }
        val option = options.first { it.cameraId == selection.cameraId }
        return RecorderConfig(
            cameraId = option.cameraId,
            profile = option.profile,
            segmentSeconds = settings.segmentSeconds,
            storageLimitBytes = settings.storageLimitBytes,
            minFreeBytes = settings.minFreeBytes,
        )
    }
}
