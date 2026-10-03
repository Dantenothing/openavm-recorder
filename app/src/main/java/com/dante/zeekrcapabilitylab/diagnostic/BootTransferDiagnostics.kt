package com.dante.zeekrcapabilitylab.diagnostic

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.util.AtomicFile
import com.dante.zeekrcapabilitylab.ZeekrApp
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.*

/** Passive bounded checkpoints. No periodic polling, networking, wake locks or recorder ownership. */
internal object BootTransferDiagnostics {
    private data class Pending(val elapsed: Long, val uptime: Long, val epoch: Long,
        val ordinal: Int, val source: TransferDiagnosticSource, val phase: TransferDiagnosticPhase)
    private val io = Executors.newSingleThreadScheduledExecutor { Thread(it, "boot-transfer-evidence").apply { isDaemon = true } }
    private val pending = ArrayBlockingQueue<Pending>(64)
    private val initialized = AtomicBoolean()
    private val scheduled = AtomicBoolean()
    private val dropped = AtomicInteger()
    private val taskOrdinals = LinkedHashMap<String, Int>()
    private var nextOrdinal = 0
    @Volatile private var journal = BootTransferJournal()
    @Volatile private var readStatus = "NOT_INITIALIZED"
    @Volatile private var writeStatus = "NOT_ATTEMPTED"
    private var bootCount: Int? = null
    private var target: AtomicFile? = null

    /** Called before transfer queue restoration. Disk and Settings provider work stay on diagnostics IO. */
    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        val app = context.applicationContext
        val uptime = SystemClock.uptimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        val epoch = System.currentTimeMillis()
        io.execute {
            runCatching {
                val file = AtomicFile(File(app.filesDir, "diagnostics/boot-transfer.json"))
                target = file
                val previous = runCatching {
                    file.openRead().use { input ->
                        require(input.channel.size() in 1..BootTransferEvidence.MAX_BYTES.toLong())
                        BootTransferEvidence.decode(input.readBytes()) ?: error("INVALID_CHECKPOINT")
                    }
                }.getOrElse {
                    readStatus = if (file.baseFile.exists()) "UNREADABLE_OR_INVALID" else "NO_PREVIOUS_CHECKPOINT"
                    BootTransferJournal()
                }
                if (readStatus == "NOT_INITIALIZED") readStatus = "AVAILABLE"
                bootCount = runCatching { Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT, -1) }
                    .getOrNull()?.takeIf { it >= 0 }
                journal = BootTransferEvidence.start(previous, observation(epoch, elapsed, uptime),
                    previousUnavailable = readStatus == "UNREADABLE_OR_INVALID")
                save()
            }.onFailure { readStatus = "INITIALIZATION_FAILED" }
        }
    }

    /** The task identifier is held in a 64-entry memory-only map, never in the file or report. */
    fun transfer(taskId: String, source: TransferDiagnosticSource, phase: TransferDiagnosticPhase) {
        if (!initialized.get()) return
        runCatching {
            val ordinal = synchronized(taskOrdinals) {
                taskOrdinals[taskId] ?: (++nextOrdinal).also {
                    taskOrdinals[taskId] = it
                    if (taskOrdinals.size > 64) taskOrdinals.remove(taskOrdinals.keys.first())
                }
            }
            val uptime = SystemClock.uptimeMillis()
            val elapsed = SystemClock.elapsedRealtime()
            if (!pending.offer(Pending(elapsed, uptime, System.currentTimeMillis(), ordinal, source, phase))) dropped.incrementAndGet()
            schedule()
        }
    }

    private fun observation(epoch: Long, elapsed: Long, uptime: Long) =
        BootObservation(epoch, elapsed, uptime, bootCount, ZeekrApp.processStartId)

    private fun schedule() {
        if (scheduled.compareAndSet(false, true)) io.schedule({
            try { drain() } finally {
                scheduled.set(false)
                if (pending.isNotEmpty()) schedule()
            }
        }, 100, TimeUnit.MILLISECONDS)
    }

    private fun drain() {
        var changed = false
        while (true) {
            val item = pending.poll() ?: break
            val next = runCatching { BootTransferEvidence.boundary(journal, TransferBoundaryEvidence(
                observation(item.epoch, item.elapsed, item.uptime), item.ordinal, item.source, item.phase)) }.getOrNull()
            if (next != null && next !== journal) { journal = next; changed = true }
        }
        if (changed) save()
    }

    private fun save() {
        runCatching {
            val file = target ?: return
            val bytes = BootTransferEvidence.encode(journal)
            file.baseFile.parentFile?.mkdirs()
            val output = file.startWrite()
            try { output.write(bytes); file.finishWrite(output) }
            catch (error: Throwable) { file.failWrite(output); throw error }
        }.onSuccess { writeStatus = "SAVED" }.onFailure { writeStatus = "WRITE_FAILED" }
    }

    /** Called on report IO. A timed-out writer does not block the report or invent missing outcomes. */
    fun collect(): JsonObject {
        val flushed = initialized.get() && runCatching { io.submit { drain() }.get(2, TimeUnit.SECONDS); true }.getOrDefault(false)
        val snapshot = journal
        return buildJsonObject {
            put("schemaVersion", 1)
            put("readStatus", readStatus); put("writeStatus", writeStatus)
            put("checkpointFlushConfirmed", flushed); put("droppedBoundaryCount", dropped.get())
            put("classification", snapshot.starts.lastOrNull()?.assessment?.classification?.name ?: "UNKNOWN")
            put("reason", snapshot.starts.lastOrNull()?.assessment?.reason?.name ?: "NO_CHECKPOINT")
            put("rebootCause", "NOT_DETERMINED")
            put("starts", Json.encodeToJsonElement(snapshot.starts))
            put("transferBoundaries", Json.encodeToJsonElement(snapshot.transfers))
            put("transferOutcomeScope", "RECORDED_BOUNDARIES_ONLY_MISSING_TERMINAL_IS_NOT_PROOF_OF_CRASH")
            put("overlayEvidenceReferences", buildJsonArray {
                add("current.mirror"); add("OPENAVM_AWAY_SHORT_JSON.mirrorSleep")
            })
            put("pathsCoordinatesAccountsOrTransferIdsIncluded", false)
        }
    }
}
