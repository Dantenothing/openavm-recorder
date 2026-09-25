package com.dante.zeekrcapabilitylab.service.recorder

import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * A single independent native closer. The hot writer never waits for this worker.
 * History is bounded; the one outstanding action (and its captured resources) is retained until
 * the owner explicitly confirms release. A timeout never interrupts or takes over native work.
 */
internal class RecordingFileFinalizer(
    private val now: () -> Long,
    private val historyLimit: Int = 16,
    private val admissionLimit: Int? = null,
    threadName: String = "recording-file-finalizer",
) {
    init { require(historyLimit in 1..64); require(admissionLimit == null || admissionLimit > 0) }
    data class Work(val file: Int, val acceptedAtMs: Long, val stage: String = "QUEUED",
        val stageAtMs: Long = acceptedAtMs, val completedAtMs: Long? = null,
        val failureType: String? = null, val nativeReleased: Boolean = false)
    data class Problem(val code: String, val stage: String, val file: Int,
        val startedAtMs: Long, val observedAtMs: Long)
    data class Snapshot(val observedAtMs: Long, val work: List<Work>, val problem: Problem?,
        val workerTerminated: Boolean, val acceptedActions: Long, val confirmedReleases: Long)
    private class OwnedWork(var evidence: Work, val released: () -> Boolean, val action: () -> Unit)

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, threadName) }
    private val history = ArrayDeque<Work>()
    private var active: OwnedWork? = null
    private var lastFile = -1
    private var accepted = 0L
    private var confirmed = 0L
    private var overrun: Problem? = null
    @Volatile var failure: Throwable? = null; private set

    @Synchronized fun submit(file: Int, releaseConfirmed: () -> Boolean, action: () -> Unit): Future<*> {
        failure?.let { throw it }
        check(!executor.isShutdown && (admissionLimit == null || accepted < admissionLimit)) { "FINALIZER_ADMISSION_CLOSED" }
        check(active == null) { "FINALIZER_BUSY" }
        check(problem() == null) { "FINALIZER_PREVIOUS_TIMEOUT" }
        // Only one monotonically increasing file identity is retained; no unbounded set of names.
        check(file > lastFile) { "FINALIZER_FILE_REUSED" }
        val owned = OwnedWork(Work(file, now()), releaseConfirmed, action)
        active = owned; lastFile = file; accepted++
        return executor.submit {
            try { stage(file, "MUXER_FINALIZE"); owned.action() }
            catch (error: Throwable) { failure = error }
            finally {
                val released = runCatching { owned.released() }.getOrDefault(false)
                if (!released && failure == null) failure = IllegalStateException("FINALIZER_NATIVE_RELEASE_UNCONFIRMED")
                complete(owned, released)
            }
        }
    }

    @Synchronized fun stage(file: Int, stage: String) {
        require(stage in setOf("MUXER_FINALIZE", "USB_SYNC_CLOSE"))
        val owned = requireNotNull(active)
        check(owned.evidence.file == file && owned.evidence.completedAtMs == null)
        val time = now()
        captureOverrun(owned.evidence, time)
        if (owned.evidence.stage == stage) return // A repeated progress notification cannot renew its deadline.
        check(owned.evidence.stage == "QUEUED" && stage == "MUXER_FINALIZE" ||
            owned.evidence.stage == "MUXER_FINALIZE" && stage == "USB_SYNC_CLOSE") { "FINALIZER_STAGE_REGRESSED" }
        owned.evidence = owned.evidence.copy(stage = stage, stageAtMs = time)
    }

    @Synchronized private fun complete(owned: OwnedWork, released: Boolean) {
        check(active === owned)
        val time = now()
        captureOverrun(owned.evidence, time)
        owned.evidence = owned.evidence.copy(completedAtMs = time,
            failureType = failure?.javaClass?.simpleName, nativeReleased = released)
        if (released) {
            confirmed++
            history.addLast(owned.evidence)
            while (history.size > historyLimit) history.removeFirst()
            active = null
        }
        // If release was not acknowledged, retain both callbacks and everything they capture.
    }

    private fun exceeded(work: Work, time: Long): Problem? {
        val limit = if (work.stage == "USB_SYNC_CLOSE") SYNC_TIMEOUT_MS else MUXER_TIMEOUT_MS
        return if (time - work.stageAtMs >= limit) Problem(
            if (work.stage == "USB_SYNC_CLOSE") "FINALIZER_SYNC_TIMEOUT" else "FINALIZER_MUXER_TIMEOUT",
            work.stage, work.file, work.stageAtMs, time) else null
    }
    private fun captureOverrun(work: Work, time: Long) { if (overrun == null) overrun = exceeded(work, time) }
    @Synchronized fun problem(): Problem? {
        active?.evidence?.takeIf { it.completedAtMs == null }?.let { captureOverrun(it, now()) }
        return overrun
    }
    @Synchronized fun snapshot() = Snapshot(now(), history.toList() + listOfNotNull(active?.evidence),
        problem(), executor.isTerminated, accepted, confirmed)

    fun finish(timeoutMs: Long = 10_000): Boolean {
        require(timeoutMs >= 0)
        synchronized(this) { executor.shutdown() } // Atomic with admission; never strand an accepted owner in a stopped queue.
        return executor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS) && terminated()
    }
    @Synchronized fun terminated() = executor.isTerminated && active == null

    companion object { const val MUXER_TIMEOUT_MS = 8_000L; const val SYNC_TIMEOUT_MS = 2_000L }
}
