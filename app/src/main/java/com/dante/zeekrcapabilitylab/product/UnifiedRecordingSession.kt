package com.dante.zeekrcapabilitylab.product

import com.dante.zeekrcapabilitylab.service.recorder.RecorderLibrary
import com.dante.zeekrcapabilitylab.service.recorder.RecordingLayoutKind
import com.dante.zeekrcapabilitylab.service.recorder.RecordingSourceRole
import com.dante.zeekrcapabilitylab.player.RecordingPresentationPolicy
import java.io.File

data class SessionCoverSource(val file: File, val layoutKind: RecordingLayoutKind, val sourceRole: RecordingSourceRole) {
    val key: String get() = file.absolutePath + ":" + layoutKind.name
}

/** A logical session can keep surround on USB and its cabin channels internally. */
data class UnifiedRecordingSession(val sessionId: String, val internal: RecorderLibrary.Recording, val usb: List<VehicleUsbRecording>) {
    val key: String get() = "joined:$sessionId"
    val files: List<File> get() = (internal.files + usb.filter { it.availability == MediaAvailability.ONLINE }.flatMap { it.files })
        .distinctBy { it.absolutePath }
    val startedAtEpochMs: Long get() = minOf(internal.startedAtEpochMs, usb.minOfOrNull { it.startedAtEpochMs } ?: Long.MAX_VALUE)
    val stoppedAtEpochMs: Long get() = maxOf(internal.stoppedAtEpochMs, usb.maxOfOrNull { it.stoppedAtEpochMs } ?: 0L)
    val totalBytes: Long get() = internal.totalBytes + usb.sumOf { it.totalBytes }
    val sourceRoles: Set<RecordingSourceRole> get() = internal.segments.map { it.second.sourceRole }.toSet() + usb.map { it.sourceRole }
    /** Prefer surround just as the existing recording cards do; offline paths cannot be opened. */
    val coverSource: SessionCoverSource? get() = (internal.segments.map { (file, metadata) ->
        val presentation = RecordingPresentationPolicy.resolve(metadata)
        SessionCoverSource(file, presentation.layoutKind, presentation.sourceRole)
    } + usb.filter { it.availability == MediaAvailability.ONLINE }.mapNotNull { source ->
        source.files.firstOrNull()?.let { SessionCoverSource(it, source.layoutKind, source.sourceRole) }
    }).minByOrNull { when (it.sourceRole) { RecordingSourceRole.SURROUND -> 0; RecordingSourceRole.CABIN -> 1; RecordingSourceRole.IR -> 2 } }
    val initialFile: File? get() = coverSource?.file ?: files.firstOrNull()
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
