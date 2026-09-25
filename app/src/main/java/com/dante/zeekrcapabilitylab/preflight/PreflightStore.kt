package com.dante.zeekrcapabilitylab.preflight

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.util.AtomicFile
import java.io.File
import kotlinx.serialization.json.*
import com.dante.zeekrcapabilitylab.preflight.cloud.DiagnosticPolicy

internal class PreflightStore(private val context: Context) {
    private val dir = File(context.filesDir, "preflight").apply { mkdirs() }
    private val report = File(dir, "latest.json")
    private val barrier = File(dir, "active.json")
    val bootCount: Int get() = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1) }.getOrDefault(-1)
    @Synchronized fun read(name: String = "latest.json"): JsonObject? = runCatching {
        AtomicFile(File(dir, name)).openRead().use { input ->
            require(input.channel.size() in 1..(4 * 1024 * 1024))
            Json.parseToJsonElement(input.bufferedReader().readText()).jsonObject
        }
    }.getOrNull()
    @Synchronized fun write(name: String, value: JsonObject) {
        require(name.matches(Regex("[a-zA-Z0-9_.-]+\\.json")))
        val bytes = value.toString().toByteArray()
        require(bytes.size <= 4 * 1024 * 1024)
        val file = AtomicFile(File(dir, name)); val out = file.startWrite()
        try { out.write(bytes); file.finishWrite(out) } catch (t: Throwable) { file.failWrite(out); throw t }
    }
    @Synchronized fun save(value: JsonObject) { PreflightReport.validate(value); write("latest.json", value) }
    @Synchronized fun archivePrevious() {
        // Preserve both generations before previous.json is replaced. Beta17 validation is needed
        // to retire its exact retained synthetic files without erasing Beta18's failed evidence.
        for(value in listOfNotNull(read("previous.json"),read())) {
            val run=(value["runId"] as? JsonPrimitive)?.content ?: continue
            require(run.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
            val name="report-$run.json"
            if(read(name)==null) {
                check(dir.listFiles().orEmpty().count { it.name.startsWith("report-") && it.extension=="json" }<64) { "DIAGNOSTIC_REPORT_ARCHIVE_LIMIT" }
                write(name,value)
            }
        }
        read()?.let { write("previous.json", it) }
    }
    @Synchronized fun blockingReason(): String? {
        return PreflightPlan.previousRunBlocks(barrier.exists() || File(dir,"active.json.bak").exists(),read("active.json"),bootCount)
    }
    @Synchronized fun checkpoint(run: String, stage: String, cleanup: String = "PENDING") {
        write("active.json", obj("runId" to run, "stage" to stage, "bootCount" to bootCount,
            "atElapsedMs" to SystemClock.elapsedRealtime(), "atEpochMs" to System.currentTimeMillis(), "cleanup" to cleanup))
    }
    @Synchronized fun exportShort(): String {
        val bytes = AtomicFile(report).readFully()
        return PreflightReport.short(bytes)
    }
    @Synchronized fun fullBytes(): ByteArray = AtomicFile(report).readFully()
    /** On a write failure never silently copy the preceding successful run. */
    fun latestExport(): Pair<ByteArray,Boolean> {
        val memory=PreflightRuntime.report?.toString()?.toByteArray()
        val saved=runCatching { fullBytes() }.getOrNull()
        val bytes=memory ?: requireNotNull(saved) { "NO_REPORT" }
        val expected=DiagnosticPolicy.expectedRun(File(dir,"latest-attempt.json").exists() ||
            File(dir,"latest-attempt.json.bak").exists(), read("latest-attempt.json"))
        DiagnosticPolicy.identity(bytes,expected)
        return bytes to (saved?.contentEquals(bytes)==true)
    }
}
