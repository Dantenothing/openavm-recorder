package com.dante.zeekrcapabilitylab.diagnostic

import android.Manifest
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.dante.zeekrcapabilitylab.BuildConfig
import com.dante.zeekrcapabilitylab.usbexport.UsbExportCatalog
import com.dante.zeekrcapabilitylab.usbexport.UsbExportPolicy
import com.dante.zeekrcapabilitylab.usbexport.UsbExportTarget
import com.dante.zeekrcapabilitylab.usbexport.UsbExportVolumeResolver
import com.dante.zeekrcapabilitylab.usbexport.UsbMediaStoreBackend
import java.io.File
import java.util.ArrayDeque
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class UsbInventorySource { MEDIASTORE, RAW_ONLY }

@Serializable
data class UsbVideoInventoryItem(
    val stableKey: String,
    val itemUri: String? = null,
    val displayName: String,
    val relativePath: String,
    val sizeBytes: Long,
    val modifiedEpochMs: Long,
    val durationMs: Long? = null,
    val mimeType: String? = null,
    val ownerPackage: String? = null,
    val trashed: Boolean? = null,
    val source: UsbInventorySource,
    val openAvm: Boolean,
)

@Serializable
data class UsbDirectoryUsage(
    val name: String,
    val fileCount: Int,
    val bytes: Long,
)

@Serializable
data class UsbLargestFile(
    val relativePath: String,
    val bytes: Long,
    val modifiedEpochMs: Long,
)

@Serializable
data class UsbDeletionObservation(
    val requestedCount: Int,
    val requestedBytes: Long,
    val userApproved: Boolean,
    val removedCount: Int,
    val freeBytesBefore: Long?,
    val freeBytesAfter: Long?,
    val error: String? = null,
)

@Serializable
data class UsbAllFilesSettingsCapability(
    val appSpecificHandlerAvailable: Boolean = false,
    val globalHandlerAvailable: Boolean = false,
    val launchAttempted: Boolean = false,
    val launchDispatchSucceeded: Boolean = false,
    val resultReturned: Boolean = false,
    val launchError: String? = null,
)

@Serializable
data class UsbStorageInventoryReport(
    val schemaVersion: Int = 2,
    val generatedAtEpochMs: Long,
    val buildVersion: String,
    val buildGitSha: String,
    val androidSdkInt: Int,
    val targetDescription: String? = null,
    val targetUuid: String? = null,
    val targetVolumeName: String? = null,
    val targetDirectory: String? = null,
    val totalBytes: Long? = null,
    val usedBytes: Long? = null,
    val freeBytes: Long? = null,
    val openAvmQuotaBytes: Long = UsbExportPolicy.OPENAVM_QUOTA_BYTES,
    val openAvmOwnedBytes: Long? = null,
    val openAvmNamespaceBytes: Long? = null,
    val openAvmDeletionEligibleBytes: Long? = null,
    val videoReadPermissionGranted: Boolean,
    val allFilesAccessGranted: Boolean,
    val allFilesSettings: UsbAllFilesSettingsCapability = UsbAllFilesSettingsCapability(),
    val directAccessProbe: UsbDirectAccessProbe? = null,
    val mediaStoreQuerySucceeded: Boolean,
    val mediaStoreQueryError: String? = null,
    val mediaStoreVideoCount: Int,
    val mediaStoreVideoBytes: Long,
    val rawScanAttempted: Boolean,
    val rawScanCompleted: Boolean,
    val rawScanTruncated: Boolean,
    val rawScannedEntries: Int,
    val rawUnreadableDirectories: Int = 0,
    val rawScanError: String? = null,
    val rawScannedFileBytes: Long? = null,
    val unaccountedBytesEstimate: Long? = null,
    val topLevelDirectories: List<UsbDirectoryUsage> = emptyList(),
    val largestFiles: List<UsbLargestFile> = emptyList(),
    val videos: List<UsbVideoInventoryItem> = emptyList(),
    val sentryDirectoryFound: Boolean = false,
    val sentryScanCompleted: Boolean = false,
    val sentryScanTruncated: Boolean = false,
    val sentryInvalidDirectoryCount: Int = 0,
    val sentryEventCount: Int = 0,
    val sentryCompleteEventCount: Int = 0,
    val sentryBytes: Long = 0L,
    val sentryScanError: String? = null,
    val sentryEvents: List<UsbSentryEvent> = emptyList(),
    val latestDeletion: UsbDeletionObservation? = null,
    val notes: List<String> = emptyList(),
)

object UsbStorageInventory {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    suspend fun collect(
        context: Context,
        includeRawScan: Boolean,
    ): UsbStorageInventoryReport = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val target = UsbExportVolumeResolver.mountedTargets(appContext).firstOrNull()
        if (target == null) {
            return@withContext save(
                appContext,
                emptyReport(appContext, "No mounted removable MediaStore USB was found"),
            )
        }
        val total = target.totalBytes
        val free = target.freeBytes
        val used = if (total != null && free != null) (total - free).coerceAtLeast(0L) else null
        val permissionGranted = hasVideoReadPermission(appContext)
        val media = queryMediaStoreVideos(appContext, target, permissionGranted)
        val rawGranted = hasAllFilesAccess(target)
        val currentSettings = allFilesSettingsCapability(appContext)
        val previousSettings = loadLatest(appContext)?.allFilesSettings
        val settings = currentSettings.copy(
            launchAttempted = previousSettings?.launchAttempted ?: false,
            launchDispatchSucceeded = previousSettings?.launchDispatchSucceeded ?: false,
            resultReturned = previousSettings?.resultReturned ?: false,
            launchError = previousSettings?.launchError,
        )
        val directAccess = ZeekrSentryInventory.probe(target)
        val raw = if (includeRawScan) {
            scanRaw(target, media.videos)
        } else {
            RawScanResult(attempted = includeRawScan, completed = false)
        }
        val catalog = runCatching {
            val backend = UsbMediaStoreBackend(appContext)
            UsbExportCatalog(appContext, backend).snapshot(target)
        }.getOrNull()
        val combinedVideos = (media.videos + raw.rawOnlyVideos)
            .distinctBy { it.stableKey }
            .sortedWith(
                compareByDescending<UsbVideoInventoryItem> { it.modifiedEpochMs }
                    .thenBy { it.relativePath.lowercase(Locale.ROOT) },
            )
        val unaccounted = if (raw.completed && used != null) {
            (used - raw.fileBytes).coerceAtLeast(0L)
        } else {
            null
        }
        save(
            appContext,
            UsbStorageInventoryReport(
                generatedAtEpochMs = System.currentTimeMillis(),
                buildVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                buildGitSha = BuildConfig.GIT_SHA,
                androidSdkInt = Build.VERSION.SDK_INT,
                targetDescription = target.description,
                targetUuid = target.storageUuid,
                targetVolumeName = target.volumeName,
                targetDirectory = target.directoryPath,
                totalBytes = total,
                usedBytes = used,
                freeBytes = free,
                openAvmOwnedBytes = catalog?.ownedBytes,
                openAvmNamespaceBytes = catalog?.namespaceBytes,
                openAvmDeletionEligibleBytes = catalog?.completeExports?.sumOf { it.existingBytes },
                videoReadPermissionGranted = permissionGranted,
                allFilesAccessGranted = rawGranted,
                allFilesSettings = settings,
                directAccessProbe = directAccess,
                mediaStoreQuerySucceeded = media.error == null,
                mediaStoreQueryError = media.error,
                mediaStoreVideoCount = media.videos.size,
                mediaStoreVideoBytes = media.videos.sumOf { it.sizeBytes },
                rawScanAttempted = raw.attempted,
                rawScanCompleted = raw.completed,
                rawScanTruncated = raw.truncated,
                rawScannedEntries = raw.scannedEntries,
                rawUnreadableDirectories = raw.unreadableDirectories,
                rawScanError = raw.error,
                rawScannedFileBytes = raw.fileBytes.takeIf { raw.attempted },
                unaccountedBytesEstimate = unaccounted,
                topLevelDirectories = raw.directories,
                largestFiles = raw.largestFiles,
                videos = combinedVideos,
                sentryDirectoryFound = raw.sentry.directoryFound,
                sentryScanCompleted = raw.sentry.completed,
                sentryScanTruncated = raw.sentry.truncated,
                sentryInvalidDirectoryCount = raw.sentry.invalidDirectoryCount,
                sentryEventCount = raw.sentry.events.size,
                sentryCompleteEventCount = raw.sentry.events.count { it.complete },
                sentryBytes = raw.sentry.events.sumOf { it.totalBytes },
                sentryScanError = raw.sentry.error,
                sentryEvents = raw.sentry.events,
                notes = buildList {
                    add("B1.2.1 inventory: capacity, exact SentryMode events, and metadata only; no raw-path write or delete was attempted")
                    add("Direct read capability is measured separately from Android all-files settings availability")
                    add("Sentry location coordinates are validated in memory but redacted from copied reports")
                    if (!permissionGranted) add("MediaStore foreign-video visibility requires the Android video read permission")
                    if (includeRawScan && !rawGranted) add("Raw scan was attempted without all-files status to measure actual App Lab read capability")
                    if (raw.truncated) add("Raw scan stopped at the safety entry limit")
                },
            ),
        )
    }

    suspend fun recordDeletionResult(
        context: Context,
        before: UsbStorageInventoryReport,
        requested: List<UsbVideoInventoryItem>,
        userApproved: Boolean,
        launchError: String? = null,
    ): UsbStorageInventoryReport {
        val after = collect(context, includeRawScan = before.rawScanAttempted)
        val remaining = after.videos.mapNotNullTo(mutableSetOf()) { it.itemUri }
        val removed = if (after.mediaStoreQuerySucceeded) {
            requested.count { it.itemUri != null && it.itemUri !in remaining }
        } else {
            0
        }
        val observation = UsbDeletionObservation(
            requestedCount = requested.size,
            requestedBytes = requested.sumOf { it.sizeBytes },
            userApproved = userApproved,
            removedCount = removed,
            freeBytesBefore = before.freeBytes,
            freeBytesAfter = after.freeBytes,
            error = launchError,
        )
        return save(context.applicationContext, after.copy(latestDeletion = observation))
    }

    fun createDeleteRequest(
        context: Context,
        items: List<UsbVideoInventoryItem>,
    ): PendingIntent {
        require(items.isNotEmpty() && items.size <= MAX_DELETE_ITEMS) {
            "Select between 1 and $MAX_DELETE_ITEMS videos"
        }
        val uris = items.map { item ->
            require(item.source == UsbInventorySource.MEDIASTORE && item.itemUri != null) {
                "Raw-path files cannot use Android media deletion confirmation"
            }
            require(!item.openAvm) { "OpenAVM exports are managed only by the quota engine" }
            require(!normalize(item.relativePath).contains("download/openavm")) {
                "Items in the OpenAVM directory cannot use this media deletion flow"
            }
            require(item.mimeType?.startsWith("video/", ignoreCase = true) == true || isVideo(item.displayName)) {
                "Only video items can use this media deletion flow"
            }
            Uri.parse(item.itemUri).also { uri ->
                require(uri.scheme == ContentResolver.SCHEME_CONTENT && uri.authority == MediaStore.AUTHORITY) {
                    "Only exact MediaStore item URIs can be deleted"
                }
                require(uri.lastPathSegment?.toLongOrNull() != null) {
                    "A collection URI cannot be deleted"
                }
            }
        }
        require(uris.distinct().size == uris.size) { "Duplicate MediaStore items are not allowed" }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            MediaStore.createDeleteRequest(context.contentResolver, uris)
        } else {
            throw UnsupportedOperationException(
                "Android media deletion confirmation requires Android 11 or newer",
            )
        }
    }

    fun requiredVideoPermission(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasVideoReadPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, requiredVideoPermission()) == PackageManager.PERMISSION_GRANTED

    fun allFilesSettingsIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < 30) return null
        val appIntent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
        if (appIntent.resolveActivity(context.packageManager) != null) return appIntent
        val globalIntent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        return globalIntent.takeIf { it.resolveActivity(context.packageManager) != null }
    }

    fun allFilesSettingsCapability(context: Context): UsbAllFilesSettingsCapability {
        if (Build.VERSION.SDK_INT < 30) return UsbAllFilesSettingsCapability()
        val appIntent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
        val globalIntent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        return UsbAllFilesSettingsCapability(
            appSpecificHandlerAvailable = appIntent.resolveActivity(context.packageManager) != null,
            globalHandlerAvailable = globalIntent.resolveActivity(context.packageManager) != null,
        )
    }

    fun recordAllFilesSettingsLaunch(
        context: Context,
        before: UsbStorageInventoryReport,
        dispatchSucceeded: Boolean,
        resultReturned: Boolean,
        error: String? = null,
    ): UsbStorageInventoryReport = save(
        context.applicationContext,
        before.copy(
            allFilesSettings = before.allFilesSettings.copy(
                launchAttempted = true,
                launchDispatchSucceeded = dispatchSucceeded,
                resultReturned = resultReturned,
                launchError = error,
            ),
        ),
    )

    fun reportJson(report: UsbStorageInventoryReport): String = json.encodeToString(report)

    fun loadLatest(context: Context): UsbStorageInventoryReport? = runCatching {
        val file = latestReportFile(context)
        if (!file.isFile) null else json.decodeFromString(UsbStorageInventoryReport.serializer(), file.readText())
    }.getOrNull()

    fun latestReportFile(context: Context): File =
        File(context.applicationContext.filesDir, "diagnostics/usb-storage-b1.2.1-latest.json")

    private fun emptyReport(context: Context, note: String) = UsbStorageInventoryReport(
        generatedAtEpochMs = System.currentTimeMillis(),
        buildVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        buildGitSha = BuildConfig.GIT_SHA,
        androidSdkInt = Build.VERSION.SDK_INT,
        videoReadPermissionGranted = hasVideoReadPermission(context),
        allFilesAccessGranted = Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager(),
        mediaStoreQuerySucceeded = false,
        mediaStoreQueryError = note,
        mediaStoreVideoCount = 0,
        mediaStoreVideoBytes = 0L,
        rawScanAttempted = false,
        rawScanCompleted = false,
        rawScanTruncated = false,
        rawScannedEntries = 0,
        notes = listOf(note),
    )

    private fun save(context: Context, report: UsbStorageInventoryReport): UsbStorageInventoryReport {
        val target = latestReportFile(context)
        target.parentFile?.mkdirs()
        val temporary = File(target.absolutePath + ".tmp")
        temporary.writeText(reportJson(report))
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
        return report
    }

    private data class MediaQueryResult(
        val videos: List<UsbVideoInventoryItem>,
        val error: String? = null,
    )

    private fun queryMediaStoreVideos(
        context: Context,
        target: UsbExportTarget,
        permissionGranted: Boolean,
    ): MediaQueryResult {
        if (!permissionGranted) return MediaQueryResult(emptyList(), "VIDEO_READ_PERMISSION_NOT_GRANTED")
        val collection = MediaStore.Video.Media.getContentUri(target.volumeName)
        val richProjection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.Video.VideoColumns.DURATION,
            MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
            MediaStore.MediaColumns.IS_TRASHED,
        )
        val basicProjection = richProjection.take(7).toTypedArray()
        return runCatching {
            queryVideos(context, target, collection, richProjection, includeTrashed = true)
        }.recoverCatching {
            queryVideos(context, target, collection, basicProjection, includeTrashed = false)
        }.fold(
            onSuccess = { MediaQueryResult(it) },
            onFailure = { MediaQueryResult(emptyList(), describe(it)) },
        )
    }

    private fun queryVideos(
        context: Context,
        target: UsbExportTarget,
        collection: Uri,
        projection: Array<String>,
        includeTrashed: Boolean,
    ): List<UsbVideoInventoryItem> {
        val cursor = if (includeTrashed && Build.VERSION.SDK_INT >= 30) {
            val args = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")
                putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
            }
            context.contentResolver.query(collection, projection, args, null)
        } else {
            context.contentResolver.query(
                collection,
                projection,
                null,
                null,
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
            )
        } ?: error("MediaStore returned no cursor")
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    fun string(column: String): String? {
                        val index = it.getColumnIndex(column)
                        return if (index >= 0 && !it.isNull(index)) it.getString(index) else null
                    }
                    fun long(column: String): Long? {
                        val index = it.getColumnIndex(column)
                        return if (index >= 0 && !it.isNull(index)) it.getLong(index) else null
                    }
                    val id = long(MediaStore.MediaColumns._ID) ?: continue
                    val name = string(MediaStore.MediaColumns.DISPLAY_NAME) ?: "video-$id"
                    val path = string(MediaStore.MediaColumns.RELATIVE_PATH).orEmpty()
                    val uri = ContentUris.withAppendedId(collection, id)
                    add(
                        UsbVideoInventoryItem(
                            stableKey = "media:${target.volumeName}:$id",
                            itemUri = uri.toString(),
                            displayName = name,
                            relativePath = path + name,
                            sizeBytes = long(MediaStore.MediaColumns.SIZE) ?: 0L,
                            modifiedEpochMs = (long(MediaStore.MediaColumns.DATE_MODIFIED) ?: 0L) * 1000L,
                            durationMs = long(MediaStore.Video.VideoColumns.DURATION),
                            mimeType = string(MediaStore.MediaColumns.MIME_TYPE),
                            ownerPackage = string(MediaStore.MediaColumns.OWNER_PACKAGE_NAME),
                            trashed = long(MediaStore.MediaColumns.IS_TRASHED)?.let { value -> value != 0L },
                            source = UsbInventorySource.MEDIASTORE,
                            openAvm = isOpenAvm(path, name),
                        ),
                    )
                }
            }
        }
    }

    private data class RawScanResult(
        val attempted: Boolean,
        val completed: Boolean,
        val truncated: Boolean = false,
        val scannedEntries: Int = 0,
        val unreadableDirectories: Int = 0,
        val error: String? = null,
        val fileBytes: Long = 0L,
        val directories: List<UsbDirectoryUsage> = emptyList(),
        val largestFiles: List<UsbLargestFile> = emptyList(),
        val rawOnlyVideos: List<UsbVideoInventoryItem> = emptyList(),
        val sentry: UsbSentryScanResult = UsbSentryScanResult(
            attempted = false,
            completed = false,
        ),
    )

    private suspend fun scanRaw(
        target: UsbExportTarget,
        mediaVideos: List<UsbVideoInventoryItem>,
    ): RawScanResult {
        val root = target.directoryPath?.let(::File)
            ?: return RawScanResult(
                attempted = true,
                completed = false,
                error = "TARGET_DIRECTORY_UNAVAILABLE",
            )
        val rootEntries = try {
            root.listFiles() ?: error("listFiles returned null")
        } catch (t: Throwable) {
            return RawScanResult(
                attempted = true,
                completed = false,
                error = "ROOT_LIST:${describe(t)}",
            )
        }
        val sentry = ZeekrSentryInventory.scan(root, target.storageUuid)
        val mediaPaths = mediaVideos.mapTo(mutableSetOf()) { normalize(it.relativePath) }
        val stack = ArrayDeque<Pair<File, Int>>()
        rootEntries.forEach { stack.addLast(it to 1) }
        val directoryBytes = mutableMapOf<String, Long>()
        val directoryCounts = mutableMapOf<String, Int>()
        val largest = mutableListOf<UsbLargestFile>()
        val rawOnlyVideos = mutableListOf<UsbVideoInventoryItem>()
        var scanned = 0
        var totalBytes = 0L
        var truncated = false
        var unreadableDirectories = 0
        while (stack.isNotEmpty()) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (scanned >= MAX_RAW_ENTRIES) {
                truncated = true
                break
            }
            val (entry, depth) = stack.removeLast()
            scanned++
            val relative = runCatching {
                entry.relativeTo(root).invariantSeparatorsPath
            }.getOrNull() ?: continue
            if (entry.isDirectory) {
                if (depth < MAX_RAW_DEPTH && !relative.equals("Android/data", ignoreCase = true)) {
                    val children = runCatching { entry.listFiles() }.getOrNull()
                    if (children == null) {
                        unreadableDirectories++
                    } else {
                        children.forEach { stack.addLast(it to depth + 1) }
                    }
                }
                continue
            }
            if (!entry.isFile) continue
            val bytes = runCatching { entry.length() }.getOrDefault(0L)
            totalBytes += bytes
            val top = relative.substringBefore('/').ifBlank { "(root)" }
            directoryBytes[top] = (directoryBytes[top] ?: 0L) + bytes
            directoryCounts[top] = (directoryCounts[top] ?: 0) + 1
            largest += UsbLargestFile(relative, bytes, entry.lastModified())
            if (largest.size > MAX_LARGEST_FILES * 2) {
                largest.sortByDescending { it.bytes }
                largest.subList(MAX_LARGEST_FILES, largest.size).clear()
            }
            if (isVideo(entry.name) && normalize(relative) !in mediaPaths) {
                rawOnlyVideos += UsbVideoInventoryItem(
                    stableKey = "raw:${target.storageUuid}:${normalize(relative)}",
                    displayName = entry.name,
                    relativePath = relative,
                    sizeBytes = bytes,
                    modifiedEpochMs = entry.lastModified(),
                    mimeType = null,
                    source = UsbInventorySource.RAW_ONLY,
                    openAvm = isOpenAvm(relative.substringBeforeLast('/', ""), entry.name),
                )
            }
        }
        largest.sortByDescending { it.bytes }
        return RawScanResult(
            attempted = true,
            completed = !truncated && unreadableDirectories == 0,
            truncated = truncated,
            scannedEntries = scanned,
            unreadableDirectories = unreadableDirectories,
            error = sentry.error,
            fileBytes = totalBytes,
            directories = directoryBytes.map { (name, bytes) ->
                UsbDirectoryUsage(name, directoryCounts[name] ?: 0, bytes)
            }.sortedByDescending { it.bytes },
            largestFiles = largest.take(MAX_LARGEST_FILES),
            rawOnlyVideos = rawOnlyVideos,
            sentry = sentry,
        )
    }

    private fun hasAllFilesAccess(target: UsbExportTarget): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val directory = target.directoryPath?.let(::File)
        return runCatching {
            if (directory != null) Environment.isExternalStorageManager(directory)
            else Environment.isExternalStorageManager()
        }.getOrDefault(false)
    }

    private fun isOpenAvm(path: String, name: String): Boolean =
        normalize(path).contains("download/openavm") &&
            name.startsWith("OpenAVM_", ignoreCase = true)

    private fun isVideo(name: String): Boolean =
        name.endsWith(".mp4", true) ||
            name.endsWith(".mov", true) ||
            name.endsWith(".m4v", true) ||
            name.endsWith(".ts", true)

    private fun normalize(value: String): String =
        value.trim().replace('\\', '/').trim('/').lowercase(Locale.ROOT)

    private fun describe(t: Throwable): String =
        "${t.javaClass.simpleName}: ${t.message.orEmpty().take(240)}"

    private const val MAX_DELETE_ITEMS = 100
    private const val MAX_RAW_ENTRIES = 20_000
    private const val MAX_RAW_DEPTH = 12
    private const val MAX_LARGEST_FILES = 50
}
