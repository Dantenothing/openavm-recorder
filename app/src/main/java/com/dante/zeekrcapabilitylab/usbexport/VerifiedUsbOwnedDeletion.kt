package com.dante.zeekrcapabilitylab.usbexport

import java.io.File
import java.util.Locale

/**
 * Resolves a fresh, manifest-proven MediaStore item back into OpenAVM's exact
 * USB namespace. The raw path is used only when the vendor MediaStore refuses
 * to delete its own URI; /SentryMode/ can never pass these checks.
 */
internal object VerifiedUsbOwnedDeletion {
    data class Result(
        val deleted: Boolean,
        val usedRawFallback: Boolean = false,
        val error: String? = null,
    )

    fun delete(
        target: UsbExportTarget,
        metadata: UsbMediaStoreBackend.Metadata,
        backend: UsbMediaStoreBackend,
        asset: UsbExportAsset,
    ): Result {
        val mediaStoreResult = backend.deleteOwned(target, asset)
        if (mediaStoreResult.deletionConfirmed) return Result(deleted = true)

        val raw = resolve(target, metadata)
            ?: return Result(
                deleted = false,
                error = listOfNotNull(mediaStoreResult.error, "RAW_FALLBACK_UNAVAILABLE")
                    .joinToString(";"),
            )
        if (!raw.exists()) {
            // The bytes are already absent even if the vendor MediaStore kept a
            // stale row. Catalogs also filter such rows by exact raw presence.
            return Result(deleted = true, usedRawFallback = true)
        }
        if (!raw.isFile || !raw.delete() || raw.exists()) {
            return Result(
                deleted = false,
                usedRawFallback = true,
                error = listOfNotNull(mediaStoreResult.error, "RAW_DELETE_FAILED:${raw.name}")
                    .joinToString(";"),
            )
        }
        return Result(deleted = true, usedRawFallback = true)
    }

    fun isPhysicallyPresent(
        target: UsbExportTarget,
        metadata: UsbMediaStoreBackend.Metadata,
    ): Boolean {
        if (target.directoryPath == null) return true
        return resolve(target, metadata)?.isFile == true
    }

    private fun resolve(
        target: UsbExportTarget,
        metadata: UsbMediaStoreBackend.Metadata,
    ): File? {
        val rootPath = target.directoryPath ?: return null
        val name = metadata.displayName ?: return null
        if (name.isBlank() || name == "." || name == ".." || '/' in name || '\\' in name) return null
        if (normalize(metadata.relativePath) != normalize(UsbExportPolicy.RELATIVE_PATH)) return null

        return runCatching {
            val root = File(rootPath).canonicalFile
            val namespace = File(root, UsbExportPolicy.RELATIVE_PATH).canonicalFile
            val candidate = File(namespace, name).canonicalFile
            val rootPrefix = root.path.trimEnd(File.separatorChar) + File.separator
            if (!namespace.path.startsWith(rootPrefix) || candidate.parentFile != namespace) null
            else candidate
        }.getOrNull()
    }

    private fun normalize(value: String?): String? =
        value?.trim()?.replace('\\', '/')?.trim('/')?.lowercase(Locale.ROOT)
}
