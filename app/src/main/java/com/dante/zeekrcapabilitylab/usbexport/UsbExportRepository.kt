package com.dante.zeekrcapabilitylab.usbexport

import android.content.Context
import com.dante.zeekrcapabilitylab.BuildConfig
import com.dante.zeekrcapabilitylab.product.SettingsStore
import com.dante.zeekrcapabilitylab.service.recorder.RecorderLibrary
import com.dante.zeekrcapabilitylab.service.recorder.RecorderStorageLock
import com.dante.zeekrcapabilitylab.service.recorder.SegmentSidecarIO
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object UsbExportRepository {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }
    private val lock = Any()
    private lateinit var appContext: Context
    private lateinit var filesRoot: File
    private val _tasks = MutableStateFlow<List<UsbExportTask>>(emptyList())
    val tasks = _tasks.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        filesRoot = appContext.filesDir
        val loaded = load().map { task ->
            if (task.state in ACTIVE_STATES || task.state == UsbExportState.CANCEL_REQUESTED) {
                task.copy(
                    state = UsbExportState.FAILED_RECOVERABLE,
                    errorCode = "PROCESS_RESTARTED",
                    message = "Export was interrupted; reconnect the same USB and retry cleanup",
                    updatedAtEpochMs = System.currentTimeMillis(),
                )
            } else {
                task
            }
        }
        _tasks.value = loaded
        UsbExportPinRegistry.restore(emptyMap())
        saveLocked()
    }

    fun enqueue(
        logicalId: String,
        recordingMode: String,
        startedAtEpochMs: Long,
        stoppedAtEpochMs: Long,
        files: List<File>,
        target: UsbExportTarget,
    ): Result<String> {
        if (!::appContext.isInitialized) return Result.failure(IllegalStateException("USB export is not initialized"))
        val unique = files.distinctBy { it.absolutePath }
        if (unique.isEmpty()) return Result.failure(IllegalArgumentException("No finalized recording selected"))
        val selectedPaths = unique.mapTo(mutableSetOf(), File::getAbsolutePath)
        var createdTaskIds = emptyList<String>()
        val result = try {
            synchronized(lock) {
                synchronized(RecorderStorageLock.lock) storage@ {
                    if (_tasks.value.any { !it.terminal() && it.sources.any { source -> source.path in selectedPaths } }) {
                        return@storage Result.failure(IllegalStateException("A selected file already has an active USB export"))
                    }
                    val snapshots = unique.map { file ->
                        if (!RecorderLibrary.isManaged(file)) {
                            return@storage Result.failure(IllegalArgumentException("Recording is not finalized"))
                        }
                        val sidecarFile = SegmentSidecarIO.sidecarFileFor(file)
                        val sidecar = SegmentSidecarIO.read(sidecarFile)
                            ?: return@storage Result.failure(IllegalArgumentException("Recording sidecar is unavailable"))
                        file to UsbExportSourceSnapshot(
                            path = file.absolutePath,
                            fileName = file.name,
                            sizeBytes = file.length(),
                            lastModifiedMs = file.lastModified(),
                            segmentNumber = sidecar.segmentNumber,
                            sidecarFileName = sidecarFile.name,
                            sidecarJson = sidecarFile.readText(),
                        )
                    }.sortedBy { it.second.segmentNumber }
                    val quota = SettingsStore.get(appContext).usbQuotaBytes
                    val tasks = snapshots.map { (_, snapshot) ->
                        UsbExportTask(
                            id = UUID.randomUUID().toString(),
                            logicalId = logicalId,
                            recordingMode = recordingMode,
                            startedAtEpochMs = startedAtEpochMs,
                            stoppedAtEpochMs = stoppedAtEpochMs,
                            target = target,
                            sources = listOf(snapshot),
                            bundleFormat = UsbExportBundleFormat.SEGMENT_BUNDLE,
                            openAvmQuotaBytes = quota,
                        )
                    }
                    val previous = _tasks.value
                    createdTaskIds = tasks.map { it.id }
                    try {
                        tasks.forEachIndexed { index, task ->
                            UsbExportPinRegistry.acquire(task.id, listOf(snapshots[index].first))
                        }
                        _tasks.value = previous + tasks
                        saveLocked()
                        Result.success(tasks.first().id)
                    } catch (t: Throwable) {
                        _tasks.value = previous
                        tasks.forEach { UsbExportPinRegistry.release(it.id) }
                        Result.failure(t)
                    }
                }
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
        val id = result.getOrNull()
        if (id != null) {
            val startFailure = runCatching { UsbExportService.start(appContext) }.exceptionOrNull()
            if (startFailure != null) {
                createdTaskIds.forEach { taskId ->
                    UsbExportPinRegistry.release(taskId)
                    get(taskId)?.let {
                        update(
                            it.copy(
                                state = UsbExportState.FAILED_RECOVERABLE,
                                errorCode = "SERVICE_START_FAILED",
                                message = "Unable to start USB export service: ${startFailure.javaClass.simpleName}",
                            ),
                        )
                    }
                }
                return Result.failure(startFailure)
            }
        }
        return result
    }

    fun nextRunnable(): UsbExportTask? = synchronized(lock) {
        _tasks.value.firstOrNull { it.state in setOf(UsbExportState.QUEUED, UsbExportState.CANCEL_REQUESTED) }
    }

    fun get(id: String): UsbExportTask? = _tasks.value.firstOrNull { it.id == id }

    fun trustedProviderOwners(target: UsbExportTarget): Set<String> = synchronized(lock) {
        _tasks.value.asSequence()
            .filter { task -> isTrustedCompletedTask(task, target) }
            .flatMap { task -> task.assets.asSequence() }
            .mapNotNull(UsbExportAsset::observedOwnerPackage)
            .toSet()
    }

    fun trustedCompletedExportKeys(target: UsbExportTarget): Set<String> = synchronized(lock) {
        _tasks.value.asSequence()
            .filter { task -> isTrustedCompletedTask(task, target) }
            .mapNotNull(UsbExportTask::exportKey)
            .filter { it.length == 64 }
            .toSet()
    }

    fun update(task: UsbExportTask) = synchronized(lock) {
        replaceLocked(task.copy(updatedAtEpochMs = System.currentTimeMillis()))
    }

    fun requestCancel(id: String) {
        synchronized(lock) {
            val task = get(id) ?: return
            if (task.terminal()) return
            replaceLocked(task.copy(state = UsbExportState.CANCEL_REQUESTED, message = "Cancellation requested"))
        }
        UsbExportService.start(appContext)
    }

    /** Keeps durable ownership evidence while acknowledging this and older finished cards. */
    fun dismissStatusCard(id: String): Boolean = synchronized(lock) {
        val task = get(id) ?: return@synchronized false
        if (!task.statusCardDismissible()) return@synchronized false
        val cutoff = task.updatedAtEpochMs
        _tasks.value = _tasks.value.map { candidate ->
            if (candidate.terminal() && candidate.updatedAtEpochMs <= cutoff) {
                candidate.copy(statusCardDismissed = true)
            } else {
                candidate
            }
        }
        saveLocked()
        true
    }

    fun retry(id: String) {
        synchronized(lock) {
            val task = get(id) ?: return
            if (task.state !in setOf(
                    UsbExportState.WAITING_FOR_TARGET,
                    UsbExportState.FAILED_RECOVERABLE,
                    UsbExportState.FAILED,
                )
            ) return
            replaceLocked(
                task.copy(
                    state = UsbExportState.QUEUED,
                    errorCode = null,
                    message = null,
                    progressBytes = 0L,
                    statusCardDismissed = false,
                ),
            )
        }
        UsbExportService.start(appContext)
    }

    fun cancelRequested(id: String): Boolean =
        get(id)?.state == UsbExportState.CANCEL_REQUESTED

    fun reportJson(task: UsbExportTask): String = json.encodeToString(
        UsbExportReport(
            generatedAtEpochMs = System.currentTimeMillis(),
            buildVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            buildGitSha = BuildConfig.GIT_SHA,
            operationId = task.id,
            logicalId = task.logicalId,
            state = task.state,
            targetDescription = task.target.description,
            targetUuid = task.target.storageUuid,
            targetVolumeName = task.target.volumeName,
            relativePath = task.relativePath,
            exportKey = task.exportKey,
            progressBytes = task.progressBytes,
            totalBytes = task.totalBytes,
            freeBytesAtStart = task.freeBytesAtStart,
            minimumObservedFreeBytes = task.minimumObservedFreeBytes,
            targetTotalBytes = task.targetTotalBytes,
            openAvmQuotaBytes = task.openAvmQuotaBytes,
            openAvmBytesBefore = task.openAvmBytesBefore,
            quotaReclaimedBytes = task.quotaReclaimedBytes,
            sourceCount = task.sources.size,
            assets = task.assets.map {
                UsbExportReportAsset(
                    kind = it.kind,
                    segmentNumber = it.segmentNumber,
                    requestedName = it.requestedName,
                    observedName = it.observedName,
                    expectedBytes = it.expectedBytes,
                    copiedBytes = it.copiedBytes,
                    expectedSha256 = it.expectedSha256,
                    expectedMimeType = it.expectedMimeType,
                    observedRelativePath = it.observedRelativePath,
                    observedVolumeName = it.observedVolumeName,
                    observedSizeBytes = it.observedSizeBytes,
                    observedMimeType = it.observedMimeType,
                    observedPending = it.observedPending,
                    observedOwnerPackage = it.observedOwnerPackage,
                    rereadBytes = it.rereadBytes,
                    rereadSha256 = it.rereadSha256,
                    verificationFailures = it.verificationFailures,
                    verified = it.verified,
                    published = it.published,
                    deletionConfirmed = it.deletionConfirmed,
                    error = it.error,
                )
            },
            errorCode = task.errorCode,
            message = task.message,
        ),
    )

    fun resumeQueuedWhenForeground() {
        if (::appContext.isInitialized && nextRunnable() != null) UsbExportService.start(appContext)
    }

    private fun replaceLocked(task: UsbExportTask) {
        _tasks.value = _tasks.value.map { if (it.id == task.id) task else it }
        saveLocked()
    }

    private fun load(): List<UsbExportTask> = runCatching {
        json.decodeFromString<List<UsbExportTask>>(queueFile().readText())
    }.getOrDefault(emptyList())

    private fun saveLocked() {
        if (!::filesRoot.isInitialized) return
        val target = queueFile()
        val temporary = File(target.absolutePath + ".tmp")
        temporary.writeText(json.encodeToString(_tasks.value))
        try {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun queueFile(): File = File(filesRoot, "usb-export-queue.json")

    private fun isTrustedCompletedTask(task: UsbExportTask, target: UsbExportTarget): Boolean =
        task.state in setOf(UsbExportState.COMPLETED, UsbExportState.ALREADY_EXPORTED) &&
            task.target.storageUuid.equals(target.storageUuid, ignoreCase = true) &&
            task.target.volumeName.equals(target.volumeName, ignoreCase = true) &&
            task.assets.isNotEmpty() &&
            task.assets.all { asset ->
                asset.verified &&
                    asset.published &&
                    asset.itemUri != null &&
                    normalizePath(asset.observedRelativePath) ==
                    normalizePath(UsbExportPolicy.RELATIVE_PATH) &&
                    asset.observedVolumeName.equals(target.volumeName, ignoreCase = true) &&
                    !asset.observedOwnerPackage.isNullOrBlank()
            }

    private fun normalizePath(value: String?): String? =
        value?.trim()?.replace('\\', '/')?.trim('/')?.lowercase(Locale.ROOT)

    private val ACTIVE_STATES = setOf(
        UsbExportState.PREPARING_SOURCE,
        UsbExportState.COPYING,
        UsbExportState.VERIFYING,
        UsbExportState.PUBLISHING,
        UsbExportState.CLEANING_UP,
    )
}
