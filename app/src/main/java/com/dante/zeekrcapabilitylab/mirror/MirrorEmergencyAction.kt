package com.dante.zeekrcapabilitylab.mirror

import com.dante.zeekrcapabilitylab.service.recorder.RecorderStatus
import com.dante.zeekrcapabilitylab.service.recorder.RecordingMode

/** A bookmark is only a command to the existing normal-recording session. */
object MirrorEmergencyAction {
    fun allowed(previewOnly: Boolean, status: String, mode: RecordingMode, busy: Boolean): Boolean =
        !previewOnly && status == RecorderStatus.RECORDING && mode == RecordingMode.NORMAL && !busy
}
