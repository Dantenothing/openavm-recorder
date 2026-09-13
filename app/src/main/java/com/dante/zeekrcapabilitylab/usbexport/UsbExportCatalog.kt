package com.dante.zeekrcapabilitylab.usbexport

import android.content.Context
import java.util.Locale
import kotlinx.serialization.json.Json

data class UsbExportCatalogSnapshot(
    val namespaceBytes: Long,
    val ownedBytes: Long,
    val completeExports: List<UsbOwnedExport>,
    val unclassifiedOwnedBytes: Long,
    val untrustedNamespaceBytes: Long,
)

data class UsbOwnedExport(
    val exportKey: String,
    val startedAtEpochMs: Long,
    val manifest: UsbMediaStoreBackend.Metadata,
    val assets: List<UsbMediaStoreBackend.Metadata>,
) {
    val existingBytes: Long
        get() = (assets + manifest).sumOf { it.sizeBytes ?: 0L }
}

data class UsbQuotaCleanupResult(
    val ownedBytesBefore: Long,
    val reclaimedBytes: Long,
    val ownedBytesAfter: Long,
)

class UsbExportCatalog(
    context: Context,
    private val backend: UsbMediaStoreBackend,
) {
    private val packageName = context.applicationContext.packageName
    private val json = Json { ignoreUnknownKeys = true }

    fun snapshot(target: UsbExportTarget): UsbExportCatalogSnapshot {
        val namespaceItems = backend.listPublishedOpenAvm(target)
            .filter { VerifiedUsbOwnedDeletion.isPhysicallyPresent(target, it) }
        val trustedOwners = buildSet {
            add(packageName)
            addAll(UsbExportRepository.trustedProviderOwners(target))
        }
        val trustedExportKeys = UsbExportRepository.trustedCompletedExportKeys(target)
        val ownerCandidates = namespaceItems.filter { metadata ->
            metadata.ownerPackage != null && metadata.ownerPackage in trustedOwners
        }
        val byName = ownerCandidates.mapNotNull { metadata ->
            metadata.displayName?.let { it to metadata }
        }.toMap()
        val complete = ownerCandidates.mapNotNull { metadata ->
            parseManifest(metadata, byName, trustedOwners)
        }.filter { export ->
            export.manifest.ownerPackage == packageName ||
                export.exportKey in trustedExportKeys
        }.distinctBy { it.exportKey }
        val classifiedUris = complete.flatMapTo(mutableSetOf()) { export ->
            (export.assets + export.manifest).map { it.uri.toString() }
        }
        val allOwned = namespaceItems.filter { metadata ->
            metadata.ownerPackage == packageName ||
                metadata.uri.toString() in classifiedUris
        }
        val ownedBytes = allOwned.sumOf { it.sizeBytes ?: 0L }
        val namespaceBytes = namespaceItems.sumOf { it.sizeBytes ?: 0L }
        val classifiedBytes = allOwned
            .filter { it.uri.toString() in classifiedUris }
            .sumOf { it.sizeBytes ?: 0L }
        return UsbExportCatalogSnapshot(
            namespaceBytes = namespaceBytes,
            ownedBytes = ownedBytes,
            completeExports = complete.sortedBy { it.startedAtEpochMs },
            unclassifiedOwnedBytes = (ownedBytes - classifiedBytes).coerceAtLeast(0L),
            untrustedNamespaceBytes = (namespaceBytes - ownedBytes).coerceAtLeast(0L),
        )
    }

    fun enforceQuotaBeforeExport(
        target: UsbExportTarget,
        incomingBytes: Long,
        protectedExportKey: String,
    ): UsbQuotaCleanupResult {
        if (incomingBytes > UsbExportPolicy.OPENAVM_QUOTA_BYTES) {
            throw UsbExportQuotaException(
                "EXPORT_EXCEEDS_OPENAVM_QUOTA",
                "This export is larger than the configured OpenAVM USB quota",
            )
        }
        val before = snapshot(target)
        var required = before.namespaceBytes + incomingBytes - UsbExportPolicy.OPENAVM_QUOTA_BYTES
        if (required <= 0L) {
            return UsbQuotaCleanupResult(before.namespaceBytes, 0L, before.namespaceBytes)
        }

        var reclaimed = 0L
        for (export in before.completeExports) {
            if (export.exportKey == protectedExportKey) continue
            val expectedReclaim = export.existingBytes
            deleteValidatedExport(target, export)
            reclaimed += expectedReclaim
            required -= expectedReclaim
            if (required <= 0L) break
        }

        val after = snapshot(target)
        if (after.namespaceBytes + incomingBytes > UsbExportPolicy.OPENAVM_QUOTA_BYTES) {
            throw UsbExportQuotaException(
                "OPENAVM_QUOTA_CLEANUP_FAILED",
                "OpenAVM owns files that could not be validated and safely reclaimed",
            )
        }
        return UsbQuotaCleanupResult(
            ownedBytesBefore = before.namespaceBytes,
            reclaimedBytes = reclaimed,
            ownedBytesAfter = after.namespaceBytes,
        )
    }

    private fun parseManifest(
        metadata: UsbMediaStoreBackend.Metadata,
        byName: Map<String, UsbMediaStoreBackend.Metadata>,
        trustedOwners: Set<String>,
    ): UsbOwnedExport? {
        val name = metadata.displayName ?: return null
        if (!name.endsWith("_manifest.json", ignoreCase = true)) return null
        if (!isStrictOwnedMetadata(metadata, name, "application/json", metadata.sizeBytes, trustedOwners)) return null
        if ((metadata.sizeBytes ?: Long.MAX_VALUE) > MAX_MANIFEST_BYTES) return null
        val manifest = runCatching {
            json.decodeFromString<UsbPortableManifest>(
                backend.readBytes(metadata.uri, MAX_MANIFEST_BYTES.toInt()).toString(Charsets.UTF_8),
            )
        }.getOrNull() ?: return null
        val prefix = name.removeSuffix("_manifest.json")
        if (!manifest.complete || manifest.exportKey.length != 64) return null
        if (!prefix.endsWith(manifest.exportKey.take(12), ignoreCase = true)) return null
        if (manifest.segmentCount <= 0 || manifest.assets.isEmpty()) return null
        if (manifest.assets.count { it.kind == UsbExportAssetKind.VIDEO } != manifest.segmentCount) return null
        if (manifest.assets.count { it.kind == UsbExportAssetKind.SIDECAR } != manifest.segmentCount) return null
        if (manifest.assets.map { it.displayName }.distinct().size != manifest.assets.size) return null
        if (manifest.assets.any { declared ->
                normalize(declared.relativePath) != normalize(UsbExportPolicy.RELATIVE_PATH) ||
                    !declared.displayName.startsWith("${prefix}_", ignoreCase = true) ||
                    declared.sizeBytes < 0L ||
                    !SHA256.matches(declared.sha256)
            }
        ) return null
        val existingAssets = manifest.assets.mapNotNull { declared ->
            byName[declared.displayName]?.takeIf { observed ->
                isStrictOwnedMetadata(
                    metadata = observed,
                    expectedName = declared.displayName,
                    expectedMime = declared.mimeType,
                    expectedBytes = declared.sizeBytes,
                    trustedOwners = trustedOwners,
                )
            }
        }
        if (existingAssets.size != manifest.assets.size) return null
        return UsbOwnedExport(
            exportKey = manifest.exportKey,
            startedAtEpochMs = manifest.startedAtEpochMs,
            manifest = metadata,
            assets = existingAssets,
        )
    }

    private fun deleteValidatedExport(target: UsbExportTarget, export: UsbOwnedExport) {
        // Invalidate ownership proof first; payloads cannot look complete if cleanup is interrupted.
        val ordered = listOf(export.manifest) + export.assets
        for (metadata in ordered) {
            val name = requireNotNull(metadata.displayName)
            val asset = UsbExportAsset(
                kind = when {
                    metadata === export.manifest -> UsbExportAssetKind.MANIFEST
                    name.endsWith(".sidecar.json", ignoreCase = true) -> UsbExportAssetKind.SIDECAR
                    else -> UsbExportAssetKind.VIDEO
                },
                requestedName = name,
                expectedBytes = requireNotNull(metadata.sizeBytes),
                expectedSha256 = "",
                expectedMimeType = metadata.mimeType,
                itemUri = metadata.uri.toString(),
                observedName = name,
                observedRelativePath = metadata.relativePath,
                observedVolumeName = metadata.volumeName,
                observedSizeBytes = metadata.sizeBytes,
                observedMimeType = metadata.mimeType,
                observedPending = metadata.pending,
                observedOwnerPackage = metadata.ownerPackage,
                verified = true,
                published = true,
            )
            val deleted = VerifiedUsbOwnedDeletion.delete(target, metadata, backend, asset)
            if (!deleted.deleted) {
                throw UsbExportQuotaException(
                    "OPENAVM_QUOTA_DELETE_FAILED",
                    "A validated OpenAVM export could not be deleted safely: ${deleted.error.orEmpty()}",
                )
            }
        }
    }

    private fun isStrictOwnedMetadata(
        metadata: UsbMediaStoreBackend.Metadata,
        expectedName: String,
        expectedMime: String?,
        expectedBytes: Long?,
        trustedOwners: Set<String>,
    ): Boolean =
        metadata.ownerPackage != null &&
            metadata.ownerPackage in trustedOwners &&
            metadata.displayName == expectedName &&
            normalize(metadata.relativePath) == normalize(UsbExportPolicy.RELATIVE_PATH) &&
            metadata.pending == 0 &&
            (expectedMime == null || metadata.mimeType == expectedMime) &&
            (expectedBytes == null || metadata.sizeBytes == expectedBytes)

    private fun normalize(value: String?): String? =
        value?.trim()?.replace('\\', '/')?.trim('/')?.lowercase(Locale.ROOT)

    companion object {
        private const val MAX_MANIFEST_BYTES = 2L * 1024L * 1024L
        private val SHA256 = Regex("^[0-9a-fA-F]{64}$")
    }
}

class UsbExportQuotaException(
    val code: String,
    message: String,
) : RuntimeException(message)
