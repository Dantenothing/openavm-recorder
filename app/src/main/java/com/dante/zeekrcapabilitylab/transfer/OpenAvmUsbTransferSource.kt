package com.dante.zeekrcapabilitylab.transfer

import android.content.Context
import com.dante.zeekrcapabilitylab.usbexport.UsbExportVolumeResolver
import com.dante.zeekrcapabilitylab.usbexport.UsbMediaStoreBackend
import com.dante.zeekrcapabilitylab.usbexport.UsbSegmentCatalog
import java.io.File

sealed interface OpenAvmUsbSourceResolution {
    data class Resolved(val file: File) : OpenAvmUsbSourceResolution
    data class WaitingForUsb(val reason: String) : OpenAvmUsbSourceResolution
    data class Invalid(val reason: String) : OpenAvmUsbSourceResolution
}

object OpenAvmUsbTransferSource {
    fun resolve(context: Context, task: TransferTask): OpenAvmUsbSourceResolution {
        val uuid = task.sourceStorageUuid
            ?: return OpenAvmUsbSourceResolution.Invalid("USB source identity is missing")
        val bundleId = task.sourceBundleId
            ?: return OpenAvmUsbSourceResolution.Invalid("USB bundle identity is missing")
        val target = UsbExportVolumeResolver.mountedTargets(context).firstOrNull {
            it.storageUuid.equals(uuid, ignoreCase = true)
        } ?: return OpenAvmUsbSourceResolution.WaitingForUsb("Reconnect the same USB to continue transfer")
        val bundle = runCatching {
            UsbSegmentCatalog(context).snapshot(target).segments.firstOrNull {
                it.manifestData.bundleId == bundleId
            }
        }.getOrElse {
            return OpenAvmUsbSourceResolution.Invalid("USB bundle could not be verified")
        } ?: return OpenAvmUsbSourceResolution.Invalid("The selected USB segment is missing")
        if (task.sourceContentKey != bundle.manifestData.contentKey ||
            task.fileName != bundle.video.displayName ||
            task.sizeBytes != bundle.video.sizeBytes
        ) return OpenAvmUsbSourceResolution.Invalid("The selected USB segment changed")
        val root = target.directoryPath?.let(::File)
            ?: return OpenAvmUsbSourceResolution.Invalid("USB raw playback path is unavailable")
        val relative = listOfNotNull(
            bundle.video.relativePath?.trim()?.trim('/', '\\'),
            bundle.video.displayName,
        ).joinToString("/")
        val file = File(root, relative.replace('/', File.separatorChar))
        return if (file.isFile && file.length() == task.sizeBytes) {
            OpenAvmUsbSourceResolution.Resolved(file)
        } else {
            OpenAvmUsbSourceResolution.Invalid("The selected USB segment changed")
        }
    }

    fun sidecarJson(context: Context, storageUuid: String, bundleId: String): Result<String> =
        runCatching {
            val target = UsbExportVolumeResolver.mountedTargets(context).single {
                it.storageUuid.equals(storageUuid, ignoreCase = true)
            }
            val bundle = UsbSegmentCatalog(context).snapshot(target).segments.single {
                it.manifestData.bundleId == bundleId
            }
            UsbMediaStoreBackend(context).readBytes(bundle.sidecar.uri).toString(Charsets.UTF_8)
        }
}
