package com.dante.zeekrcapabilitylab.product

import com.dante.zeekrcapabilitylab.enhancement.CameraWorkState
import com.dante.zeekrcapabilitylab.service.recorder.RecordingSourceRole

/** Home-page admission and presentation only; never opens or closes a camera. */
internal object HomePreviewSelection {
    fun canSelect(recording: Boolean, serviceRunning: Boolean, work: CameraWorkState,
                  mirrorActive: Boolean, mirrorBusy: Boolean, loading: Boolean): Boolean =
        !recording && !serviceRunning && (!work.active || work.kind == "PREVIEW" && mirrorActive) &&
            work.error == null && !mirrorBusy && !loading

    fun displayedSource(selected: RecordingSourceRole, recording: Boolean, recorderSource: RecordingSourceRole?,
                        previewOwned: Boolean, mirrorSource: RecordingSourceRole?): RecordingSourceRole = when {
        recording -> recorderSource ?: selected
        previewOwned -> mirrorSource ?: selected
        else -> selected
    }
}
