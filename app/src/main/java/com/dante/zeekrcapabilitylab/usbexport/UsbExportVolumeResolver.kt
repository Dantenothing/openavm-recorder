package com.dante.zeekrcapabilitylab.usbexport

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.MediaStore
import java.io.File
import java.util.Locale
import kotlinx.coroutines.delay

object UsbExportVolumeResolver {
    fun mountedTargets(context: Context): List<UsbExportTarget> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        val appContext = context.applicationContext
        val mediaNames = runCatching {
            MediaStore.getExternalVolumeNames(appContext)
        }.getOrDefault(emptySet())
        val manager = appContext.getSystemService(StorageManager::class.java)
        val removable = runCatching { manager.storageVolumes }.getOrDefault(emptyList())
            .filter { it.isRemovable && it.state == Environment.MEDIA_MOUNTED }
        val claimed = mutableSetOf<String>()
        return removable.mapNotNull { volume ->
            val uuid = volume.uuid ?: return@mapNotNull null
            val expectedName = uuid.lowercase(Locale.ROOT)
            val volumeName = mediaNames.firstOrNull { it.equals(expectedName, ignoreCase = true) }
                ?: mediaNames.filterNot { it == MediaStore.VOLUME_EXTERNAL_PRIMARY || it in claimed }
                    .singleOrNull()
                ?: return@mapNotNull null
            claimed += volumeName
            val directory = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                volume.directory
            } else {
                null
            }
            UsbExportTarget(
                volumeName = volumeName,
                storageUuid = uuid,
                description = runCatching { volume.getDescription(appContext) }.getOrDefault("USB"),
                directoryPath = directory?.absolutePath,
                collectionUri = MediaStore.Downloads.getContentUri(volumeName).toString(),
                freeBytes = directory?.let { runCatching { File(it.absolutePath).usableSpace }.getOrNull() },
                totalBytes = directory?.let { runCatching { File(it.absolutePath).totalSpace }.getOrNull() },
            )
        }.sortedBy { it.description.lowercase(Locale.ROOT) }
    }

    suspend fun awaitMountedTargets(
        context: Context,
        timeoutMs: Long = 3_000L,
        intervalMs: Long = 250L,
    ): List<UsbExportTarget> {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        do {
            val targets = mountedTargets(context)
            if (targets.isNotEmpty()) return targets
            delay(intervalMs)
        } while (android.os.SystemClock.elapsedRealtime() < deadline)
        return mountedTargets(context)
    }

    /** Lightweight recorder watchdog check that does not query a disappearing MediaStore provider. */
    fun isRemovableVolumeMounted(context: Context, storageUuid: String): Boolean {
        val appContext = context.applicationContext
        val manager = appContext.getSystemService(StorageManager::class.java)
        return runCatching {
            manager.storageVolumes.any { volume ->
                volume.isRemovable &&
                    volume.state == Environment.MEDIA_MOUNTED &&
                    volume.uuid.equals(storageUuid, ignoreCase = true)
            }
        }.getOrDefault(false)
    }

    fun resolveExact(context: Context, expected: UsbExportTarget): UsbExportTarget? =
        mountedTargets(context).firstOrNull {
            it.storageUuid.equals(expected.storageUuid, ignoreCase = true) &&
                it.volumeName.equals(expected.volumeName, ignoreCase = true)
        }
}
