package com.dante.zeekrcapabilitylab.product

import android.content.Context
import com.dante.zeekrcapabilitylab.probe.camera.CameraProfileCatalog
import com.dante.zeekrcapabilitylab.probe.camera.ProfileSize
import com.dante.zeekrcapabilitylab.service.recorder.RecorderConfig
import com.dante.zeekrcapabilitylab.service.recorder.RecordingLayoutKind
import com.dante.zeekrcapabilitylab.service.recorder.RecordingMode
import com.dante.zeekrcapabilitylab.service.recorder.RecordingSourceRole
import com.dante.zeekrcapabilitylab.service.recorder.SegmentLaneLayoutFactory
import com.dante.zeekrcapabilitylab.service.recorder.SessionSourceSnapshot

/** Builds the recorder configuration used by the public product UI. */
object ProductRecorderConfigFactory {
    fun listRecordingCameraCapabilities(context: Context): List<RecordingCameraCapability> =
        CameraRuntime.cameraIds(context).mapNotNull { cameraId ->
            val declaredSizes = CameraRuntime.videoSizeCandidates(context, cameraId)
                .map { ProfileSize(it.width, it.height) }
                .toSet()
            val profile = CameraProfileCatalog.productPreferredProfile(declaredSizes)
                ?: return@mapNotNull null
            RecordingCameraCapability(
                cameraId = cameraId,
                profile = profile,
                isFourLaneComposite = CameraProfileCatalog.isFourLaneComposite(profile.size),
            )
        }

    fun resolveSource(context: Context, role: RecordingSourceRole): SessionSourceSnapshot? {
        val settings = SettingsStore.get(context)
        val resolved = RecordingSourcePolicy.resolve(
            role = role,
            requestedCameraId = settings.cameraMapping(role),
            capabilities = listRecordingCameraCapabilities(context),
        ) ?: return null
        val profile = resolved.capability.profile
        val laneLayout = if (resolved.layoutKind == RecordingLayoutKind.FOUR_LANE_V1) {
            SegmentLaneLayoutFactory.forProfile(
                width = profile.size.width,
                height = profile.size.height,
                labels = settings.laneLabels,
                displayOrder = settings.laneOrder,
                rotations = settings.laneRotations,
            ) ?: return null
        } else {
            null
        }
        return SessionSourceSnapshot(
            sourceRole = role,
            cameraId = resolved.capability.cameraId,
            profile = profile,
            layoutKind = resolved.layoutKind,
            laneLayout = laneLayout,
            mappingRevision = settings.cameraMappingRevision,
        )
    }

    fun create(
        context: Context,
        role: RecordingSourceRole,
        recordingMode: RecordingMode = RecordingMode.NORMAL,
        timeLapseMultiplier: Int = 1,
    ): RecorderConfig? {
        val settings = SettingsStore.get(context)
        val source = resolveSource(context, role) ?: return null
        return RecorderConfig(
            source = source,
            segmentSeconds = settings.segmentSeconds,
            storageLimitBytes = settings.internalStorageLimitBytes,
            minFreeBytes = settings.minFreeBytes,
            storagePreference = settings.recordingStoragePreference,
            usbQuotaBytes = settings.usbQuotaBytes,
            recordingMode = recordingMode,
            timeLapseMultiplier = if (recordingMode == RecordingMode.NORMAL) 1 else timeLapseMultiplier,
        )
    }
}
