package com.dante.zeekrbridge.core

import android.content.Context
import com.dante.zeekrbridge.service.MediaExportService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class MediaExportState { QUEUED, RUNNING, CANCELLING, COMPLETED, FAILED, CANCELLED }

data class MediaExportJob(
    val id: String,
    val outputName: String,
    val plan: MediaExportPlan,
    val state: MediaExportState = MediaExportState.QUEUED,
    val progressPercent: Int = 0,
    val message: String? = null,
    val outputUri: String? = null,
    val outputPath: String? = null,
)

object MediaExportQueue {
    private val _jobs = MutableStateFlow<List<MediaExportJob>>(emptyList())
    val jobs: StateFlow<List<MediaExportJob>> = _jobs.asStateFlow()

    @Synchronized
    fun enqueue(
        context: Context,
        segments: List<IndexedMediaSegment>,
        targets: Collection<MediaExportTarget>,
        trimStartMs: Long,
        trimEndMs: Long,
    ): List<String> {
        require(targets.isNotEmpty()) { "Select at least one output" }
        val orderedTargets = MediaExportTarget.entries.filter(targets::contains)
        val startEpochMs = segments.minOf { it.startedAtEpochMs }
        val additions = orderedTargets.map { target ->
            val plan = MediaExportPlanner.build(segments, target, trimStartMs, trimEndMs)
            MediaExportJob(
                id = UUID.randomUUID().toString(),
                outputName = outputName(startEpochMs, plan),
                plan = plan,
            )
        }
        _jobs.value = (_jobs.value + additions).takeLast(MAX_VISIBLE_JOBS)
        MediaExportService.start(context.applicationContext)
        return additions.map { it.id }
    }

    @Synchronized
    fun requestCancel(context: Context, id: String) {
        val job = _jobs.value.firstOrNull { it.id == id } ?: return
        when (job.state) {
            MediaExportState.QUEUED -> update(id) { it.copy(state = MediaExportState.CANCELLED, message = "Cancelled") }
            MediaExportState.RUNNING -> {
                update(id) { it.copy(state = MediaExportState.CANCELLING, message = "Cancelling") }
                MediaExportService.cancel(context.applicationContext, id)
            }
            else -> Unit
        }
    }

    @Synchronized
    fun dismiss(id: String) {
        _jobs.value = _jobs.value.filterNot { it.id == id && it.state.isTerminal }
    }

    @Synchronized
    fun retry(context: Context, id: String) {
        val job = _jobs.value.firstOrNull { it.id == id } ?: return
        if (job.state !in setOf(MediaExportState.FAILED, MediaExportState.CANCELLED)) return
        update(id) {
            it.copy(
                state = MediaExportState.QUEUED,
                progressPercent = 0,
                message = null,
                outputUri = null,
                outputPath = null,
            )
        }
        MediaExportService.start(context.applicationContext)
    }

    @Synchronized
    internal fun nextQueued(): MediaExportJob? = _jobs.value.firstOrNull { it.state == MediaExportState.QUEUED }

    @Synchronized
    internal fun markRunning(id: String) = update(id) {
        it.copy(state = MediaExportState.RUNNING, progressPercent = 0, message = null)
    }

    @Synchronized
    internal fun markProgress(id: String, percent: Int) = update(id) {
        if (it.state == MediaExportState.RUNNING) it.copy(progressPercent = percent.coerceIn(0, 99)) else it
    }

    @Synchronized
    internal fun markCompleted(id: String, uri: String?, path: String?) = update(id) {
        it.copy(
            state = MediaExportState.COMPLETED,
            progressPercent = 100,
            message = null,
            outputUri = uri,
            outputPath = path,
        )
    }

    @Synchronized
    internal fun markFailed(id: String, message: String) = update(id) {
        it.copy(state = MediaExportState.FAILED, message = message.take(240))
    }

    @Synchronized
    internal fun markCancelled(id: String) = update(id) {
        it.copy(state = MediaExportState.CANCELLED, message = "Cancelled")
    }

    private fun update(id: String, transform: (MediaExportJob) -> MediaExportJob) {
        _jobs.value = _jobs.value.map { if (it.id == id) transform(it) else it }
    }

    private fun outputName(startEpochMs: Long, plan: MediaExportPlan): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(startEpochMs))
        val suffix = when {
            plan.target != MediaExportTarget.ORIGINAL -> plan.target.fileSuffix
            plan.sourceRole == IndexedSourceRole.CABIN -> "Cabin"
            plan.sourceRole == IndexedSourceRole.IR -> "IR"
            else -> "360"
        }
        val mode = if (plan.recordingMode == IndexedRecordingMode.TIME_LAPSE) {
            "_TL${plan.timeLapseMultiplier}x"
        } else {
            ""
        }
        return "OpenAVM_${timestamp}_${suffix}$mode.mp4"
    }

    private val MediaExportState.isTerminal: Boolean
        get() = this in setOf(MediaExportState.COMPLETED, MediaExportState.FAILED, MediaExportState.CANCELLED)

    private const val MAX_VISIBLE_JOBS = 24
}
