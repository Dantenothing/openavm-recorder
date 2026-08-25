package com.dante.zeekrcapabilitylab.service.recorder

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class SegmentJournalStage {
    CAPTURE_PENDING,
    CAPTURING,
    ENCODER_STOPPED,
    MEDIA_PROMOTED,
    SIDECAR_DURABLE,
    QUARANTINED,
}

@Serializable
data class SegmentJournal(
    val schemaVersion: Int = 1,
    val segmentId: String,
    val partialPath: String,
    val finalPath: String,
    val sidecarPath: String,
    val createdAtEpochMs: Long,
    val stage: SegmentJournalStage,
)

data class SegmentRecoveryReport(
    val completedJournals: Int = 0,
    val quarantinedPartials: Int = 0,
    val quarantinedOrphans: Int = 0,
    val errors: List<String> = emptyList(),
)

object SegmentJournalIO {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    const val SUFFIX = ".segment-journal.json"

    fun begin(journalsDir: File, partial: File, finalFile: File, nowEpochMs: Long): File {
        journalsDir.mkdirs()
        val id = partial.name.removeSuffix(SegmentNaming.PARTIAL_SUFFIX)
        val journalFile = File(journalsDir, "$id$SUFFIX")
        writeDurable(
            journalFile,
            SegmentJournal(
                segmentId = id,
                partialPath = partial.absolutePath,
                finalPath = finalFile.absolutePath,
                sidecarPath = SegmentSidecarIO.sidecarFileFor(finalFile).absolutePath,
                createdAtEpochMs = nowEpochMs,
                stage = SegmentJournalStage.CAPTURE_PENDING,
            ),
        )
        return journalFile
    }

    fun update(journalFile: File, stage: SegmentJournalStage) {
        val current = read(journalFile) ?: return
        writeDurable(journalFile, current.copy(stage = stage))
    }

    fun complete(journalFile: File) {
        update(journalFile, SegmentJournalStage.SIDECAR_DURABLE)
        if (!journalFile.delete() && journalFile.exists()) {
            throw IllegalStateException("JOURNAL_DELETE_FAILED ${journalFile.name}")
        }
    }

    fun promote(partial: File, finalFile: File): Boolean {
        if (!partial.isFile || partial.length() <= 0L) return false
        return try {
            Files.move(
                partial.toPath(),
                finalFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            true
        } catch (_: AtomicMoveNotSupportedException) {
            runCatching {
                Files.move(partial.toPath(), finalFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                true
            }.getOrDefault(false)
        } catch (_: Throwable) {
            false
        }
    }

    fun read(file: File): SegmentJournal? = runCatching {
        json.decodeFromString(SegmentJournal.serializer(), file.readText())
    }.getOrNull()

    private fun writeDurable(file: File, journal: SegmentJournal) {
        val tmp = File(file.absolutePath + ".tmp")
        val bytes = json.encodeToString(SegmentJournal.serializer(), journal).toByteArray(Charsets.UTF_8)
        FileOutputStream(tmp).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
        if (!promoteSmallFile(tmp, file)) throw IllegalStateException("JOURNAL_WRITE_FAILED ${file.name}")
    }

    private fun promoteSmallFile(tmp: File, target: File): Boolean = try {
        Files.move(
            tmp.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        true
    } catch (_: AtomicMoveNotSupportedException) {
        runCatching {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            true
        }.getOrDefault(false)
    } catch (_: Throwable) {
        false
    }
}

object SegmentRecovery {
    fun reconcile(segmentsDir: File, quarantineDir: File, journalsDir: File): SegmentRecoveryReport {
        quarantineDir.mkdirs()
        var completed = 0
        var partials = 0
        var orphans = 0
        val errors = mutableListOf<String>()
        journalsDir.listFiles()
            ?.filter { it.name.endsWith(".tmp") }
            .orEmpty()
            .forEach { if (moveToQuarantine(it, quarantineDir)) partials++ }
        journalsDir.listFiles()
            ?.filter { it.name.endsWith(SegmentJournalIO.SUFFIX) }
            .orEmpty()
            .forEach { journalFile ->
                val journal = SegmentJournalIO.read(journalFile)
                if (journal == null) {
                    errors += "CORRUPT_JOURNAL ${journalFile.name}"
                    return@forEach
                }
                val partial = File(journal.partialPath)
                val finalFile = File(journal.finalPath)
                val sidecar = File(journal.sidecarPath)
                when {
                    finalFile.isFile && sidecar.isFile && RecorderLibrary.isManaged(finalFile) -> {
                        if (journalFile.delete()) completed++ else errors += "JOURNAL_DELETE_FAILED ${journalFile.name}"
                    }
                    finalFile.exists() -> {
                        if (moveToQuarantine(finalFile, quarantineDir)) orphans++
                        else errors += "ORPHAN_QUARANTINE_FAILED ${finalFile.name}"
                        sidecar.takeIf { it.exists() }?.let { moveToQuarantine(it, quarantineDir) }
                        journalFile.delete()
                    }
                    partial.exists() -> {
                        if (moveToQuarantine(partial, quarantineDir)) partials++
                        else errors += "PARTIAL_QUARANTINE_FAILED ${partial.name}"
                        journalFile.delete()
                    }
                    else -> journalFile.delete()
                }
            }

        segmentsDir.listFiles().orEmpty().forEach { file ->
            when {
                SegmentNaming.isPartial(file.name) -> {
                    if (moveToQuarantine(file, quarantineDir)) partials++
                }
                SegmentNaming.isFinalMp4(file.name) && !SegmentSidecarIO.sidecarFileFor(file).isFile -> {
                    if (moveToQuarantine(file, quarantineDir)) orphans++
                }
                file.name.endsWith(SegmentNaming.SIDECAR_SUFFIX) -> {
                    val media = File(file.absolutePath.removeSuffix(SegmentNaming.SIDECAR_SUFFIX))
                    if (!media.isFile && moveToQuarantine(file, quarantineDir)) orphans++
                }
                file.name.endsWith(".tmp") -> {
                    if (moveToQuarantine(file, quarantineDir)) partials++
                }
            }
        }
        return SegmentRecoveryReport(completed, partials, orphans, errors)
    }

    private fun moveToQuarantine(file: File, quarantineDir: File): Boolean {
        val target = File(quarantineDir, file.name)
        return runCatching {
            Files.move(file.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            true
        }.getOrDefault(false)
    }
}
