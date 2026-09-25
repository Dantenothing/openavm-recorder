package com.dante.zeekrcapabilitylab.usbexport

import com.dante.zeekrcapabilitylab.enhancement.CameraWorkState

/** An active camera is not necessarily a writer of USB video files. */
internal object UsbDeleteAdmission {
    fun blockingReason(recorderRunning: Boolean, auxiliary: CameraWorkState): String? = when {
        // The service stays alive while stopping/finalizing. Do not substitute status == RECORDING.
        recorderRunning -> "RECORDING_ACTIVE"
        !auxiliary.active -> null
        auxiliary.kind == "MULTI" -> "RECORDING_ACTIVE"
        auxiliary.kind == "PREVIEW" && auxiliary.error == null -> null
        else -> "CAMERA_WORK_BUSY"
    }
}
