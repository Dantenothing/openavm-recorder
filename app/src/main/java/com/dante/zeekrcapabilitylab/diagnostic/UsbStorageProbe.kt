package com.dante.zeekrcapabilitylab.diagnostic

import android.content.ContentValues
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.system.Os
import android.system.OsConstants
import com.dante.zeekrcapabilitylab.BuildConfig
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object UsbStorageProbe {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    suspend fun collect(context: Context, runWriteProbe: Boolean): UsbProbeReport = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val treeUri = savedTreeUri(appContext)
        val storageVolumes = collectStorageVolumes(appContext)
        val previousPicker = loadLatest(appContext)?.safPicker
        val picker = inspectSafPicker(appContext).copy(
            launchAttempted = previousPicker?.launchAttempted ?: false,
            launchDispatchSucceeded = previousPicker?.launchDispatchSucceeded,
            resultReturned = previousPicker?.resultReturned,
            treeSelected = previousPicker?.treeSelected,
            launchError = previousPicker?.launchError,
        )
        val report = UsbProbeReport(
            generatedAtEpochMs = System.currentTimeMillis(),
            buildVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            buildGitSha = BuildConfig.GIT_SHA,
            allFilesAccessGranted = if (Build.VERSION.SDK_INT >= 30) {
                Environment.isExternalStorageManager()
            } else {
                false
            },
            androidSdkInt = Build.VERSION.SDK_INT,
            appSpecific = collectAppSpecific(appContext, runWriteProbe),
            storageVolumes = storageVolumes,
            mediaStoreVolumes = collectMediaStoreVolumes(appContext, storageVolumes, runWriteProbe),
            mounts = collectMounts(),
            safPicker = picker,
            safTree = treeUri?.let { collectSafTree(appContext, it, runWriteProbe) },
            notes = buildList {
                add("Gate A.2 only: no camera, recording, media export, or raw-path write was attempted")
                if (runWriteProbe) {
                    add("The removable MediaStore probe used final-name insert, pending publication, metadata re-query, duplicate observation, and exact cleanup")
                    add("Explicit tiny probes were limited to removable app-specific storage, removable MediaStore volumes, and the selected SAF tree")
                } else {
                    add("Read-only collection: no tiny write probe was requested")
                }
            },
        )
        saveLatest(appContext, report)
        report
    }

    fun reportJson(report: UsbProbeReport): String = json.encodeToString(report)

    fun loadLatest(context: Context): UsbProbeReport? = runCatching {
        val file = latestReportFile(context)
        if (!file.isFile) null else json.decodeFromString(UsbProbeReport.serializer(), file.readText())
    }.getOrNull()

    fun latestReportFile(context: Context): File =
        File(context.applicationContext.filesDir, "diagnostics/usb-probe-latest.json")

    fun savedTreeUri(context: Context): Uri? = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY_TREE_URI, null)
        ?.let(Uri::parse)

    fun rememberTreeUri(context: Context, uri: Uri) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TREE_URI, uri.toString())
            .apply()
    }

    @Suppress("DEPRECATION")
    fun inspectSafPicker(context: Context): SafPickerObservation {
        val appContext = context.applicationContext
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addCategory(Intent.CATEGORY_DEFAULT)
        val resolved = runCatching {
            appContext.packageManager.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        }.getOrNull()
        return SafPickerObservation(
            intentAction = intent.action.orEmpty(),
            handlerAvailable = resolved != null,
            resolvedPackage = resolved?.activityInfo?.packageName,
            resolvedActivity = resolved?.activityInfo?.name,
        )
    }

    fun recordSafPickerLaunchAttempt(context: Context): UsbProbeReport? = updateSafPicker(context) {
        inspectSafPicker(context).copy(
            launchAttempted = true,
            launchDispatchSucceeded = null,
            resultReturned = null,
            treeSelected = null,
            launchError = null,
        )
    }

    fun recordSafPickerLaunchDispatched(context: Context): UsbProbeReport? = updateSafPicker(context) {
        it.copy(
            launchAttempted = true,
            launchDispatchSucceeded = true,
            launchError = null,
        )
    }

    fun recordSafPickerResult(context: Context, treeSelected: Boolean): UsbProbeReport? = updateSafPicker(context) {
        it.copy(
            launchAttempted = true,
            launchDispatchSucceeded = true,
            resultReturned = true,
            treeSelected = treeSelected,
            launchError = null,
        )
    }

    fun recordSafPickerLaunchFailure(context: Context, failure: Throwable): UsbProbeReport? = updateSafPicker(context) {
        inspectSafPicker(context).copy(
            launchAttempted = true,
            launchDispatchSucceeded = false,
            resultReturned = false,
            treeSelected = false,
            launchError = describeError(failure),
        )
    }

    private fun updateSafPicker(
        context: Context,
        transform: (SafPickerObservation) -> SafPickerObservation,
    ): UsbProbeReport? {
        val appContext = context.applicationContext
        val latest = loadLatest(appContext) ?: return null
        val updated = latest.copy(
            generatedAtEpochMs = System.currentTimeMillis(),
            safPicker = transform(latest.safPicker ?: inspectSafPicker(appContext)),
        )
        saveLatest(appContext, updated)
        return updated
    }

    private fun saveLatest(context: Context, report: UsbProbeReport) {
        val target = latestReportFile(context)
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, target.name + ".tmp")
        temporary.writeText(reportJson(report))
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
    }

    private fun collectAppSpecific(context: Context, runWriteProbe: Boolean): List<AppSpecificStorageObservation> =
        context.getExternalFilesDirs(null).mapIndexed { index, file ->
            if (file == null) {
                AppSpecificStorageObservation(
                    index = index,
                    path = null,
                    exists = false,
                    removable = null,
                    readable = false,
                    writable = false,
                    usableBytes = null,
                    totalBytes = null,
                )
            } else {
                val removable = runCatching { Environment.isExternalStorageRemovable(file) }.getOrNull()
                val stats = runCatching { StatFs(file.absolutePath) }.getOrNull()
                AppSpecificStorageObservation(
                    index = index,
                    path = file.absolutePath,
                    exists = file.exists(),
                    removable = removable,
                    readable = file.canRead(),
                    writable = file.canWrite(),
                    usableBytes = stats?.availableBytes,
                    totalBytes = stats?.totalBytes,
                    writeProbe = if (runWriteProbe && removable == true) probeAppSpecific(file) else null,
                )
            }
        }

    private fun probeAppSpecific(root: File): UsbWriteProbeResult {
        val token = UUID.randomUUID().toString()
        val probeDirectory = File(root, ".openavm-usb-probe-$token")
        val source = File(probeDirectory, "probe.tmp")
        val renamed = File(probeDirectory, "probe.verified")
        val marker = "OpenAVM USB probe $token".toByteArray(Charsets.UTF_8)
        var created = false
        var wrote = false
        var synced = false
        var sought = false
        var readBack = false
        var renamedOk = false
        var error: String? = null
        try {
            check(probeDirectory.mkdir()) { "Could not create the unique probe directory" }
            RandomAccessFile(source, "rw").use { file ->
                created = true
                file.setLength(0L)
                file.write(marker)
                wrote = true
                file.fd.sync()
                synced = true
                file.seek(0L)
                sought = true
                val actual = ByteArray(marker.size)
                file.readFully(actual)
                readBack = actual.contentEquals(marker)
            }
            renamedOk = source.renameTo(renamed)
        } catch (t: Throwable) {
            error = describeError(t)
        }
        val cleanupOk = runCatching {
            val sourceGone = !source.exists() || source.delete()
            val renamedGone = !renamed.exists() || renamed.delete()
            val directoryGone = !probeDirectory.exists() || probeDirectory.delete()
            sourceGone && renamedGone && directoryGone
        }.getOrDefault(false)
        return UsbWriteProbeResult(
            createSucceeded = created,
            writeSucceeded = wrote,
            syncSucceeded = synced,
            seekSucceeded = sought,
            readBackSucceeded = readBack,
            renameSucceeded = renamedOk,
            cleanupSucceeded = cleanupOk,
            error = error,
        )
    }

    private fun collectStorageVolumes(context: Context): List<StorageVolumeObservation> {
        val manager = context.getSystemService(StorageManager::class.java) ?: return emptyList()
        return manager.storageVolumes.map { volume ->
            StorageVolumeObservation(
                description = runCatching { volume.getDescription(context) }.getOrDefault("unknown"),
                uuid = volume.uuid,
                state = volume.state,
                primary = volume.isPrimary,
                removable = volume.isRemovable,
                emulated = volume.isEmulated,
                directory = if (Build.VERSION.SDK_INT >= 30) volume.directory?.absolutePath else null,
                mediaStoreVolumeName = if (Build.VERSION.SDK_INT >= 30) {
                    volume.mediaStoreVolumeName
                } else if (volume.isPrimary) {
                    MediaStore.VOLUME_EXTERNAL_PRIMARY
                } else {
                    volume.uuid?.lowercase(Locale.ROOT)
                },
            )
        }
    }

    private fun collectMediaStoreVolumes(
        context: Context,
        storageVolumes: List<StorageVolumeObservation>,
        runWriteProbe: Boolean,
    ): List<MediaStoreVolumeObservation> {
        if (Build.VERSION.SDK_INT < 29) return emptyList()
        val names = runCatching { MediaStore.getExternalVolumeNames(context) }.getOrDefault(emptySet())
        return names.sorted().map { volumeName ->
            val storage = storageVolumes.firstOrNull {
                it.mediaStoreVolumeName.equals(volumeName, ignoreCase = true)
            } ?: storageVolumes.firstOrNull {
                volumeName == MediaStore.VOLUME_EXTERNAL_PRIMARY && it.primary
            } ?: storageVolumes.firstOrNull {
                it.uuid.equals(volumeName, ignoreCase = true)
            }
            val collection = MediaStore.Downloads.getContentUri(volumeName)
            var querySucceeded = false
            var queryError: String? = null
            runCatching {
                context.contentResolver.query(
                    collection,
                    arrayOf(MediaStore.MediaColumns._ID),
                    null,
                    null,
                    "${MediaStore.MediaColumns._ID} DESC",
                )?.use {
                    querySucceeded = true
                } ?: error("MediaStore returned no cursor")
            }.onFailure { queryError = describeError(it) }
            MediaStoreVolumeObservation(
                volumeName = volumeName,
                collectionUri = collection.toString(),
                storageDescription = storage?.description,
                storageUuid = storage?.uuid,
                primary = storage?.primary,
                removable = storage?.removable,
                mounted = storage?.state == Environment.MEDIA_MOUNTED,
                querySucceeded = querySucceeded,
                queryError = queryError,
                publicationProbe = if (
                    runWriteProbe && storage?.removable == true && storage.state == Environment.MEDIA_MOUNTED
                ) {
                    probeMediaStoreVolume(context, collection, volumeName)
                } else {
                    null
                },
            )
        }
    }

    private data class PendingPublishedProbeItem(
        val uri: Uri?,
        val observation: MediaStorePublishedItemObservation,
    )

    private data class ObservedMediaStoreMetadata(
        val displayName: String?,
        val relativePath: String?,
        val sizeBytes: Long?,
        val mimeType: String?,
        val pending: Int?,
        val volumeName: String?,
    )

    private fun probeMediaStoreVolume(
        context: Context,
        collection: Uri,
        volumeName: String,
    ): MediaStorePublicationProbeResult {
        val resolver = context.contentResolver
        val token = UUID.randomUUID().toString()
        val requestedName = "OpenAVM_USB_Probe_${System.currentTimeMillis()}_${token.take(8)}.bin"
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/OpenAVM/"
        val mimeType = "application/octet-stream"
        val first = createAndPublishMediaStoreItem(
            context = context,
            collection = collection,
            expectedVolumeName = volumeName,
            requestedName = requestedName,
            relativePath = relativePath,
            mimeType = mimeType,
            marker = "OpenAVM Gate A.2 first $token".toByteArray(Charsets.UTF_8),
        )
        val second = createAndPublishMediaStoreItem(
            context = context,
            collection = collection,
            expectedVolumeName = volumeName,
            requestedName = requestedName,
            relativePath = relativePath,
            mimeType = mimeType,
            marker = "OpenAVM Gate A.2 duplicate $token".toByteArray(Charsets.UTF_8),
        )
        val duplicateBehavior = when {
            !second.observation.createSucceeded -> MediaStoreDuplicateBehavior.INSERT_REJECTED
            second.observation.observedDisplayName == requestedName ->
                MediaStoreDuplicateBehavior.SAME_NAME_ALLOWED
            !second.observation.observedDisplayName.isNullOrBlank() ->
                MediaStoreDuplicateBehavior.AUTO_RENAMED
            else -> MediaStoreDuplicateBehavior.UNKNOWN
        }
        val cleanedSecond = cleanupPublishedMediaStoreItem(resolver, second)
        val cleanedFirst = cleanupPublishedMediaStoreItem(resolver, first)
        return MediaStorePublicationProbeResult(
            expectedRelativePath = relativePath,
            expectedMimeType = mimeType,
            firstItem = cleanedFirst.observation,
            duplicate = MediaStoreDuplicateObservation(
                attempted = true,
                behavior = duplicateBehavior,
                requestedDisplayName = requestedName,
                firstObservedDisplayName = first.observation.observedDisplayName,
                secondObservedDisplayName = second.observation.observedDisplayName,
                secondItemUri = second.uri?.toString(),
                secondItemDeleted = if (second.uri == null) null else cleanedSecond.observation.deletionConfirmed,
                error = second.observation.error,
            ),
        )
    }

    private fun createAndPublishMediaStoreItem(
        context: Context,
        collection: Uri,
        expectedVolumeName: String,
        requestedName: String,
        relativePath: String,
        mimeType: String,
        marker: ByteArray,
    ): PendingPublishedProbeItem {
        val resolver = context.contentResolver
        var itemUri: Uri? = null
        var created = false
        var wrote = false
        var synced = false
        var sought = false
        var readBack = false
        var publishUpdateCount: Int? = null
        var uriMetadata: ObservedMediaStoreMetadata? = null
        var collectionMetadata: ObservedMediaStoreMetadata? = null
        var error: String? = null
        try {
            itemUri = resolver.insert(
                collection,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, requestedName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                },
            ) ?: error("MediaStore did not create the probe item")
            created = true
            resolver.openFileDescriptor(itemUri, "rw")?.use { descriptor ->
                val fd = descriptor.fileDescriptor
                var writeOffset = 0
                while (writeOffset < marker.size) {
                    val count = Os.write(fd, marker, writeOffset, marker.size - writeOffset)
                    check(count > 0) { "MediaStore stopped before the marker was fully written" }
                    writeOffset += count
                }
                wrote = true
                Os.fsync(fd)
                synced = true
                Os.lseek(fd, 0L, OsConstants.SEEK_SET)
                sought = true
                val actual = ByteArray(marker.size)
                var readOffset = 0
                while (readOffset < actual.size) {
                    val count = Os.read(fd, actual, readOffset, actual.size - readOffset)
                    if (count <= 0) break
                    readOffset += count
                }
                readBack = readOffset == marker.size && actual.contentEquals(marker)
            } ?: error("MediaStore did not return a writable file descriptor")
            publishUpdateCount = resolver.update(
                itemUri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            uriMetadata = queryMediaStoreMetadata(resolver, itemUri)
            val itemId = ContentUris.parseId(itemUri)
            collectionMetadata = queryMediaStoreMetadata(
                resolver = resolver,
                uri = collection,
                selection = "${MediaStore.MediaColumns._ID}=?",
                selectionArgs = arrayOf(itemId.toString()),
            )
        } catch (t: Throwable) {
            error = describeError(t)
        }
        val observed = uriMetadata
        val metadataMatched = observed != null &&
            observed.displayName == requestedName &&
            normalizeRelativePath(observed.relativePath) == normalizeRelativePath(relativePath) &&
            observed.sizeBytes == marker.size.toLong() &&
            observed.mimeType == mimeType &&
            observed.pending == 0 &&
            observed.volumeName.equals(expectedVolumeName, ignoreCase = true)
        return PendingPublishedProbeItem(
            uri = itemUri,
            observation = MediaStorePublishedItemObservation(
                requestedDisplayName = requestedName,
                itemUri = itemUri?.toString(),
                createSucceeded = created,
                writeSucceeded = wrote,
                syncSucceeded = synced,
                seekSucceeded = sought,
                readBackSucceeded = readBack,
                publishUpdateCount = publishUpdateCount,
                uriQuerySucceeded = uriMetadata != null,
                collectionQuerySucceeded = collectionMetadata != null,
                observedDisplayName = observed?.displayName,
                observedRelativePath = observed?.relativePath,
                observedSizeBytes = observed?.sizeBytes,
                observedMimeType = observed?.mimeType,
                observedPending = observed?.pending,
                observedVolumeName = observed?.volumeName,
                metadataMatched = metadataMatched,
                error = error,
            ),
        )
    }

    private fun cleanupPublishedMediaStoreItem(
        resolver: android.content.ContentResolver,
        item: PendingPublishedProbeItem,
    ): PendingPublishedProbeItem {
        val uri = item.uri ?: return item
        val deleteCount = runCatching { resolver.delete(uri, null, null) }.getOrDefault(0)
        val deletionConfirmed = runCatching {
            resolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns._ID),
                null,
                null,
                null,
            )?.use { cursor -> !cursor.moveToFirst() } ?: false
        }.getOrDefault(false)
        return item.copy(
            observation = item.observation.copy(
                deleteCount = deleteCount,
                deletionConfirmed = deletionConfirmed,
            ),
        )
    }

    private fun queryMediaStoreMetadata(
        resolver: android.content.ContentResolver,
        uri: Uri,
        selection: String? = null,
        selectionArgs: Array<String>? = null,
    ): ObservedMediaStoreMetadata? = resolver.query(
        uri,
        arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.IS_PENDING,
            MediaStore.MediaColumns.VOLUME_NAME,
        ),
        selection,
        selectionArgs,
        null,
    )?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        fun stringValue(column: String): String? {
            val index = cursor.getColumnIndex(column)
            return if (index >= 0 && !cursor.isNull(index)) cursor.getString(index) else null
        }
        fun longValue(column: String): Long? {
            val index = cursor.getColumnIndex(column)
            return if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else null
        }
        ObservedMediaStoreMetadata(
            displayName = stringValue(MediaStore.MediaColumns.DISPLAY_NAME),
            relativePath = stringValue(MediaStore.MediaColumns.RELATIVE_PATH),
            sizeBytes = longValue(MediaStore.MediaColumns.SIZE),
            mimeType = stringValue(MediaStore.MediaColumns.MIME_TYPE),
            pending = longValue(MediaStore.MediaColumns.IS_PENDING)?.toInt(),
            volumeName = stringValue(MediaStore.MediaColumns.VOLUME_NAME),
        )
    }

    private fun normalizeRelativePath(path: String?): String? =
        path?.trim()?.replace('\\', '/')?.trim('/')?.lowercase(Locale.ROOT)

    private fun collectMounts(): List<MountObservation> = runCatching {
        UsbMountTableParser.parse(File("/proc/mounts").readText())
    }.getOrDefault(emptyList())

    private fun collectSafTree(context: Context, treeUri: Uri, runWriteProbe: Boolean): SafTreeObservation {
        val resolver = context.contentResolver
        val permission = resolver.persistedUriPermissions.firstOrNull { it.uri == treeUri }
        val rootUri = runCatching {
            DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        }.getOrNull()
        var documentId: String? = null
        var displayName: String? = null
        var flags: Long? = null
        var queryError: String? = null
        if (rootUri != null) {
            runCatching {
                resolver.query(
                    rootUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_FLAGS,
                    ),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        documentId = cursor.getString(0)
                        displayName = cursor.getString(1)
                        flags = cursor.getLong(2)
                    }
                }
            }.onFailure { queryError = describeError(it) }
        } else {
            queryError = "Cannot build the selected tree document URI"
        }
        val rawFlags = flags?.toInt()
        return SafTreeObservation(
            uri = treeUri.toString(),
            documentId = documentId,
            displayName = displayName,
            providerFlags = flags,
            persistedRead = permission?.isReadPermission == true,
            persistedWrite = permission?.isWritePermission == true,
            supportsCreate = rawFlags?.let {
                it and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0
            },
            supportsRename = rawFlags?.let {
                it and DocumentsContract.Document.FLAG_SUPPORTS_RENAME != 0
            },
            writeProbe = if (runWriteProbe && rootUri != null) probeSafTree(context, rootUri) else null,
            error = queryError,
        )
    }

    private fun probeSafTree(context: Context, rootUri: Uri): UsbWriteProbeResult {
        val resolver = context.contentResolver
        val token = UUID.randomUUID().toString()
        val marker = "OpenAVM USB probe $token".toByteArray(Charsets.UTF_8)
        var directoryUri: Uri? = null
        var fileUri: Uri? = null
        var created = false
        var wrote = false
        var synced = false
        var sought = false
        var readBack = false
        var renamedOk = false
        var error: String? = null
        try {
            directoryUri = DocumentsContract.createDocument(
                resolver,
                rootUri,
                DocumentsContract.Document.MIME_TYPE_DIR,
                ".openavm-usb-probe-$token",
            ) ?: error("Provider did not create the probe directory")
            fileUri = DocumentsContract.createDocument(
                resolver,
                directoryUri,
                "application/octet-stream",
                "probe.tmp",
            ) ?: error("Provider did not create the probe file")
            created = true
            resolver.openFileDescriptor(fileUri, "rw")?.use { descriptor ->
                val fd = descriptor.fileDescriptor
                var writeOffset = 0
                while (writeOffset < marker.size) {
                    val count = Os.write(fd, marker, writeOffset, marker.size - writeOffset)
                    check(count > 0) { "Provider stopped before the marker was fully written" }
                    writeOffset += count
                }
                wrote = true
                Os.fsync(fd)
                synced = true
                Os.lseek(fd, 0L, OsConstants.SEEK_SET)
                sought = true
                val actual = ByteArray(marker.size)
                var readOffset = 0
                while (readOffset < actual.size) {
                    val count = Os.read(fd, actual, readOffset, actual.size - readOffset)
                    if (count <= 0) break
                    readOffset += count
                }
                readBack = readOffset == marker.size && actual.contentEquals(marker)
            } ?: error("Provider did not return a writable file descriptor")
            val renamedUri = DocumentsContract.renameDocument(resolver, fileUri, "probe.verified")
            renamedOk = renamedUri != null
            if (renamedUri != null) fileUri = renamedUri
        } catch (t: Throwable) {
            error = describeError(t)
        }
        var cleanupOk = true
        val exactFile = fileUri
        if (exactFile != null) {
            cleanupOk = runCatching { DocumentsContract.deleteDocument(resolver, exactFile) }.getOrDefault(false)
        }
        val exactDirectory = directoryUri
        if (exactDirectory != null) {
            cleanupOk = runCatching { DocumentsContract.deleteDocument(resolver, exactDirectory) }
                .getOrDefault(false) && cleanupOk
        }
        return UsbWriteProbeResult(
            createSucceeded = created,
            writeSucceeded = wrote,
            syncSucceeded = synced,
            seekSucceeded = sought,
            readBackSucceeded = readBack,
            renameSucceeded = renamedOk,
            cleanupSucceeded = cleanupOk && (fileUri != null || directoryUri != null),
            error = error,
        )
    }

    private fun describeError(t: Throwable): String = buildString {
        append(t.javaClass.simpleName)
        t.message?.takeIf(String::isNotBlank)?.let { append(": ").append(it.take(240)) }
    }

    private const val PREFS = "usb_storage_probe_v1"
    private const val KEY_TREE_URI = "tree_uri"
}
