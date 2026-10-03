package com.dante.zeekrcapabilitylab.product

import com.dante.zeekrcapabilitylab.service.recorder.RecorderLibrary
import java.io.File

/** A logical session can keep surround on USB and its cabin channels internally. */
data class UnifiedRecordingSession(val sessionId: String, val internal: RecorderLibrary.Recording, val usb: List<VehicleUsbRecording>) {
    val key: String get() = "joined:$sessionId"
    val files: List<File> get() = (internal.files + usb.filter { it.availability == MediaAvailability.ONLINE }.flatMap { it.files })
        .distinctBy { it.absolutePath }
    val startedAtEpochMs: Long get() = minOf(internal.startedAtEpochMs, usb.minOfOrNull { it.startedAtEpochMs } ?: Long.MAX_VALUE)
    val hasOfflineFiles: Boolean get() = usb.any { it.availability != MediaAvailability.ONLINE }
    companion object {
        fun group(internal: List<RecorderLibrary.Recording>, usb: List<VehicleUsbRecording>): List<UnifiedRecordingSession> =
            internal.filter { !it.isTimeLapse && it.sidecar.recordingSessionId != null }.mapNotNull { recording ->
                val session = recording.sidecar.recordingSessionId!!
                val removable = usb.filter { it.category == VehicleMediaCategory.NORMAL && it.containsDirectRecording &&
                    it.logicalId.removePrefix("session:") == session }
                removable.takeIf { it.isNotEmpty() }?.let { UnifiedRecordingSession(session, recording, it) }
            }
    }
}
