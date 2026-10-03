package com.dante.zeekrcapabilitylab.mirror

import com.dante.zeekrcapabilitylab.service.recorder.RecorderState
import com.dante.zeekrcapabilitylab.service.recorder.RecorderStatus

/** Only called for a fenced service onStopped callback, never to poll arbitrary recorder state.
 * The callback acknowledges Stop; MirrorHandoffGate separately proves every resource is free.
 */
internal object MirrorStoppedRecordingPolicy {
    fun stopCallbackAcknowledged(request: MirrorHandoffGate.Request, current: RecorderState): Boolean {
        if (request.stoppedSession == null || current.recordingSessionId != request.stoppedSession) return false
        return if (request.target == MirrorHandoffGate.Target.RECORD)
            current.status == RecorderStatus.STOPPED && !current.cleanupPending && !current.cleanupUnconfirmed && current.lastError == null
        else current.status in setOf(RecorderStatus.STOPPED, RecorderStatus.ERROR, RecorderStatus.CAMERA_UNAVAILABLE)
    }
}
