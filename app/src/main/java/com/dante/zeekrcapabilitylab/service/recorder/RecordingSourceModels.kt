package com.dante.zeekrcapabilitylab.service.recorder

import com.dante.zeekrcapabilitylab.probe.camera.CameraFormatProfile
import kotlinx.serialization.Serializable

@Serializable
enum class RecordingSourceRole {
    SURROUND,
    CABIN,
    IR,
}

@Serializable
enum class RecordingLayoutKind {
    FOUR_LANE_V1,
    SINGLE_V1,
}

/** Immutable camera identity and presentation captured by one manual Start. */
@Serializable
data class SessionSourceSnapshot(
    val sourceRole: RecordingSourceRole,
    val cameraId: String,
    val profile: CameraFormatProfile,
    val layoutKind: RecordingLayoutKind,
    val laneLayout: SegmentLaneLayout? = null,
    val mappingRevision: Int = 0,
)
