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
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
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

    suspend fun pair(host: String, code: String): Result<PhoneEndpoint> {
        val parsed = PhoneAddressParser.parse(host)
        if (parsed.isFailure) {
            val error = parsed.exceptionOrNull() ?: IllegalArgumentException("手机地址格式无效")
            _connection.value = PhoneConnectionState(PhoneConnectionStore.saved(), false, error.message ?: "配对失败")
            return Result.failure(error)
        }
        val address = parsed.getOrThrow()
        val result = PhoneConnectionStore.pair(address.host, code.trim(), address.port)
        result.onSuccess {
            _connection.value = PhoneConnectionState(it, true, "Connected to ${it.phoneName}")
            if (hasWork()) TransferService.start(ZeekrApp.appContext)
        }
            .onFailure { _connection.value = PhoneConnectionState(PhoneConnectionStore.saved(), false, it.message ?: "Pairing failed") }
        return result
    }

    suspend fun checkConnection(): Boolean = connectionCheck.withLock {
        val endpoint = PhoneConnectionStore.saved()
        if (endpoint == null) {
            _connection.value = PhoneConnectionState(message = "Not paired")
            return@withLock false
        }
        val result = PhoneConnectionStore.reconnectSaved()
        val connectedEndpoint = result.getOrNull()
        _connection.value = if (connectedEndpoint != null) PhoneConnectionState(
            connectedEndpoint,
            true,
            "Connected to ${connectedEndpoint.phoneName}",
        )
        else PhoneConnectionState(endpoint, false, result.exceptionOrNull()?.message ?: "Phone unavailable")
        if (result.isSuccess && hasWork()) TransferService.start(ZeekrApp.appContext)
        result.isSuccess
    }

    fun reconnectInBackground() {
        if (_connection.value.endpoint == null) return
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
        if (!reconnectRetryScheduled.compareAndSet(false, true)) return
        reconnectScope.launch {
            delay(10_000L)
            reconnectRetryScheduled.set(false)
            if (hasWork() && !_connection.value.connected) reconnectInBackground()
        }
    }

    fun enqueue(file: File): Result<String> = synchronized(lock) {
        if (!RecorderLibrary.isManaged(file)) return Result.failure(IllegalArgumentException("Recording is not finalized"))
        if (_tasks.value.any { it.filePath == file.absolutePath && it.state !in TERMINAL }) {
            return Result.failure(IllegalStateException("Recording is already queued"))
        }
        val sidecar = runCatching { SegmentSidecarIO.sidecarFileFor(file).readText() }.getOrNull()
        if (!RecorderLibrary.pinForUpload(file)) return Result.failure(IllegalStateException("Unable to protect recording for transfer"))
        try {
            val id = UUID.randomUUID().toString()
            val decision = TransferEnqueuePolicy.decide(_connection.value.connected)
            val task = TransferTask(
                id = id,
                filePath = file.absolutePath,
                fileName = file.name,
                sizeBytes = file.length(),
                sidecarJson = sidecar,
                state = decision.initialState,
                reason = decision.reason,
            )
            _tasks.value = _tasks.value + task
            saveLocked()
            if (decision.startServiceImmediately) {
                TransferService.start(ZeekrApp.appContext)
            } else {
                reconnectInBackground()
            }
            Result.success(id)
        } catch (t: Throwable) {
            RecorderLibrary.releaseUploadPin(file)
            Result.failure(t)
        }
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
                RecorderLibrary.releaseUploadPin(File(task.filePath))
                return
            }
            updateLocked(task.copy(state = TransferTaskState.CANCEL_PENDING, reason = "Cancelled by user", updatedAt = System.currentTimeMillis()))
            // Once intent is durable and the active HTTP read is cancelled, remote cleanup only needs uploadId/token.
            TransferHttp.cancel(id)
            RecorderLibrary.releaseUploadPin(File(task.filePath))
            TransferService.start(ZeekrApp.appContext)
        }
    }

    fun retry(id: String) {
        synchronized(lock) {
            val task = _tasks.value.firstOrNull { it.id == id } ?: return
            if (task.state !in setOf(TransferTaskState.FAILED, TransferTaskState.WAITING_RETRY)) return
            updateLocked(task.copy(state = TransferTaskState.QUEUED, reason = null, updatedAt = System.currentTimeMillis()))
            if (File(task.filePath).isFile) RecorderLibrary.pinForUpload(File(task.filePath))
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
        _connection.value = PhoneConnectionState(endpoint, connected, message)
        if (!connected && hasWork()) scheduleReconnectRetry()
    }

    fun finish(task: TransferTask, state: TransferTaskState, reason: String? = null) = synchronized(lock) {
        updateLocked(task.copy(state = state, reason = reason, updatedAt = System.currentTimeMillis()))
        RecorderLibrary.releaseUploadPin(File(task.filePath))
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
        val activePaths = _tasks.value.filter { it.state !in TERMINAL && it.state != TransferTaskState.CANCEL_PENDING }
            .mapTo(mutableSetOf()) { it.filePath }
        val segmentsDir = File(filesRoot, "recordings/segments")
        RecorderLibrary.listFinalized(segmentsDir).forEach { file ->
            if (file.absolutePath in activePaths) RecorderLibrary.pinForUpload(file)
            else if (RecorderLibrary.protectionOf(file)?.uploadPinned == true) RecorderLibrary.releaseUploadPin(file)
        }
    }
}
