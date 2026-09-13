package com.dante.zeekrcapabilitylab.transfer

import android.content.Context
import com.dante.zeekrcapabilitylab.usbexport.UsbExportTarget
import com.dante.zeekrcapabilitylab.usbexport.UsbExportVolumeResolver
import com.dante.zeekrcapabilitylab.usbexport.UsbMutationCoordinator
import io.github.dantenothing.avmtransfer.protocol.SoundTransferProtocol
import io.github.dantenothing.avmtransfer.protocol.SoundTransferValidation
import io.github.dantenothing.avmtransfer.protocol.SoundWavParameters
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class ZeekrSoundMirrorState {
    MIRRORED,
    ENGLISH_ONLY,
    CHINESE_ONLY,
    CONFLICT,
}

data class ZeekrSoundUsbCopy(
    val directoryName: String,
    val absolutePath: String,
    val sizeBytes: Long,
    val sha256: String?,
    val wav: SoundWavParameters?,
)

data class ZeekrSoundUsbEntry(
    val stableKey: String,
    val storageUuid: String,
    val storageDescription: String,
    val fileName: String,
    val copies: List<ZeekrSoundUsbCopy>,
    val mirrorState: ZeekrSoundMirrorState,
    val durationMs: Long?,
) {
    val totalBytes: Long get() = copies.sumOf { it.sizeBytes }
    val previewPath: String? get() = copies.firstOrNull()?.absolutePath
    val deleteEligible: Boolean
        get() = SoundTransferValidation.cleanWavFileName(fileName) == fileName &&
            copies.isNotEmpty() &&
            copies.all { it.sha256 != null && it.sizeBytes <= MAX_SAFE_DELETE_BYTES }

    companion object {
        const val MAX_SAFE_DELETE_BYTES = 16L * 1024L * 1024L
    }
}

data class ZeekrSoundUsbVolume(
    val storageUuid: String,
    val description: String,
    val sounds: List<ZeekrSoundUsbEntry>,
)

data class ZeekrSoundUsbSnapshot(
    val volumes: List<ZeekrSoundUsbVolume> = emptyList(),
    val errors: List<String> = emptyList(),
)

data class ZeekrSoundDeleteResult(
    val deleted: Boolean,
    val deletedCopies: Int = 0,
    val errorCode: String? = null,
    val message: String? = null,
)

/**
 * Vehicle-side inventory for the two known Zeekr custom-sound folders.
 * It never scans or mutates recordings, /SentryMode/, or arbitrary USB paths.
 */
object ZeekrSoundUsbLibrary {
    suspend fun scan(context: Context): ZeekrSoundUsbSnapshot = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val errors = mutableListOf<String>()
        val volumes = UsbExportVolumeResolver.mountedTargets(appContext).mapNotNull { target ->
            runCatching {
                UsbMutationCoordinator.withTarget(target.storageUuid) { scanTarget(target, errors) }
            }.onFailure { errors += "${target.description}:${it.message ?: it.javaClass.simpleName}" }
                .getOrNull()
        }
        ZeekrSoundUsbSnapshot(volumes = volumes, errors = errors)
    }

    suspend fun delete(
        context: Context,
        requested: ZeekrSoundUsbEntry,
    ): ZeekrSoundDeleteResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val target = UsbExportVolumeResolver.mountedTargets(appContext)
            .singleOrNull { it.storageUuid.equals(requested.storageUuid, ignoreCase = true) }
            ?: return@withContext ZeekrSoundDeleteResult(
                deleted = false,
                errorCode = "TARGET_NOT_MOUNTED",
                message = "Reconnect the same USB",
            )
        UsbMutationCoordinator.withTarget(target.storageUuid) {
            deleteLocked(appContext, target, requested)
        }
    }

    private fun scanTarget(
        target: UsbExportTarget,
        errors: MutableList<String>,
    ): ZeekrSoundUsbVolume {
        val root = target.directoryPath?.let(::File)?.canonicalFile
            ?: error("USB_RAW_ROOT_UNAVAILABLE")
        require(root.isDirectory) { "USB_ROOT_NOT_READABLE" }
        val byName = linkedMapOf<String, MutableList<ZeekrSoundUsbCopy>>()

        SoundTransferProtocol.TARGET_DIRECTORIES.forEach { directoryName ->
            val directory = exactDirectory(root, directoryName) ?: error("SOUND_DIRECTORY_PATH_ESCAPE")
            if (!directory.exists()) return@forEach
            if (!directory.isDirectory) {
                errors += "${target.description}/$directoryName:NOT_A_DIRECTORY"
                return@forEach
            }
            directory.listFiles().orEmpty()
                .filter { it.isFile && it.extension.equals("wav", ignoreCase = true) }
                .sortedBy { it.name.lowercase(Locale.ROOT) }
                .forEach { file ->
                    val exact = runCatching { file.canonicalFile }.getOrNull() ?: return@forEach
                    if (exact.parentFile != directory) return@forEach
                    val hash = runCatching { PhoneSoundRelay.sha256(exact) }
                        .onFailure { errors += "${target.description}/$directoryName/${file.name}:HASH_FAILED" }
                        .getOrNull()
                    val wav = runCatching { PhoneSoundRelay.parseWav(exact) }.getOrNull()
                    byName.getOrPut(file.name.lowercase(Locale.ROOT)) { mutableListOf() } +=
                        ZeekrSoundUsbCopy(
                            directoryName = directoryName,
                            absolutePath = exact.absolutePath,
                            sizeBytes = exact.length(),
                            sha256 = hash,
                            wav = wav,
                        )
                }
        }

        val sounds = byName.map { (normalizedName, copies) ->
            val ordered = copies.sortedBy {
                SoundTransferProtocol.TARGET_DIRECTORIES.indexOf(it.directoryName)
            }
            val directories = ordered.mapTo(mutableSetOf()) { it.directoryName }
            val hashes = ordered.mapNotNull { it.sha256 }.toSet()
            val mirrorState = when {
                directories.size == 2 && hashes.size == 1 && ordered.all { it.sha256 != null } ->
                    ZeekrSoundMirrorState.MIRRORED
                directories == setOf(SoundTransferProtocol.TARGET_DIRECTORIES[0]) ->
                    ZeekrSoundMirrorState.ENGLISH_ONLY
                directories == setOf(SoundTransferProtocol.TARGET_DIRECTORIES[1]) ->
                    ZeekrSoundMirrorState.CHINESE_ONLY
                else -> ZeekrSoundMirrorState.CONFLICT
            }
            val wav = ordered.firstNotNullOfOrNull { it.wav }
            ZeekrSoundUsbEntry(
                stableKey = "${target.storageUuid}:$normalizedName",
                storageUuid = target.storageUuid,
                storageDescription = target.description,
                fileName = File(ordered.first().absolutePath).name,
                copies = ordered,
                mirrorState = mirrorState,
                durationMs = wav?.let(::durationMs),
            )
        }.sortedBy { it.fileName.lowercase(Locale.ROOT) }

        return ZeekrSoundUsbVolume(
            storageUuid = target.storageUuid,
            description = target.description,
            sounds = sounds,
        )
    }

    private fun deleteLocked(
        context: Context,
        target: UsbExportTarget,
        requested: ZeekrSoundUsbEntry,
    ): ZeekrSoundDeleteResult {
        if (SoundTransferValidation.cleanWavFileName(requested.fileName) != requested.fileName) {
            return failure("UNSAFE_FILE_NAME", "The WAV name is not safe to delete")
        }
        val fresh = scanTarget(target, mutableListOf()).sounds
            .firstOrNull { it.fileName.equals(requested.fileName, ignoreCase = true) }
            ?: return ZeekrSoundDeleteResult(deleted = true, message = "Sound was already absent")
        val expected = requested.copies.associate { it.directoryName to (it.sizeBytes to it.sha256) }
        val observed = fresh.copies.associate { it.directoryName to (it.sizeBytes to it.sha256) }
        if (expected != observed || !fresh.deleteEligible) {
            return failure("SOUND_CHANGED_REFRESH_REQUIRED", "Sound copies changed; refresh before deleting")
        }

        val root = target.directoryPath?.let(::File)?.canonicalFile
            ?: return failure("USB_RAW_ROOT_UNAVAILABLE", "USB raw root unavailable")
        val sources = fresh.copies.mapNotNull { copy ->
            val exact = exactSoundFile(root, copy.directoryName, fresh.fileName)
            exact?.takeIf { it.isFile }?.let { Triple(it, copy.sha256 ?: return@mapNotNull null, copy.sizeBytes) }
        }
        if (sources.size != fresh.copies.size) {
            return failure("SOUND_PATH_REVALIDATION_FAILED", "An exact sound path could not be revalidated")
        }

        val backupRoot = File(context.cacheDir, "sound-delete/${UUID.randomUUID()}")
        if (!backupRoot.mkdirs()) return failure("BACKUP_CREATE_FAILED", "Could not create rollback backup")
        val backups = mutableListOf<Triple<File, File, String>>()
        val removed = mutableListOf<Triple<File, File, String>>()
        return try {
            sources.forEachIndexed { index, (source, sha, expectedBytes) ->
                if (source.length() != expectedBytes || PhoneSoundRelay.sha256(source) != sha) {
                    error("SOURCE_CHANGED:${source.name}")
                }
                val backup = File(backupRoot, "$index.wav")
                PhoneSoundRelay.copyVerified(source, backup, sha)
                backups += Triple(source, backup, sha)
            }
            backups.forEach { item ->
                val source = item.first
                if (!source.delete() || source.exists()) error("DELETE_FAILED:${source.absolutePath}")
                removed += item
            }
            cleanupBackups(backupRoot)
            ZeekrSoundDeleteResult(
                deleted = true,
                deletedCopies = removed.size,
                message = "Deleted and verified in both known sound folders",
            )
        } catch (problem: Throwable) {
            val rollbackErrors = removed.mapNotNull { (source, backup, sha) ->
                runCatching { PhoneSoundRelay.copyVerified(backup, source, sha) }
                    .exceptionOrNull()?.message
            }
            if (rollbackErrors.isEmpty()) cleanupBackups(backupRoot)
            failure(
                "SOUND_DELETE_FAILED",
                buildString {
                    append(problem.message ?: problem.javaClass.simpleName)
                    if (rollbackErrors.isNotEmpty()) append("; rollback: ${rollbackErrors.joinToString()}")
                    if (rollbackErrors.isNotEmpty()) append("; backup retained at ${backupRoot.absolutePath}")
                },
            )
        }
    }

    private fun exactDirectory(root: File, directoryName: String): File? = runCatching {
        File(root, directoryName).canonicalFile.takeIf { it.parentFile == root }
    }.getOrNull()

    private fun exactSoundFile(root: File, directoryName: String, fileName: String): File? {
        val directory = exactDirectory(root, directoryName) ?: return null
        return runCatching {
            File(directory, fileName).canonicalFile.takeIf { it.parentFile == directory }
        }.getOrNull()
    }

    private fun durationMs(wav: SoundWavParameters): Long? {
        val bytesPerSecond = wav.sampleRate.toLong() * wav.channels * (wav.bitsPerSample / 8)
        return bytesPerSecond.takeIf { it > 0L }?.let { wav.dataBytes * 1_000L / it }
    }

    private fun cleanupBackups(root: File) {
        root.listFiles().orEmpty().forEach { it.delete() }
        root.delete()
    }

    private fun failure(code: String, message: String) =
        ZeekrSoundDeleteResult(deleted = false, errorCode = code, message = message)
}
