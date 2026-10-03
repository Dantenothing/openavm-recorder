package com.dante.zeekrbridge.core

import io.github.dantenothing.avmtransfer.protocol.ContinuousSegmentTimeline
import io.github.dantenothing.avmtransfer.protocol.SessionPlaybackTimeline
import io.github.dantenothing.avmtransfer.protocol.SessionVideoSegment
import io.github.dantenothing.avmtransfer.protocol.VideoClockAnchor
import io.github.dantenothing.avmtransfer.protocol.TransferProtocol

/** In-memory selection evidence, never written to exported media or diagnostic reports. */
data class SessionExportBinding(
    val sessionId: String, val channelId: String, val startedElapsedMs: Long, val stoppedElapsedMs: Long,
    val sessionStartedElapsedMs: Long?, val anchor: VideoClockAnchor?, val continuous: ContinuousSegmentTimeline?,
    val sizeBytes: Long, val modifiedMs: Long, val sourceRole: IndexedSourceRole, val layout: IndexedLayoutKind,
    val originalWidth: Int?, val originalHeight: Int?,
) {
    internal fun requireMatches(current: IndexedMediaSegment) {
        require(current.recordingMode == IndexedRecordingMode.NORMAL && current.timeLapseMultiplier == 1 &&
            current.recordingSessionId == sessionId && current.playbackChannelId == channelId &&
            current.startedElapsedMs == startedElapsedMs && current.stoppedElapsedMs == stoppedElapsedMs &&
            current.sessionStartedElapsedMs == sessionStartedElapsedMs && current.videoClockAnchor == anchor &&
            current.continuousTimeline == continuous && current.sizeBytes == sizeBytes && current.file.length() == sizeBytes &&
            current.file.lastModified() == modifiedMs && current.sourceRole == sourceRole && current.layoutKind == layout &&
            current.originalWidth == originalWidth && current.originalHeight == originalHeight) { "SESSION_EXPORT_SOURCE_CHANGED" }
    }

    companion object {
        internal fun capture(item: IndexedMediaSegment, window: SessionVideoSegment): SessionExportBinding {
            require(window.valid() && window.recordingSessionId.length in 1..128 &&
                item.sizeBytes in 1..TransferProtocol.MAX_FILE_BYTES &&
                item.recordingMode == IndexedRecordingMode.NORMAL && item.timeLapseMultiplier == 1 &&
                item.recordingSessionId == window.recordingSessionId && item.playbackChannelId == window.channelId &&
                SessionPlaybackTimeline.channelForRole(item.sourceRole.name) == window.channelId &&
                item.startedElapsedMs == window.startElapsedMs && item.stoppedElapsedMs == window.endElapsedMs &&
                item.videoClockAnchor == window.anchor && item.continuousTimeline == window.continuousTimeline &&
                item.file.isFile && item.file.length() == item.sizeBytes) { "SESSION_EXPORT_SOURCE_CHANGED" }
            return SessionExportBinding(window.recordingSessionId, window.channelId, window.startElapsedMs, window.endElapsedMs,
                item.sessionStartedElapsedMs, window.anchor, window.continuousTimeline, item.sizeBytes, item.file.lastModified(),
                item.sourceRole, item.layoutKind, item.originalWidth, item.originalHeight)
        }
    }
}
