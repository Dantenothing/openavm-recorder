package com.dante.zeekrcapabilitylab.sentry.runtime

import android.content.Context
import android.util.AtomicFile
import java.io.File
import kotlinx.serialization.encodeToString

/** Small run reports only. Existing video/event directories are never traversed or changed. */
internal class GuardReportStore(context: Context) {
    private val root = File(context.filesDir, "sentry")
    private val history = File(root, "run-history")
    val currentFile = File(root, "guard-report.json")

    fun current(): GuardState? = synchronized(IO_LOCK) { read(currentFile) }
    fun writeCurrent(state: GuardState, isCurrent: () -> Boolean = { true }) {
        synchronized(IO_LOCK) {
            // Evaluate after acquiring the lock: an old Service can finish IO after a new run starts.
            if (isCurrent()) write(currentFile, state)
        }
    }
    fun preservePrevious(nextRunId: String) {
        synchronized(IO_LOCK) {
            current()?.takeIf { it.runId != nextRunId }?.let(::archive)
        }
    }

    fun archive(state: GuardState) {
        synchronized(IO_LOCK) {
            if (!state.runId.matches(RUN_ID)) return
            write(File(history, state.runId + ".json"), state)
            // Exact owned files only; unknown files remain untouched.
            ownedFiles().sortedByDescending { it.lastModified() }.drop(6).forEach { file ->
                AtomicFile(file).delete()
            }
        }
    }

    fun recent(excludingRunId: String): List<GuardState> = synchronized(IO_LOCK) {
        ownedFiles().mapNotNull(::read).filter { it.runId != excludingRunId }
            .sortedByDescending { it.updatedAtEpochMs }.distinctBy { it.runId }.take(3)
    }

    private fun ownedFiles(): List<File> = history.listFiles().orEmpty().filter {
        it.isFile && it.name.endsWith(".json") && it.name.removeSuffix(".json").matches(RUN_ID)
    }

    private fun read(file: File): GuardState? = runCatching {
        AtomicFile(file).openRead().use { input ->
            require(input.channel.size() in 1..MAX_BYTES.toLong())
            GuardEventStore.json.decodeFromString<GuardState>(input.bufferedReader().readText())
        }
    }.getOrNull()

    private fun write(file: File, state: GuardState) {
        val data = GuardEventStore.json.encodeToString(state).toByteArray(Charsets.UTF_8)
        require(data.size <= MAX_BYTES)
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try { output.write(data); atomic.finishWrite(output) }
        catch (t: Throwable) { atomic.failWrite(output); throw t }
    }

    companion object {
        // AtomicFile does not lock; readers can otherwise delete a writer's in-progress .new file.
        // Shared across Store instances, old/new Service executors, restore and diagnostic export.
        private val IO_LOCK = Any()
        private const val MAX_BYTES = 512 * 1024
        private val RUN_ID = Regex("[a-f0-9-]{36}")
    }
}
