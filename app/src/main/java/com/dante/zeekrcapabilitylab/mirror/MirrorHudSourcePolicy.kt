package com.dante.zeekrcapabilitylab.mirror

import com.dante.zeekrcapabilitylab.service.recorder.RecorderStatus

/** A visible, awake preview may read fixed vehicle getters. Recording keeps its existing producer. */
internal object MirrorHudSourcePolicy {
    fun previewReads(visible: Boolean, screenUsable: Boolean, telemetryEnabled: Boolean, recorderStatus: String) =
        visible && screenUsable && telemetryEnabled && recorderStatus in setOf(
            RecorderStatus.IDLE, RecorderStatus.STOPPED, RecorderStatus.PREVIEWING, RecorderStatus.AWAKE_IDLE,
            RecorderStatus.ERROR, RecorderStatus.CAMERA_UNAVAILABLE,
        )
}
