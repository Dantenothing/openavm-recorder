package com.dante.zeekrcapabilitylab.sentry.canary

import android.content.Context
import android.util.AtomicFile
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object CanaryEvidenceJson {
    val format = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }
    // Keep all measurements while spending less of the clipboard budget on indentation.
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    val copyFormat = Json(format) { prettyPrintIndent = "  " }
}

data class CanaryArchiveState(val records: Int = 0, val evicted: Long = 0, val error: String? = null)

/** Metadata only. All callers use a worker, never the codec callback or UI handler. */
object CanaryEvidenceStore {
    const val MAX_METADATA_BYTES = 2 * 1024 * 1024
    private val mutableState = MutableStateFlow(CanaryArchiveState())
    val state = mutableState.asStateFlow()
    private fun file(context: Context) = AtomicFile(File(context.filesDir, "sentry-canary/evidence.json"))

    @Synchronized fun read(context: Context): CanaryEvidenceArchive {
        val target = file(context)
        return try {
            val archive = if (!target.baseFile.exists()) CanaryEvidenceArchive() else {
                check(target.baseFile.length() <= MAX_METADATA_BYTES) { "EVIDENCE_FILE_TOO_LARGE" }
                target.openRead().use { CanaryEvidenceJson.format.decodeFromString<CanaryEvidenceArchive>(it.readBytes().toString(Charsets.UTF_8)) }
            }
            mutableState.value = CanaryArchiveState(archive.records.size, archive.evictedRecords)
            archive
        } catch (error: Exception) {
            mutableState.value = mutableState.value.copy(error = "EVIDENCE_READ_FAILED")
            throw error // Never replace a corrupt/unknown journal with an empty history.
        }
    }

    @Synchronized fun record(context: Context, record: CanaryEvidenceRecord) {
        var archive = read(context).append(record)
        var bytes = CanaryEvidenceJson.format.encodeToString(archive).toByteArray(Charsets.UTF_8)
        while (bytes.size > MAX_METADATA_BYTES && archive.records.size > 1) {
            archive = archive.copy(records = archive.records.drop(1), evictedRecords = archive.evictedRecords + 1)
            bytes = CanaryEvidenceJson.format.encodeToString(archive).toByteArray(Charsets.UTF_8)
        }
        check(bytes.size <= MAX_METADATA_BYTES) { "EVIDENCE_RECORD_TOO_LARGE" }
        val target = file(context)
        target.baseFile.parentFile?.mkdirs()
        var output: java.io.FileOutputStream? = null
        try {
            output = target.startWrite()
            output.write(bytes)
            output.fd.sync()
            target.finishWrite(output)
            syncCanaryDirectory(target.baseFile.parentFile!!)
            mutableState.value = CanaryArchiveState(archive.records.size, archive.evictedRecords)
        } catch (error: Exception) {
            output?.let { target.failWrite(it) }
            mutableState.value = mutableState.value.copy(error = "EVIDENCE_WRITE_FAILED")
            throw error
        }
    }
}
