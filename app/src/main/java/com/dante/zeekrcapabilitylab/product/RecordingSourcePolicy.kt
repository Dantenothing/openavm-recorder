package com.dante.zeekrcapabilitylab.product

import com.dante.zeekrcapabilitylab.probe.camera.CameraFormatProfile
import com.dante.zeekrcapabilitylab.service.recorder.RecordingLayoutKind
import com.dante.zeekrcapabilitylab.service.recorder.RecordingSourceRole

data class RecordingCameraCapability(
    val cameraId: String,
    val profile: CameraFormatProfile,
    val isFourLaneComposite: Boolean,
)

data class ResolvedRecordingSource(
    val sourceRole: RecordingSourceRole,
    val capability: RecordingCameraCapability,
    val layoutKind: RecordingLayoutKind,
)

/** Pure source-to-camera mapping. It never falls back to another semantic role. */
object RecordingSourcePolicy {
    const val AUTO = "auto"
    const val DEFAULT_SURROUND_CAMERA_ID = "2"
    const val DEFAULT_CABIN_CAMERA_ID = "1"
    const val DEFAULT_IR_CAMERA_ID = "0"

    fun defaultMapping(role: RecordingSourceRole): String = when (role) {
        RecordingSourceRole.SURROUND -> AUTO
        RecordingSourceRole.CABIN -> DEFAULT_CABIN_CAMERA_ID
        RecordingSourceRole.IR -> DEFAULT_IR_CAMERA_ID
    }

    fun resolve(
        role: RecordingSourceRole,
        requestedCameraId: String,
        capabilities: List<RecordingCameraCapability>,
    ): ResolvedRecordingSource? {
        val candidate = when {
            role == RecordingSourceRole.SURROUND && requestedCameraId == AUTO -> {
                val composites = capabilities.filter { it.isFourLaneComposite }
                when {
                    composites.size == 1 -> composites.single()
                    composites.size > 1 -> composites.firstOrNull {
                        it.cameraId == DEFAULT_SURROUND_CAMERA_ID
                    }
                    else -> null
                }
            }
            requestedCameraId == AUTO -> null
            else -> capabilities.firstOrNull { it.cameraId == requestedCameraId }
        } ?: return null

        if (role == RecordingSourceRole.SURROUND && !candidate.isFourLaneComposite) return null

        return ResolvedRecordingSource(
            sourceRole = role,
            capability = candidate,
            layoutKind = if (role == RecordingSourceRole.SURROUND) {
                RecordingLayoutKind.FOUR_LANE_V1
            } else {
                RecordingLayoutKind.SINGLE_V1
            },
        )
    }
}
