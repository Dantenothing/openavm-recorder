package com.dante.zeekrcapabilitylab.transfer

import android.content.Context
import io.github.dantenothing.avmtransfer.protocol.TransferTaskState
import java.io.File
import java.nio.file.Files
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.dante.zeekrcapabilitylab.service.recorder.RecorderLibrary
import com.dante.zeekrcapabilitylab.service.recorder.SegmentSidecarIO
import com.dante.zeekrcapabilitylab.ZeekrApp

import com.dante.zeekrcapabilitylab.usbexport.UsbBundleLeaseRegistry
import com.dante.zeekrcapabilitylab.usbexport.UsbExportPolicy
import com.dante.zeekrcapabilitylab.usbexport.UsbExportVolumeResolver
import com.dante.zeekrcapabilitylab.usbexport.UsbMediaStoreBackend
import com.dante.zeekrcapabilitylab.usbexport.UsbSegmentCatalog
@Serializable
enum class TransferSourceKind {
    MANAGED_RECORDING,
    FACTORY_SENTRY_USB,
    OPENAVM_USB,
}

@Serializable
data class TransferTask(
    val id: String,
    val filePath: String,
    val fileName: String,
    val sizeBytes: Long,
    val sidecarJson: String? = null,
    val state: TransferTaskState = TransferTaskState.QUEUED,
    val reason: String? = null,
    val sha256: String? = null,
    val uploadId: String? = null,
    val chunkSize: Int = 0,
    val totalChunks: Int = 0,
    val uploadedChunks: Int = 0,
    /** One user-confirmed multi-segment transfer selection. Null for legacy tasks. */
    val selectionId: String? = null,
    val selectionOrder: Int? = null,
    val selectionCount: Int? = null,
    val sourceRecordingSessionId: String? = null,
    val sourceKind: TransferSourceKind = TransferSourceKind.MANAGED_RECORDING,
    val sourceStorageUuid: String? = null,
    val sourceRelativePath: String? = null,
    val sourceEventId: String? = null,
    val sourceLastModifiedEpochMs: Long? = null,
    val sourceBundleId: String? = null,
    val sourceContentKey: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

data class TransferSelectionResult(
    val selectionId: String,
    val taskIds: List<String>,
    val fileCount: Int,
    val totalBytes: Long,
)

object TransferRepository {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private lateinit var filesRoot: File
    private val lock = Any()
    private val _tasks = MutableStateFlow<List<TransferTask>>(emptyList())
    val tasks = _tasks.asStateFlow()
    private val _connection = MutableStateFlow(PhoneConnectionState())
    val connection = _connection.asStateFlow()
    private val reconnectScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectionCheck = Mutex()
    private val backgroundReconnectRunning = AtomicBoolean(false)
    private val reconnectRetryScheduled = AtomicBoolean(false)

    fun init(appContext: Context) {
        filesRoot = appContext.applicationContext.filesDir
        PhoneConnectionStore.init(appContext)
        _connection.value = PhoneConnectionState(endpoint = PhoneConnectionStore.saved(), connected = false, message = "Tap Check connection")
        val loaded = load().map { task ->
            if (task.state in setOf(TransferTaskState.PREPARING, TransferTaskState.UPLOADING, TransferTaskState.COMMITTING)) {
                task.copy(state = TransferTaskState.WAITING_RETRY, reason = "App restarted", updatedAt = System.currentTimeMillis())
            } else task
        }
        _tasks.value = loaded
        saveLocked()
        reconcileUploadPins()
        reconnectInBackground()
    }

    suspend fun discover(): Result<PhoneAddress> = PhoneConnectionStore.discover().map { PhoneAddress(it.ip, it.port) }

    suspend fun inspectPairing(host: String): Result<PhonePairingCandidate> {
        val parsed = PhoneAddressParser.parse(host)
        val address = parsed.getOrElse { return Result.failure(it) }
        return PhoneConnectionStore.inspectPairing(address.host, address.port)
    }

    suspend fun pair(candidate: PhonePairingCandidate, code: String): Result<PhoneEndpoint> {
        val result = PhoneConnectionStore.pair(candidate, code.trim())
        result.onSuccess {
            _connection.value = PhoneConnectionState(it, true, "Connected to ${it.phoneName}")
            if (hasWork()) TransferService.start(ZeekrApp.appContext)
        }.onFailure {
            val failure = phoneFailure(it)
            _connection.value = PhoneConnectionState(PhoneConnectionStore.saved(), false, phoneSecurityMessage(failure.error), failure.error)
        }
        return result
    }

    suspend fun checkConnection(): Boolean = connectionCheck.withLock {
        val endpoint = PhoneConnectionStore.saved()
        if (endpoint == null) {
            _connection.value = PhoneConnectionState(message = "Not paired")
            return@withLock false
        }
        val result = PhoneConnectionStore.reconnectSaved(force = true)
        val connectedEndpoint = result.getOrNull()
        _connection.value = if (connectedEndpoint != null) PhoneConnectionState(
            connectedEndpoint,
            true,
            "Connected to ${connectedEndpoint.phoneName}",
        )
        else {
            val failure = phoneFailure(result.exceptionOrNull() ?: IllegalStateException())
            PhoneConnectionState(PhoneConnectionStore.saved(), false, phoneSecurityMessage(failure.error), failure.error)
        }
        if (result.isSuccess && hasWork()) TransferService.start(ZeekrApp.appContext)
        result.isSuccess
    }

    fun reconnectInBackground() {
        if (_connection.value.endpoint == null) return
        if (_connection.value.securityError?.retryable == false || PhoneConnectionStore.securityError()?.retryable == false) return
        if (!backgroundReconnectRunning.compareAndSet(false, true)) return
        reconnectScope.launch {
            try {
                checkConnection()
            } finally {
                backgroundReconnectRunning.set(false)
                if (hasWork() && !_connection.value.connected) scheduleReconnectRetry()
            }
        }
    }

    private fun scheduleReconnectRetry() {
        if (!hasWork() || _connection.value.connected || _connection.value.endpoint == null) return
        if (_connection.value.securityError?.retryable == false || PhoneConnectionStore.securityError()?.retryable == false) return
        if (!reconnectRetryScheduled.compareAndSet(false, true)) return
        reconnectScope.launch {
            delay(10_000L)
            reconnectRetryScheduled.set(false)
            if (hasWork() && !_connection.value.connected) reconnectInBackground()
        }
    }

    fun enqueue(file: File): Result<String> =
        enqueueSelection(recordingSessionId = null, files = listOf(file)).map { it.taskIds.single() }

    /**
     * Durably queues one user selection as an all-or-nothing operation.
     * Every source is validated and pinned before the queue snapshot is replaced;
     * a failure restores both the old queue and every pin acquired by this call.
     */
    fun enqueueSelection(
        recordingSessionId: String?,
        files: List<File>,
    ): Result<TransferSelectionResult> {
        val decision = TransferEnqueuePolicy.decide(_connection.value.connected)
        val result = synchronized(lock) {
            val uniqueFiles = files.distinctBy { it.absolutePath }
            if (uniqueFiles.isEmpty()) {
                return@synchronized Result.failure(IllegalArgumentException("Select at least one segment"))
            }

            val candidates = uniqueFiles.map { file ->
                if (!RecorderLibrary.isManaged(file)) {
                    return@synchronized Result.failure(IllegalArgumentException("Recording is not finalized: ${file.name}"))
                }
                if (_tasks.value.any { it.filePath == file.absolutePath && it.state !in TERMINAL }) {
                    return@synchronized Result.failure(IllegalStateException("Recording is already queued: ${file.name}"))
                }
                val sidecarFile = SegmentSidecarIO.sidecarFileFor(file)
                val sidecar = SegmentSidecarIO.read(sidecarFile)
                    ?: return@synchronized Result.failure(IllegalStateException("Recording metadata is unavailable: ${file.name}"))
                val sidecarJson = runCatching { sidecarFile.readText() }.getOrElse {
                    return@synchronized Result.failure(IllegalStateException("Recording metadata cannot be read: ${file.name}", it))
                }
                if (recordingSessionId != null && sidecar.recordingSessionId != recordingSessionId) {
                    return@synchronized Result.failure(IllegalArgumentException("Selected segments do not belong to the same recording"))
                }
                Triple(file, sidecar, sidecarJson)
            }.sortedWith(compareBy<Triple<File, com.dante.zeekrcapabilitylab.service.recorder.SegmentSidecar, String>> {
                it.second.segmentNumber
            }.thenBy { it.first.lastModified() })

            val previousTasks = _tasks.value
            val pinned = mutableListOf<File>()
            try {
                candidates.forEach { (file, _, _) ->
                    if (!RecorderLibrary.pinForUpload(file)) {
                        error("Unable to protect recording for transfer: ${file.name}")
                    }
                    pinned += file
                }

                val selectionId = UUID.randomUUID().toString()
                val now = System.currentTimeMillis()
                val newTasks = candidates.mapIndexed { index, (file, _, sidecarJson) ->
                    TransferTask(
                        id = UUID.randomUUID().toString(),
                        filePath = file.absolutePath,
                        fileName = file.name,
                        sizeBytes = file.length(),
                        sidecarJson = sidecarJson,
                        state = decision.initialState,
                        reason = decision.reason,
                        selectionId = selectionId,
                        selectionOrder = index,
                        selectionCount = candidates.size,
                        sourceRecordingSessionId = recordingSessionId,
                        createdAt = now,
                        updatedAt = now,
                    )
                }
                _tasks.value = previousTasks + newTasks
                saveLocked()
                Result.success(
                    TransferSelectionResult(
                        selectionId = selectionId,
                        taskIds = newTasks.map { it.id },
                        fileCount = newTasks.size,
                        totalBytes = newTasks.sumOf { it.sizeBytes },
                    ),
                )
            } catch (t: Throwable) {
                _tasks.value = previousTasks
                runCatching { saveLocked() }
                pinned.forEach(RecorderLibrary::releaseUploadPin)
                Result.failure(t)
            }
        }

        activateSelection(decision, result)
        return result
    }

    /** Queues one read-only factory SentryMode MP4 without mutating or pinning the USB source. */
    fun enqueueFactorySentry(
        storageUuid: String,
        eventId: String,
        startedAtEpochMs: Long,
        videoFile: File,
    ): Result<TransferSelectionResult> {
        val snapshot = FactorySentryTransferSource.snapshotForQueue(
            context = ZeekrApp.appContext,
            storageUuid = storageUuid,
            eventId = eventId,
            startedAtEpochMs = startedAtEpochMs,
            selectedFile = videoFile,
        ).getOrElse { return Result.failure(it) }
        val decision = TransferEnqueuePolicy.decide(_connection.value.connected)
        val result = synchronized(lock) {
            if (_tasks.value.any {
                    it.sourceKind == TransferSourceKind.FACTORY_SENTRY_USB &&
                        it.sourceStorageUuid.equals(storageUuid, ignoreCase = true) &&
                        it.sourceRelativePath == snapshot.relativePath &&
                        it.state !in TERMINAL
                }
            ) {
                return@synchronized Result.failure(
                    IllegalStateException("Sentry event is already queued: $eventId"),
                )
            }
            val selectionId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val task = TransferTask(
                id = UUID.randomUUID().toString(),
                filePath = snapshot.file.absolutePath,
                fileName = snapshot.file.name,
                sizeBytes = snapshot.sizeBytes,
                sidecarJson = snapshot.sidecarJson,
                state = decision.initialState,
                reason = decision.reason,
                selectionId = selectionId,
                selectionOrder = 0,
                selectionCount = 1,
                sourceRecordingSessionId = snapshot.sourceId,
                sourceKind = TransferSourceKind.FACTORY_SENTRY_USB,
                sourceStorageUuid = storageUuid,
                sourceRelativePath = snapshot.relativePath,
                sourceEventId = eventId,
                sourceLastModifiedEpochMs = snapshot.lastModifiedEpochMs,
                createdAt = now,
                updatedAt = now,
            )
            val previousTasks = _tasks.value
            try {
                _tasks.value = previousTasks + task
                saveLocked()
                Result.success(
                    TransferSelectionResult(
                        selectionId = selectionId,
                        taskIds = listOf(task.id),
                        fileCount = 1,
                        totalBytes = task.sizeBytes,
                    ),
                )
            } catch (t: Throwable) {
                _tasks.value = previousTasks
                runCatching { saveLocked() }
                Result.failure(t)
            }
        }
        activateSelection(decision, result)
        return result
    }

    /** Queues selected verified OpenAVM USB segment bundles without making an internal MP4 copy. */
    fun enqueueOpenAvmUsb(
        storageUuid: String,
        recordingSessionId: String,
        files: List<File>,
    ): Result<TransferSelectionResult> {
        val target = UsbExportVolumeResolver.mountedTargets(ZeekrApp.appContext).singleOrNull {
            it.storageUuid.equals(storageUuid, ignoreCase = true)
        } ?: return Result.failure(IllegalStateException("Reconnect the same USB before sending"))
        val backend = UsbMediaStoreBackend(ZeekrApp.appContext)
        val bundles = runCatching { UsbSegmentCatalog(ZeekrApp.appContext, backend).snapshot(target).segments }
            .getOrElse { return Result.failure(it) }
        val selected = files.distinctBy { it.absolutePath }.map { file ->
            val bundle = bundles.singleOrNull {
                it.video.displayName == file.name && it.video.sizeBytes == file.length()
            } ?: return Result.failure(IllegalArgumentException("USB segment is not a verified OpenAVM bundle: ${file.name}"))
            val sidecarJson = runCatching {
                com.dante.zeekrcapabilitylab.usbexport.UsbIncidentMarkers(ZeekrApp.appContext).sidecarBytes(bundle).toString(Charsets.UTF_8)
            }.getOrElse { return Result.failure(it) }
            Triple(file, bundle, sidecarJson)
        }.sortedBy { it.second.manifestData.segmentNumber }
        if (selected.isEmpty()) return Result.failure(IllegalArgumentException("Select at least one USB segment"))
        val decision = TransferEnqueuePolicy.decide(_connection.value.connected)
        val result = synchronized(lock) {
            val duplicate = selected.firstOrNull { (_, bundle, _) ->
                _tasks.value.any { task ->
                    task.sourceKind == TransferSourceKind.OPENAVM_USB &&
                        task.sourceStorageUuid.equals(storageUuid, ignoreCase = true) &&
                        task.sourceBundleId == bundle.manifestData.bundleId &&
                        task.state !in TERMINAL
                }
            }
            if (duplicate != null) {
                return@synchronized Result.failure(
                    IllegalStateException("USB segment is already queued: ${duplicate.first.name}"),
                )
            }
            val selectionId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val tasks = selected.mapIndexed { index, (file, bundle, sidecarJson) ->
                val manifest = bundle.manifestData
                val relativePath = listOfNotNull(
                    bundle.video.relativePath?.trim()?.trim('/', '\\'),
                    bundle.video.displayName,
                ).joinToString("/")
                TransferTask(
                    id = UUID.randomUUID().toString(),
                    filePath = file.absolutePath,
                    fileName = file.name,
                    sizeBytes = file.length(),
                    sidecarJson = sidecarJson,
                    state = decision.initialState,
                    reason = decision.reason,
                    selectionId = selectionId,
                    selectionOrder = index,
                    selectionCount = selected.size,
                    sourceRecordingSessionId = recordingSessionId,
                    sourceKind = TransferSourceKind.OPENAVM_USB,
                    sourceStorageUuid = storageUuid,
                    sourceRelativePath = relativePath,
                    sourceLastModifiedEpochMs = file.lastModified(),
                    sourceBundleId = manifest.bundleId,
                    sourceContentKey = manifest.contentKey,
                    createdAt = now,
                    updatedAt = now,
                )
            }
            val previous = _tasks.value
            try {
                tasks.forEach { task ->
                    UsbBundleLeaseRegistry.acquire(storageUuid, requireNotNull(task.sourceBundleId))
                }
                _tasks.value = previous + tasks
                saveLocked()
                Result.success(
                    TransferSelectionResult(
                        selectionId = selectionId,
                        taskIds = tasks.map { it.id },
                        fileCount = tasks.size,
                        totalBytes = tasks.sumOf { it.sizeBytes },
                    ),
                )
            } catch (failure: Throwable) {
                _tasks.value = previous
                tasks.forEach { task ->
                    UsbBundleLeaseRegistry.release(storageUuid, requireNotNull(task.sourceBundleId))
                }
                runCatching { saveLocked() }
                Result.failure(failure)
            }
        }
        activateSelection(decision, result)
        return result
    }

    fun cancel(id: String) {
        synchronized(lock) {
            val task = _tasks.value.firstOrNull { it.id == id } ?: return
            if (task.state in TERMINAL) return
            if (TransferCancelPolicy.action(task.uploadId) == TransferCancelAction.FINISH_LOCALLY) {
                updateLocked(
                    task.copy(
                        state = TransferTaskState.CANCELLED,
                        reason = "Cancelled by user",
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                releaseSourcePin(task)
                return
            }
            updateLocked(task.copy(state = TransferTaskState.CANCEL_PENDING, reason = "Cancelled by user", updatedAt = System.currentTimeMillis()))
            // Once intent is durable and the active HTTP read is cancelled, remote cleanup only needs uploadId/token.
            TransferHttp.cancel(id)
            releaseSourcePin(task)
            TransferService.start(ZeekrApp.appContext)
        }
    }

    fun retry(id: String) {
        synchronized(lock) {
            val task = _tasks.value.firstOrNull { it.id == id } ?: return
            if (task.state !in setOf(TransferTaskState.FAILED, TransferTaskState.WAITING_RETRY)) return
            val leaseWasReleased = task.state == TransferTaskState.FAILED
            updateLocked(task.copy(state = TransferTaskState.QUEUED, reason = null, updatedAt = System.currentTimeMillis()))
            if (task.sourceKind == TransferSourceKind.MANAGED_RECORDING && File(task.filePath).isFile) {
                RecorderLibrary.pinForUpload(File(task.filePath))
            }
            if (task.sourceKind == TransferSourceKind.OPENAVM_USB && leaseWasReleased) {
                UsbBundleLeaseRegistry.acquire(requireNotNull(task.sourceStorageUuid), requireNotNull(task.sourceBundleId))
            }
            TransferService.start(ZeekrApp.appContext)
        }
    }

    fun clearFinished() = synchronized(lock) {
        _tasks.value = _tasks.value.filterNot { it.state in TERMINAL }
        saveLocked()
    }

    fun get(id: String): TransferTask? = _tasks.value.firstOrNull { it.id == id }
    fun nextWork(): TransferTask? = _tasks.value.firstOrNull { it.state !in TERMINAL }
    fun hasWork(): Boolean = _tasks.value.any { it.state !in TERMINAL }

    fun update(task: TransferTask) = synchronized(lock) { updateLocked(task.copy(updatedAt = System.currentTimeMillis())) }
    fun markConnected(endpoint: PhoneEndpoint, connected: Boolean, message: String) {
        if (!PhoneConnectionStore.isCurrentPairing(endpoint)) return
        _connection.value = PhoneConnectionState(PhoneConnectionStore.saved(), connected, message,
            if (connected) null else PhoneConnectionStore.securityError())
        if (!connected && hasWork()) scheduleReconnectRetry()
    }

    fun finish(task: TransferTask, state: TransferTaskState, reason: String? = null) = synchronized(lock) {
        val current = _tasks.value.firstOrNull { it.id == task.id } ?: return@synchronized
        updateLocked(current.copy(state = state, reason = reason, updatedAt = System.currentTimeMillis()))
        if (current.state !in TERMINAL && current.state != TransferTaskState.CANCEL_PENDING) {
            releaseSourcePin(current)
        }
    }

    private fun updateLocked(task: TransferTask) {
        _tasks.value = _tasks.value.map { if (it.id == task.id) task else it }
        saveLocked()
    }

    private fun load(): List<TransferTask> = try {
        json.decodeFromString<List<TransferTask>>(queueFile().readText())
    } catch (_: Throwable) { emptyList() }

    private fun saveLocked() {
        val file = queueFile()
        val tmp = File(file.absolutePath + ".tmp")
        tmp.writeText(json.encodeToString(_tasks.value))
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun queueFile() = File(filesRoot, "phone-transfer-queue.json")
    val TERMINAL = setOf(TransferTaskState.COMPLETED, TransferTaskState.CANCELLED, TransferTaskState.FAILED)

    private fun reconcileUploadPins() {
        UsbBundleLeaseRegistry.reset()
        val activePaths = _tasks.value.filter {
            it.sourceKind == TransferSourceKind.MANAGED_RECORDING &&
                it.state !in TERMINAL && it.state != TransferTaskState.CANCEL_PENDING
        }
            .mapTo(mutableSetOf()) { it.filePath }
        val segmentsDir = File(filesRoot, "recordings/segments")
        RecorderLibrary.listFinalized(segmentsDir).forEach { file ->
            if (file.absolutePath in activePaths) RecorderLibrary.pinForUpload(file)
            else if (RecorderLibrary.protectionOf(file)?.uploadPinned == true) RecorderLibrary.releaseUploadPin(file)
        }
        _tasks.value.filter {
            it.sourceKind == TransferSourceKind.OPENAVM_USB &&
                it.state !in TERMINAL && it.state != TransferTaskState.CANCEL_PENDING
        }.forEach { task ->
            val uuid = task.sourceStorageUuid ?: return@forEach
            val bundleId = task.sourceBundleId ?: return@forEach
            UsbBundleLeaseRegistry.acquire(uuid, bundleId)
        }
    }

    private fun activateSelection(
        decision: TransferEnqueueDecision,
        result: Result<TransferSelectionResult>,
    ) {
        result.onSuccess { selection ->
            if (decision.startServiceImmediately) {
                runCatching { TransferService.start(ZeekrApp.appContext) }.onFailure { failure ->
                    synchronized(lock) {
                        val taskIds = selection.taskIds.toSet()
                        _tasks.value = _tasks.value.map { task ->
                            if (task.id in taskIds) task.copy(
                                state = TransferTaskState.WAITING_RETRY,
                                reason = failure.message ?: "Transfer service unavailable",
                                updatedAt = System.currentTimeMillis(),
                            ) else task
                        }
                        saveLocked()
                    }
                    reconnectInBackground()
                }
            } else {
                reconnectInBackground()
            }
        }
    }

    private fun releaseSourcePin(task: TransferTask) {
        if (task.sourceKind == TransferSourceKind.MANAGED_RECORDING) {
            RecorderLibrary.releaseUploadPin(File(task.filePath))
        }
        if (task.sourceKind == TransferSourceKind.OPENAVM_USB) {
            UsbBundleLeaseRegistry.release(requireNotNull(task.sourceStorageUuid), requireNotNull(task.sourceBundleId))
        }
    }
}
