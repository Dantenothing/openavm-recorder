package com.dante.zeekrcapabilitylab.product

import android.content.Context
import com.dante.zeekrcapabilitylab.service.recorder.RecorderState
import com.dante.zeekrcapabilitylab.service.recorder.RecordingStorageKind
import com.dante.zeekrcapabilitylab.service.recorder.RecordingStoragePreference
import com.dante.zeekrcapabilitylab.usbexport.*
import java.io.File

data class RecordingCapacity(val storage: RecordingStorageKind? = null, val freeBytes: Long? = null,
    val availableBytes: Long? = null)

object RecordingCapacityMath {
    fun knownUsage(sizes: List<Long?>): Long? {
        var total = 0L
        for (size in sizes) {
            if (size == null || size < 0 || size > Long.MAX_VALUE - total) return null
            total += size
        }
        return total
    }
    /** Headroom before deleting any existing recording. Unknown measurements stay unknown. */
    fun available(free: Long?, reserve: Long, quota: Long, used: Long?): Long? {
        if (free == null || used == null || listOf(free, reserve, quota, used).any { it < 0 }) return null
        return minOf((free - reserve).coerceAtLeast(0), (quota - used).coerceAtLeast(0))
    }

    fun minutes(bytes: Long?, bitrateBps: Long?, timeLapseMultiplier: Int): Long? {
        if (bytes == null || bytes < 0 || bitrateBps == null || bitrateBps <= 0 || timeLapseMultiplier < 1) return null
        // Requested bitrate plus a 10% allowance. This is an estimate, not measured duration/FPS.
        return (bytes.toDouble() * 8.0 / bitrateBps / 60.0 / 1.10 * timeLapseMultiplier).toLong()
    }
}

object RecordingCapacityReader {
    // Conservative free space: the estimate must not promise capacity obtained by evicting caches.
    @android.annotation.SuppressLint("UsableSpace")
    fun read(context: Context, state: RecorderState, running: Boolean): RecordingCapacity {
        val settings = SettingsStore.get(context)
        val targets = if (settings.recordingStoragePreference == RecordingStoragePreference.USB_PREFERRED ||
            running && state.activeStorageKind == RecordingStorageKind.USB_MEDIASTORE)
            UsbExportVolumeResolver.mountedTargets(context) else emptyList()
        val usb = if (running) targets.singleOrNull { it.storageUuid == state.activeStorageUuid }
            else targets.singleOrNull()
        if (running && state.activeStorageKind == RecordingStorageKind.USB_MEDIASTORE && usb == null)
            return RecordingCapacity(RecordingStorageKind.USB_MEDIASTORE)
        if (usb != null && (!running || state.activeStorageKind == RecordingStorageKind.USB_MEDIASTORE)) {
            val usage = runCatching {
                // One metadata query, no per-file reads while the recorder may be active.
                // Stale/unclassified namespace entries conservatively reduce this estimate.
                RecordingCapacityMath.knownUsage(UsbMediaStoreBackend(context).listPublishedOpenAvm(usb).map { it.sizeBytes })
            }.getOrNull()
            val quota = if (running) state.usbQuotaBytes else settings.usbQuotaBytes
            return RecordingCapacity(RecordingStorageKind.USB_MEDIASTORE, usb.freeBytes,
                RecordingCapacityMath.available(usb.freeBytes, UsbRecordingFreeSpace.RESERVE_BYTES, quota, usage))
        }
        val directory = File(context.filesDir, "recordings/segments")
        val free = runCatching { context.filesDir.usableSpace }.getOrNull()
        val usage = runCatching {
            if (!directory.exists()) 0L else directory.listFiles()?.filter { it.isFile }?.map(File::length)
                ?.let(RecordingCapacityMath::knownUsage)
        }.getOrNull()
        val quota = if (running) state.storageLimitBytes else settings.internalStorageLimitBytes
        return RecordingCapacity(RecordingStorageKind.INTERNAL, free,
            RecordingCapacityMath.available(free, settings.minFreeBytes, quota, usage))
    }
}
