package com.dante.zeekrcapabilitylab.sentry.runtime

import com.dante.zeekrcapabilitylab.service.recorder.RecorderState
import kotlinx.serialization.Serializable

@Serializable
data class GuardNormalEvidence(
    val atEpochMs: Long,
    val status: String,
    val cameraId: String?,
    val lastError: String?,
    val message: String?,
    val storageKind: String,
    val segmentNumber: Int,
    val fileName: String?,
    val previewRequested: Boolean,
    val previewActive: Boolean,
    val previewFallbackUsed: Boolean,
    val wakeLockHeld: Boolean,
    val recordingMode: String? = null,
    val timeLapseMultiplier: Int? = null,
) {
    companion object {
        fun from(value: RecorderState, atEpochMs: Long) = GuardNormalEvidence(atEpochMs, value.status,
            value.cameraId, value.lastError?.take(500), value.message?.take(300), value.activeStorageKind.name,
            value.segmentNumber, value.currentFile?.substringAfterLast('/')?.takeLast(200),
            value.previewRequested, value.previewActive, value.previewFallbackUsed, value.wakeLockHeld,
            value.recordingMode.name, value.timeLapseMultiplier)
    }
}

internal object GuardNormalEvidencePolicy {
    fun observe(state: GuardState, value: RecorderState, atEpochMs: Long): GuardState {
        val next = GuardNormalEvidence.from(value, atEpochMs)
        val previous = state.normalEvidence
        val changed = previous == null || previous.copy(atEpochMs = 0) != next.copy(atEpochMs = 0)
        return state.copy(normalEvidence = next, normalTransitions =
            if (changed) (state.normalTransitions + next).takeLast(12) else state.normalTransitions)
    }
    fun stopReason(state: GuardState): String {
        val normal = state.normalEvidence
        return normal?.lastError?.takeIf { it.isNotBlank() }?.let { "NORMAL_${normal.status}: $it" }
            ?: "普通录像意外停止，请查看诊断后重新开始"
    }
}
