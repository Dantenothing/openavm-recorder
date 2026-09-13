package com.dante.zeekrbridge.sound

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Naming/conflict plan for a same-name-safe WAV install. */
data class SoundBackupPlan(
    val finalName: String,
    val tempName: String,
    val backupName: String?,
    val conflict: Boolean,
)

object SoundBackupPlanner {
    fun plan(
        requestedName: String?,
        existingNames: Collection<String>,
        epoch: Long,
        fallbackName: String = "sound.wav",
    ): SoundBackupPlan {
        val finalName = SoundFileNames.wavFileName(requestedName, fallbackName)
        val base = finalName.substringBeforeLast('.')
        val lower = existingNames.map { it.lowercase() }.toSet()
        // A half-written temporary file must not look like a selectable vehicle sound.
        var tempName = "$base.installing-$epoch.wav.tmp"
        var n = 1
        while (tempName.lowercase() in lower) {
            tempName = "$base.installing-$epoch-${n++}.wav.tmp"
        }
        val conflict = finalName.lowercase() in lower
        var backupName: String? = null
        if (conflict) {
            // Keep rollback copies outside the vehicle's WAV scan set.
            var candidate = "$base.backup-$epoch.wav.bak"
            var bn = 1
            while (candidate.lowercase() in lower || candidate.lowercase() == tempName.lowercase()) {
                candidate = "$base.backup-$epoch-${bn++}.wav.bak"
            }
            backupName = candidate
        }
        return SoundBackupPlan(
            finalName = finalName,
            tempName = tempName,
            backupName = backupName,
            conflict = conflict,
        )
    }
}

data class SavedSound(
    val file: File,
    val backupName: String?,
)

/**
 * Local (JVM) WAV saver with same-name backup semantics:
 * existing file is copied to [backupDir] before replacement, writes go through
 * a temp file, and any failure restores the original and deletes the backup.
 */
class LocalWavSaver(
    private val dir: File,
    private val backupDir: File,
) {
    fun save(
        source: File,
        requestedName: String,
        verify: (File) -> Boolean = { WavPcmValidator.validateWavFile(it).valid },
    ): SavedSound {
        if (!source.isFile || source.length() == 0L) {
            throw SoundIoException("待保存文件不存在或为空", "SOURCE_MISSING")
        }
        val epoch = System.currentTimeMillis()
        val existing = dir.listFiles()?.map { it.name }?.toList() ?: emptyList()
        val plan = SoundBackupPlanner.plan(requestedName, existing, epoch)
        dir.mkdirs()
        backupDir.mkdirs()
        val final = File(dir, plan.finalName)
        val temp = File(dir, plan.tempName)
        var backup: File? = null
        try {
            if (plan.conflict && final.isFile) {
                backup = File(backupDir, "$epoch-${plan.finalName}")
                final.copyTo(backup, overwrite = true)
                if (!backup.isFile || backup.length() != final.length()) {
                    throw SoundIoException("同名文件备份失败，未覆盖原文件", "USB_BACKUP_FAILED")
                }
            }
            source.copyTo(temp, overwrite = true)
            if (!verify(temp)) {
                throw SoundIoException("写入后校验失败", "VERIFY_FAILED")
            }
            Files.move(temp.toPath(), final.toPath(), StandardCopyOption.REPLACE_EXISTING)
            if (!verify(final)) {
                throw SoundIoException("最终校验失败", "VERIFY_FAILED")
            }
            return SavedSound(final, backup?.name)
        } catch (t: Throwable) {
            runCatching { temp.delete() }
            val backupFile = backup
            if (backupFile != null && backupFile.isFile) {
                runCatching { backupFile.copyTo(final, overwrite = true) }
                runCatching { backupFile.delete() }
            }
            throw t
        }
    }
}
