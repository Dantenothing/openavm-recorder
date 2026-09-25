package com.dante.zeekrcapabilitylab.preflight.continuous

import com.dante.zeekrcapabilitylab.service.recorder.RecordingFileFinalizer

/** One closing file, no backlog. Completion of this worker is not native-release acknowledgement. */
internal class ProbeFileFinalizer(now:()->Long) {
    // Preserve the diagnostic four-file budget; production uses bounded history without this cap.
    private val delegate = RecordingFileFinalizer(now, historyLimit = 4, admissionLimit = 4, threadName = "p2-file-finalizer")
    val failure get() = delegate.failure
    fun submit(file: Int, releaseConfirmed: () -> Boolean, action: () -> Unit) = delegate.submit(file, releaseConfirmed, action)
    fun stage(file: Int, stage: String) = delegate.stage(file, stage)
    fun problem() = delegate.problem()
    fun snapshot() = delegate.snapshot()
    fun finish(timeoutMs: Long = 10_000) = delegate.finish(timeoutMs)
    fun terminated() = delegate.terminated()
    companion object {
        const val MUXER_TIMEOUT_MS = RecordingFileFinalizer.MUXER_TIMEOUT_MS
        const val SYNC_TIMEOUT_MS = RecordingFileFinalizer.SYNC_TIMEOUT_MS
    }
}
