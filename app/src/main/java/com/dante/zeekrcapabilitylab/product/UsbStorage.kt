package com.dante.zeekrcapabilitylab.product

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File

/**
 * Discovery of removable USB volumes without any storage permission: the
 * app-specific directory that [Context.getExternalFilesDirs] returns on a
 * removable volume is writable by this app on every supported API level, and
 * fully readable on a PC (Android/data/<package>/files/...).
 */
object UsbStorage {

    data class UsbVolume(
        val root: File,
        val description: String,
        val freeBytes: Long,
    )

    /** First mounted removable volume, or null when no usable stick is present. */
    fun findRemovableVolume(context: Context): UsbVolume? =
        context.getExternalFilesDirs(null)
            .orEmpty()
            .filterNotNull()
            .firstNotNullOfOrNull { dir -> asRemovableVolume(context, dir) }

    private fun asRemovableVolume(context: Context, dir: File): UsbVolume? = runCatching {
        if (!Environment.isExternalStorageRemovable(dir)) return@runCatching null
        if (Environment.getExternalStorageState(dir) != Environment.MEDIA_MOUNTED) return@runCatching null
        if (!dir.exists() && !dir.mkdirs()) return@runCatching null
        UsbVolume(
            root = dir,
            description = volumeDescription(context, dir),
            freeBytes = dir.usableSpace,
        )
    }.getOrNull()

    private fun volumeDescription(context: Context, dir: File): String = runCatching {
        val manager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        manager?.getStorageVolume(dir)?.getDescription(context)
    }.getOrNull() ?: "USB"
}
