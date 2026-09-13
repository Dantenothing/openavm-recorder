package com.dante.zeekrcapabilitylab.usbexport

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import android.net.Uri
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale

class UsbMediaStoreBackend(private val context: Context) {
    data class TrackMetadata(
        val width: Int?,
        val height: Int?,
        val bitrateBps: Long?,
        val durationMs: Long?,
    )

    data class Metadata(
        val uri: Uri,
        val displayName: String?,
        val relativePath: String?,
        val sizeBytes: Long?,
        val mimeType: String?,
        val pending: Int?,
        val volumeName: String?,
        val ownerPackage: String?,
        val dateModifiedSeconds: Long?,
    )

    private val resolver = context.contentResolver

    fun insertPending(target: UsbExportTarget, displayName: String, mimeType: String): Metadata {
        val collection = Uri.parse(target.collectionUri)
        val uri = resolver.insert(
            collection,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, UsbExportPolicy.RELATIVE_PATH)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            },
        ) ?: error("MediaStore insert returned null")
        return metadata(uri) ?: error("Created MediaStore item cannot be queried")
    }

    fun openRecordingDescriptor(uri: Uri): ParcelFileDescriptor =
        resolver.openFileDescriptor(uri, "rw")
            ?: error("MediaStore did not return a writable recording descriptor")

    fun sync(descriptor: ParcelFileDescriptor) {
        descriptor.fileDescriptor.sync()
    }

    fun trackMetadata(uri: Uri): TrackMetadata? {
        val descriptor = resolver.openFileDescriptor(uri, "r") ?: return null
        descriptor.use { pfd ->
            val retriever = MediaMetadataRetriever()
            return try {
                retriever.setDataSource(pfd.fileDescriptor)
                TrackMetadata(
                    width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull(),
                    height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull(),
                    bitrateBps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull(),
                    durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
                )
            } catch (_: Throwable) {
                null
            } finally {
                runCatching { retriever.release() }
            }
        }
    }

    fun copyFile(
        source: File,
        destination: Uri,
        cancelled: () -> Boolean,
        onBytes: (Long) -> Unit,
    ): Pair<Long, String> = FileInputStream(source).use { input ->
        copyInput(input, destination, cancelled, onBytes)
    }

    fun copyBytes(
        bytes: ByteArray,
        destination: Uri,
        cancelled: () -> Boolean,
        onBytes: (Long) -> Unit,
    ): Pair<Long, String> = bytes.inputStream().use { input ->
        copyInput(input, destination, cancelled, onBytes)
    }

    private fun copyInput(
        input: InputStream,
        destination: Uri,
        cancelled: () -> Boolean,
        onBytes: (Long) -> Unit,
    ): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        val descriptor = resolver.openFileDescriptor(destination, "rw")
            ?: error("MediaStore did not return a writable descriptor")
        descriptor.use { pfd ->
            val output = FileOutputStream(pfd.fileDescriptor)
            try {
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    if (cancelled()) throw UsbExportCancelledException()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                    copied += count
                    onBytes(copied)
                }
                output.flush()
                pfd.fileDescriptor.sync()
            } finally {
                runCatching { output.close() }
            }
        }
        return copied to digest.digest().toHex()
    }

    fun hashFile(file: File, cancelled: () -> Boolean, onBytes: (Long) -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var read = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                if (cancelled()) throw UsbExportCancelledException()
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                digest.update(buffer, 0, count)
                read += count
                onBytes(read)
            }
        }
        return digest.digest().toHex()
    }

    fun hashUri(uri: Uri, cancelled: () -> Boolean = { false }): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        var read = 0L
        resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                if (cancelled()) throw UsbExportCancelledException()
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                digest.update(buffer, 0, count)
                read += count
            }
        } ?: error("MediaStore did not return a readable stream")
        return read to digest.digest().toHex()
    }

    fun publish(uri: Uri): Int = resolver.update(
        uri,
        ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
        null,
        null,
    )

    fun metadata(uri: Uri): Metadata? = query(uri, null, null).firstOrNull()

    fun findPublished(
        target: UsbExportTarget,
        displayName: String,
    ): Metadata? = query(
        uri = Uri.parse(target.collectionUri),
        selection = "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND " +
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND " +
            "${MediaStore.MediaColumns.IS_PENDING}=0",
        selectionArgs = arrayOf(UsbExportPolicy.RELATIVE_PATH, displayName),
    ).firstOrNull()

    fun listPublishedOpenAvm(target: UsbExportTarget): List<Metadata> = query(
        uri = Uri.parse(target.collectionUri),
        selection = "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND " +
            "${MediaStore.MediaColumns.IS_PENDING}=0",
        selectionArgs = arrayOf(UsbExportPolicy.RELATIVE_PATH),
    ).filter { metadata ->
        metadata.displayName?.startsWith("OpenAVM_", ignoreCase = true) == true
    }

    fun readBytes(uri: Uri, maxBytes: Int = 2 * 1024 * 1024): ByteArray {
        resolver.openInputStream(uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > maxBytes) error("MediaStore item exceeds safe read limit")
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
        error("MediaStore did not return a readable stream")
    }

    fun deleteOwned(target: UsbExportTarget, asset: UsbExportAsset): UsbExportAsset {
        val uriText = asset.itemUri ?: return asset
        val uri = Uri.parse(uriText)
        val observed = try {
            metadata(uri)
        } catch (t: Throwable) {
            return asset.copy(
                deleteAttempted = true,
                deletionConfirmed = false,
                error = "DELETE_QUERY_FAILED: ${t.javaClass.simpleName}",
            )
        }
        if (observed == null) {
            return asset.copy(deleteAttempted = true, deletionConfirmed = true)
        }
        val allowedNames = setOfNotNull(asset.requestedName, asset.observedName)
        val ownershipFailures = buildList {
            if (!isExactItemUri(target, uri)) add("URI")
            if (!observed.volumeName.equals(target.volumeName, ignoreCase = true)) add("VOLUME")
            if (normalizePath(observed.relativePath) != normalizePath(UsbExportPolicy.RELATIVE_PATH)) add("PATH")
            if (observed.displayName !in allowedNames) add("NAME")
            if (asset.expectedMimeType != null && observed.mimeType != asset.expectedMimeType) add("MIME")
            val recordedOwner = asset.observedOwnerPackage
            if (observed.ownerPackage.isNullOrBlank() ||
                (
                    observed.ownerPackage != context.packageName &&
                        observed.ownerPackage != recordedOwner
                    )
            ) add("OWNER")
            if (asset.published && observed.sizeBytes != asset.expectedBytes) add("SIZE")
        }
        if (ownershipFailures.isNotEmpty()) {
            return asset.copy(
                deleteAttempted = true,
                deletionConfirmed = false,
                observedName = observed.displayName,
                observedRelativePath = observed.relativePath,
                observedVolumeName = observed.volumeName,
                observedSizeBytes = observed.sizeBytes,
                observedMimeType = observed.mimeType,
                observedPending = observed.pending,
                observedOwnerPackage = observed.ownerPackage,
                error = "OWNERSHIP_MISMATCH:${ownershipFailures.joinToString(",")}",
            )
        }
        val deleted = runCatching { resolver.delete(uri, null, null) }.getOrDefault(0)
        val absent = try {
            metadata(uri) == null
        } catch (_: Throwable) {
            false
        }
        return asset.copy(
            deleteAttempted = true,
            deletionConfirmed = absent,
            observedName = observed.displayName,
            observedRelativePath = observed.relativePath,
            observedVolumeName = observed.volumeName,
            observedSizeBytes = observed.sizeBytes,
            observedMimeType = observed.mimeType,
            observedPending = observed.pending,
            observedOwnerPackage = observed.ownerPackage,
            error = if (absent) asset.error else "DELETE_FAILED count=$deleted",
        )
    }

    private fun isExactItemUri(target: UsbExportTarget, item: Uri): Boolean {
        val collection = Uri.parse(target.collectionUri)
        if (!item.scheme.equals(collection.scheme, ignoreCase = true)) return false
        if (!item.authority.equals(collection.authority, ignoreCase = true)) return false
        val collectionSegments = collection.pathSegments
        val itemSegments = item.pathSegments
        if (itemSegments.size != collectionSegments.size + 1) return false
        if (!collectionSegments.indices.all {
                itemSegments[it].equals(collectionSegments[it], ignoreCase = true)
            }
        ) return false
        return itemSegments.last().toLongOrNull() != null
    }

    private fun query(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<String>?,
    ): List<Metadata> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.IS_PENDING,
            MediaStore.MediaColumns.VOLUME_NAME,
            MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
        return resolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    fun string(column: String): String? {
                        val index = cursor.getColumnIndex(column)
                        return if (index >= 0 && !cursor.isNull(index)) cursor.getString(index) else null
                    }
                    fun long(column: String): Long? {
                        val index = cursor.getColumnIndex(column)
                        return if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else null
                    }
                    val id = long(MediaStore.MediaColumns._ID) ?: continue
                    val itemUri = if (uri.lastPathSegment?.toLongOrNull() != null) {
                        uri
                    } else {
                        ContentUris.withAppendedId(uri, id)
                    }
                    add(
                        Metadata(
                            uri = itemUri,
                            displayName = string(MediaStore.MediaColumns.DISPLAY_NAME),
                            relativePath = string(MediaStore.MediaColumns.RELATIVE_PATH),
                            sizeBytes = long(MediaStore.MediaColumns.SIZE),
                            mimeType = string(MediaStore.MediaColumns.MIME_TYPE),
                            pending = long(MediaStore.MediaColumns.IS_PENDING)?.toInt(),
                            volumeName = string(MediaStore.MediaColumns.VOLUME_NAME),
                            ownerPackage = string(MediaStore.MediaColumns.OWNER_PACKAGE_NAME),
                            dateModifiedSeconds = long(MediaStore.MediaColumns.DATE_MODIFIED),
                        ),
                    )
                }
            }
        }.orEmpty()
    }

    private fun normalizePath(value: String?): String? =
        value?.trim()?.replace('\\', '/')?.trim('/')?.lowercase(Locale.ROOT)

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(Locale.ROOT, it) }

}

class UsbExportCancelledException : RuntimeException("USB export cancelled")
