package com.dante.zeekrcapabilitylab.player

import com.dante.zeekrcapabilitylab.service.recorder.RecordingLayoutKind
import com.dante.zeekrcapabilitylab.service.recorder.RecordingSourceRole
import com.dante.zeekrcapabilitylab.service.recorder.SegmentSidecar

data class RecordingPresentation(
    val sourceRole: RecordingSourceRole,
    val layoutKind: RecordingLayoutKind,
)

/** Resolves playback only from facts stored with the recording, never current settings. */
object RecordingPresentationPolicy {
    fun resolve(sidecar: SegmentSidecar): RecordingPresentation {
        if (sidecar.schemaVersion >= 4) {
            return RecordingPresentation(sidecar.sourceRole, sidecar.layoutKind)
        }

        val width = sidecar.actualTrack?.width ?: sidecar.profile.size.width
        val height = sidecar.actualTrack?.height ?: sidecar.profile.size.height
        val fourLane = sidecar.laneLayout != null || isFourLane(width, height)
        val role = when {
            fourLane -> RecordingSourceRole.SURROUND
            sidecar.cameraId == "0" -> RecordingSourceRole.IR
            sidecar.cameraId == "1" -> RecordingSourceRole.CABIN
            else -> RecordingSourceRole.SURROUND
        }
        return RecordingPresentation(
            sourceRole = role,
            layoutKind = if (fourLane) {
                RecordingLayoutKind.FOUR_LANE_V1
            } else {
                RecordingLayoutKind.SINGLE_V1
            },
        )
    }

    private fun isFourLane(width: Int, height: Int): Boolean =
        FourLaneTextureLayout.isKnownFourLane(width, height)
}
