package com.dante.zeekrcapabilitylab.enhancement.field

import android.content.Context
import android.util.AtomicFile
import java.io.File
import kotlinx.serialization.json.*

/** AtomicFile requires mutual exclusion between its recovery reader and writer. */
internal object FieldReportIoLock

internal object FieldReportFiles {
    fun list(dir: File): List<File> = dir.listFiles()?.filter { it.isFile && (it.extension == "json" || it.name.endsWith(".json.bak")) }
        ?.map { if (it.name.endsWith(".bak")) File(it.path.removeSuffix(".bak")) else it }?.distinctBy { it.path }
        ?.sortedByDescending { maxOf(it.lastModified(), File(it.path + ".bak").lastModified()) }.orEmpty()
}

/** Accessed on the single report executor, never the UI thread. */
internal class FieldReportStore(context: Context) {
    private val base = context.noBackupFilesDir
    private val directory = File(base, "vehicle-field-tests")
    fun save(id: String, json: JsonObject): Int {
        check(id.matches(Regex("[0-9a-f-]{36}")))
        check(directory.isDirectory || directory.mkdirs())
        val file = AtomicFile(File(directory, "$id.json"))
        val out = file.startWrite()
        try { out.write(json.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(out) }
        catch (error: Throwable) { file.failWrite(out); throw error }
        files(directory).drop(4).forEach { AtomicFile(it).delete() }
        return files(directory).size
    }
    fun count(): Int = files(directory).size
    fun hasReport(): Boolean = count() > 0 || synchronized(FieldReportIoLock) {
        read(File(base, "multi-preview-last.json")) != null || files(File(base, "multi-preview-history")).isNotEmpty()
    }
    fun export(metadata: JsonObject): String = synchronized(FieldReportIoLock) {
        val sessions = files(directory).take(4).mapNotNull(::read)
        val archive = files(File(base, "multi-preview-history")).take(8).mapNotNull(::read)
        val latest = read(File(base, "multi-preview-last.json"))
        val cameras = (listOfNotNull(latest) + archive).distinctBy { it["run"] }.take(8)
        FieldReportBudget.combine(metadata, sessions, cameras)
    }
    private fun files(dir: File) = FieldReportFiles.list(dir)
    private fun read(file: File): JsonObject? = runCatching {
        val possible = listOf(file, File(file.path + ".bak"))
        if (possible.none { it.isFile && it.length() in 1..524_288 } || possible.any { it.length() > 524_288 }) return null
        Json.parseToJsonElement(AtomicFile(file).readFully().toString(Charsets.UTF_8)) as? JsonObject
    }.getOrNull()
}
