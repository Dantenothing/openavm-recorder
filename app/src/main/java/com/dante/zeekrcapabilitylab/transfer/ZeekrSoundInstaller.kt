package com.dante.zeekrcapabilitylab.transfer

import com.dante.zeekrcapabilitylab.usbexport.UsbExportTarget
import com.dante.zeekrcapabilitylab.usbexport.UsbMutationCoordinator
import io.github.dantenothing.avmtransfer.protocol.SoundTransferProtocol
import io.github.dantenothing.avmtransfer.protocol.SoundTransferValidation
import java.io.File

internal data class InstallResult(val ok: Boolean, val directories: List<SoundDirectoryObservation>, val errorCode: String? = null, val message: String)

internal object ZeekrSoundInstaller {
    fun install(
        task: SoundRelayTask,
        target: UsbExportTarget,
        payload: File,
        allowReplace: Boolean = true,
        progress: (List<SoundDirectoryObservation>) -> Unit,
    ): InstallResult = UsbMutationCoordinator.withTarget(target.storageUuid) {
        installLocked(task, target, payload, allowReplace, progress)
    }

    private fun installLocked(
        task: SoundRelayTask,
        target: UsbExportTarget,
        payload: File,
        allowReplace: Boolean,
        progress: (List<SoundDirectoryObservation>) -> Unit,
    ): InstallResult {
        val rootPath = target.directoryPath ?: return InstallResult(false, emptyList(), "USB_RAW_ROOT_UNAVAILABLE", "USB raw root unavailable")
        val root = File(rootPath).canonicalFile
        if (!root.isDirectory || !root.canWrite()) return InstallResult(false, emptyList(), "USB_NOT_WRITABLE", "USB is not writable")
        if (!payload.isFile || payload.length() != task.offer.sizeBytes || PhoneSoundRelay.sha256(payload) != task.offer.sha256)
            return InstallResult(false, emptyList(), "PAYLOAD_CHANGED", "The prepared WAV changed; prepare it again")
        val name = SoundTransferValidation.cleanWavFileName(task.offer.fileName)
            ?: return InstallResult(false, emptyList(), "INVALID_FILE_NAME", "Unsafe WAV name")
        val dirs = SoundTransferProtocol.TARGET_DIRECTORIES.map { File(root, it).canonicalFile }
        if (dirs.any { it.parentFile != root }) return InstallResult(false, emptyList(), "PATH_ESCAPE", "Sound directory escaped USB root")
        val persisted = task.directories.associateBy { it.directoryName }
        val states = dirs.map { dir ->
            persisted[dir.name] ?: SoundDirectoryObservation(
                directoryName = dir.name, finalPath = File(dir, name).absolutePath, existedBefore = File(dir, name).isFile,
            )
        }.toMutableList()
        val op = task.operationId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        // Validate both destinations before changing either folder. Local edits never replace
        // a different sound; retry may reuse the exact content written by the same operation.
        dirs.forEach { dir ->
            if (dir.exists() && !dir.isDirectory)
                return InstallResult(false, states, "NOT_A_DIRECTORY", "/${dir.name}/ is not a directory")
            val final = File(dir, name)
            if (listOf(final, File(dir, ".openavm-$op.tmp"), File(dir, ".openavm-$op.bak")).any { it.canonicalFile.parentFile != dir })
                return InstallResult(false, states, "PATH_ESCAPE", "Sound file escaped its directory")
            if (final.exists() && !final.isFile)
                return InstallResult(false, states, "FILE_NAME_CONFLICT", "The selected name belongs to a directory")
            if (!allowReplace && final.isFile && PhoneSoundRelay.sha256(final) != task.offer.sha256)
                return InstallResult(false, states, "FILE_NAME_CONFLICT", "An existing sound has this name; it was preserved")
            if (!final.exists() && dir.listFiles { f -> f.isFile && f.extension.equals("wav", true) }.orEmpty().size >= 5)
                return InstallResult(false, states, "SOUND_FOLDER_FULL", "/${dir.name}/ already contains 5 WAV files")
        }
        try {
            dirs.forEachIndexed { index, dir ->
                if (!dir.exists() && !dir.mkdir()) error("Cannot create /${dir.name}/")
                val final = File(dir, name)
                val temp = File(dir, ".openavm-$op.tmp")
                val backup = File(dir, ".openavm-$op.bak")
                // A prior process may have stopped between backup and the two-directory commit.
                // Always roll that operation back before beginning the same operation again.
                if (backup.isFile) {
                    final.delete()
                    if (!backup.renameTo(final)) PhoneSoundRelay.copyVerified(backup, final, PhoneSoundRelay.sha256(backup))
                    if (backup.exists() && !backup.delete()) error("Cannot finish previous backup recovery")
                } else if ((states[index].finalVerified || states[index].finalWriteStarted) && !states[index].existedBefore && final.isFile) {
                    if (!final.delete()) error("Cannot roll back previous partial install")
                }
                if (temp.exists() && !temp.delete()) error("Cannot clean previous transaction temp")
                states[index] = states[index].copy(
                    tempWritten = false, backupWritten = false, finalWriteStarted = false, finalVerified = false, rollbackCompleted = false, error = null,
                )
                progress(states.toList())
                val wavCount = dir.listFiles { f -> f.isFile && f.extension.equals("wav", true) }.orEmpty().size
                if (!final.exists() && wavCount >= 5) error("/${dir.name}/ already contains 5 WAV files")
                PhoneSoundRelay.copyVerified(payload, temp, task.offer.sha256)
                states[index] = states[index].copy(tempWritten = true); progress(states.toList())
                if (final.isFile) {
                    backup.delete()
                    PhoneSoundRelay.copyVerified(final, backup, PhoneSoundRelay.sha256(final))
                    if (!final.delete()) error("Cannot replace existing ${final.name}")
                    states[index] = states[index].copy(backupWritten = true); progress(states.toList())
                }
                states[index] = states[index].copy(finalWriteStarted = true); progress(states.toList())
                if (!temp.renameTo(final)) {
                    PhoneSoundRelay.copyVerified(temp, final, task.offer.sha256)
                    if (!temp.delete()) error("Cannot remove transaction temp")
                }
                if (final.length() != payload.length() || PhoneSoundRelay.sha256(final) != task.offer.sha256) error("Final reread failed in /${dir.name}/")
                states[index] = states[index].copy(finalVerified = true); progress(states.toList())
            }
            dirs.forEach { File(it, ".openavm-$op.bak").delete(); File(it, ".openavm-$op.tmp").delete() }
            return InstallResult(
                true, states,
                message = "WAV verified in both sound folders. Select the new custom sound in Vehicle settings; reopen that page or restart the display if it is not listed.",
            )
        } catch (t: Throwable) {
            dirs.forEachIndexed { index, dir ->
                runCatching {
                    val final = File(dir, name); val temp = File(dir, ".openavm-$op.tmp"); val backup = File(dir, ".openavm-$op.bak")
                    temp.delete()
                    if (backup.isFile) {
                        final.delete()
                        if (!backup.renameTo(final)) PhoneSoundRelay.copyVerified(backup, final, PhoneSoundRelay.sha256(backup))
                        backup.delete()
                    } else if (!states[index].existedBefore && (states[index].finalVerified || states[index].finalWriteStarted)) {
                        if (final.exists() && !final.delete()) error("Cannot roll back new WAV")
                    }
                    states[index] = states[index].copy(rollbackCompleted = true, error = t.message)
                }.onFailure { rollback -> states[index] = states[index].copy(error = "${t.message}; rollback: ${rollback.message}") }
            }
            progress(states.toList())
            return InstallResult(false, states, "DUAL_DIRECTORY_TRANSACTION_FAILED", t.message ?: "USB sound transaction failed")
        }
    }
}

