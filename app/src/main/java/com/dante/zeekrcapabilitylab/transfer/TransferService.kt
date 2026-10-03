package com.dante.zeekrcapabilitylab.transfer
import com.dante.zeekrcapabilitylab.util.Utils
import com.dante.zeekrcapabilitylab.diagnostic.TransferDiagnosticPhase

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import com.dante.zeekrcapabilitylab.MainActivity
import io.github.dantenothing.avmtransfer.protocol.TransferTaskState
import io.github.dantenothing.avmtransfer.protocol.UploadCreateRequest
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class TransferService : Service() {
    private enum class QueueStep { CONTINUE, PAUSE }

    companion object {
        private const val CHANNEL = "phone_transfer"
        private const val NOTIFICATION = 2201
        private val workerRunning = AtomicBoolean(false)
        private val executor = Executors.newSingleThreadExecutor()
        fun start(context: Context) = context.startForegroundService(Intent(context, TransferService::class.java))
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, Utils.t("Phone transfers", "手机传输"), NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION, notification(Utils.t("Preparing phone transfer", "正在准备传输到手机")))
        acquireLocks()
        if (workerRunning.compareAndSet(false, true)) executor.execute { runQueue() }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        removeForegroundNotification()
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
        super.onDestroy()
    }

    private fun runQueue() {
        try {
            while (true) {
                val task = TransferRepository.nextWork() ?: break
                task.recordDiagnostic(TransferDiagnosticPhase.TASK_SELECTED)
                if (task.state == TransferTaskState.CANCEL_PENDING && task.uploadId == null) {
                    TransferRepository.finish(task, TransferTaskState.CANCELLED)
                    continue
                }
                task.recordDiagnostic(TransferDiagnosticPhase.CHECKING_CONNECTION)
                val endpoint = try { PhoneConnectionStore.requireConnected() } catch (t: Exception) {
                    val failure = phoneFailure(t)
                    val message = phoneSecurityMessage(failure.error)
                    TransferRepository.update(task.copy(state = if (task.state == TransferTaskState.CANCEL_PENDING) task.state else TransferTaskState.WAITING_RETRY, reason = message))
                    PhoneConnectionStore.saved()?.let { TransferRepository.markConnected(it, false, message) }
                    break
                }
                try {
                    val step = if (task.state == TransferTaskState.CANCEL_PENDING) {
                        cancelRemote(task, endpoint)
                        QueueStep.CONTINUE
                    } else {
                        upload(task, endpoint)
                    }
                    TransferRepository.markConnected(endpoint, true, "Connected to ${endpoint.phoneName}")
                    if (step == QueueStep.PAUSE) break
                } catch (t: Throwable) {
                    if (t is TransferCancelledException) continue
                    if (TransferRepository.get(task.id)?.state in TransferRepository.TERMINAL) continue
                    if (t is PhoneSecurityException) {
                        val latest = TransferRepository.get(task.id) ?: break
                        val message = phoneSecurityMessage(t.error)
                        TransferRepository.update(latest.copy(state = if (latest.state == TransferTaskState.CANCEL_PENDING) latest.state else TransferTaskState.WAITING_RETRY, reason = message))
                        TransferRepository.markConnected(endpoint, false, message)
                        break
                    }
                    val latest = TransferRepository.get(task.id) ?: continue
                    if (latest.state == TransferTaskState.CANCEL_PENDING) {
                        TransferRepository.update(latest.copy(reason = "Waiting for phone to remove the partial transfer"))
                        TransferRepository.markConnected(endpoint, false, t.message ?: "Phone unavailable")
                        break
                    }
                    if (latest.sourceKind == TransferSourceKind.FACTORY_SENTRY_USB) {
                        when (val source = resolveFactorySentry(latest)) {
                            is FactorySentrySourceResolution.WaitingForUsb -> {
                                TransferRepository.update(
                                    latest.copy(state = TransferTaskState.WAITING_RETRY, reason = source.reason),
                                )
                                TransferRepository.markConnected(
                                    endpoint,
                                    true,
                                    "Connected to ${endpoint.phoneName}",
                                )
                                break
                            }
                            is FactorySentrySourceResolution.Invalid -> {
                                TransferRepository.finish(latest, TransferTaskState.FAILED, source.reason)
                                TransferRepository.markConnected(
                                    endpoint,
                                    true,
                                    "Connected to ${endpoint.phoneName}",
                                )
                                continue
                            }
                            is FactorySentrySourceResolution.Resolved -> Unit
                        }
                    }
                    if (latest.sourceKind == TransferSourceKind.OPENAVM_USB) {
                        when (val source = resolveOpenAvmUsb(latest)) {
                            is OpenAvmUsbSourceResolution.WaitingForUsb -> {
                                TransferRepository.update(
                                    latest.copy(state = TransferTaskState.WAITING_RETRY, reason = source.reason),
                                )
                                TransferRepository.markConnected(
                                    endpoint,
                                    true,
                                    "Connected to ${endpoint.phoneName}",
                                )
                                break
                            }
                            is OpenAvmUsbSourceResolution.Invalid -> {
                                TransferRepository.finish(latest, TransferTaskState.FAILED, source.reason)
                                TransferRepository.markConnected(
                                    endpoint,
                                    true,
                                    "Connected to ${endpoint.phoneName}",
                                )
                                continue
                            }
                            is OpenAvmUsbSourceResolution.Resolved -> Unit
                        }
                    }
                    if (latest.state == TransferTaskState.CANCEL_PENDING) {
                        TransferRepository.update(
                            latest.copy(reason = "Waiting for phone to remove the partial transfer"),
                        )
                    } else {
                        TransferRepository.update(
                            latest.copy(
                                state = TransferTaskState.WAITING_RETRY,
                                reason = t.message ?: "Transfer interrupted",
                            ),
                        )
                    }
                    TransferRepository.markConnected(endpoint, false, t.message ?: "Phone unavailable")
                    break
                }
            }
        } finally {
            workerRunning.set(false)
            removeForegroundNotification()
            stopSelf()
        }
    }

    private fun upload(initial: TransferTask, endpoint: PhoneEndpoint): QueueStep {
        var task = TransferRepository.get(initial.id) ?: return QueueStep.CONTINUE
        if (stopped(task.id)) return QueueStep.CONTINUE
        task.recordDiagnostic(TransferDiagnosticPhase.CHECKING_RECEIVER)
        val raster = io.github.dantenothing.avmtransfer.protocol.RecordingRasterMetadata.read(task.sidecarJson)
        val sessionFormatRequired = io.github.dantenothing.avmtransfer.protocol.RecordingSessionCompatibility.required(task.sidecarJson)
        val health = if (task.includeDriveData || sessionFormatRequired || raster is io.github.dantenothing.avmtransfer.protocol.RecordingRasterMetadata.Repacked)
            TransferHttp.health(task.id, endpoint) else null
        val capabilities = health?.recordingRasterLayouts.orEmpty()
        if (stopped(task.id)) return QueueStep.CONTINUE
        if (!io.github.dantenothing.avmtransfer.protocol.RecordingSessionCompatibility.supported(task.sidecarJson, health?.recordingSessionFormats.orEmpty())) {
            TransferRepository.finish(task, TransferTaskState.FAILED,
                Utils.t("Update the phone app to play recordings on the shared session timeline.", "请更新手机版，以正确回放同一行程的多通道录像。"))
            return QueueStep.CONTINUE
        }
        val requiredDriveFormat = io.github.dantenothing.avmtransfer.protocol.DriveDataCodec.capability(task.driveData?.schemaVersion ?: 2)
        if (task.includeDriveData && requiredDriveFormat !in health?.driveDataFormats.orEmpty()) {
            TransferRepository.finish(task, TransferTaskState.FAILED,
                Utils.t("Update the phone app to receive drive data, or send video only.", "请更新手机版以接收行车数据，或选择仅发送视频。"))
            return QueueStep.CONTINUE
        }
        raster.receiverError(capabilities)?.let { reason ->
            TransferRepository.finish(task, TransferTaskState.FAILED,
                if (reason == "RECEIVER_RASTER_UPGRADE_REQUIRED")
                    Utils.t("Update the phone app before sending this recording.", "请先更新手机版，再发送这段录像。")
                else Utils.t("Recording layout metadata is invalid.", "录像布局信息无效。"))
            return QueueStep.CONTINUE
        }
        if (task.uploadId != null) {
            task.recordDiagnostic(TransferDiagnosticPhase.CHECKING_REMOTE_STATUS)
            val remote = TransferHttp.status(task.id, endpoint, task.uploadId!!)
            if (remote.status == "COMPLETED") {
                TransferRepository.finish(task, TransferTaskState.COMPLETED)
                return QueueStep.CONTINUE
            }
        }
        if (stopped(task.id)) return QueueStep.CONTINUE
        task.recordDiagnostic(TransferDiagnosticPhase.READING_SOURCE)
        val file = when (task.sourceKind) {
            TransferSourceKind.MANAGED_RECORDING -> File(task.filePath).takeIf {
                it.isFile && it.length() == task.sizeBytes
            } ?: run {
                TransferRepository.finish(task, TransferTaskState.FAILED, "Recording is missing or changed")
                return QueueStep.CONTINUE
            }
            TransferSourceKind.FACTORY_SENTRY_USB -> when (val source = resolveFactorySentry(task)) {
                is FactorySentrySourceResolution.Resolved -> source.file
                is FactorySentrySourceResolution.WaitingForUsb -> {
                    TransferRepository.update(
                        task.copy(state = TransferTaskState.WAITING_RETRY, reason = source.reason),
                    )
                    return QueueStep.PAUSE
                }
                is FactorySentrySourceResolution.Invalid -> {
                    TransferRepository.finish(task, TransferTaskState.FAILED, source.reason)
                    return QueueStep.CONTINUE
                }
            }
            TransferSourceKind.OPENAVM_USB -> when (val source = resolveOpenAvmUsb(task)) {
                is OpenAvmUsbSourceResolution.Resolved -> source.file
                is OpenAvmUsbSourceResolution.WaitingForUsb -> {
                    TransferRepository.update(task.copy(state = TransferTaskState.WAITING_RETRY, reason = source.reason))
                    return QueueStep.PAUSE
                }
                is OpenAvmUsbSourceResolution.Invalid -> {
                    TransferRepository.finish(task, TransferTaskState.FAILED, source.reason)
                    return QueueStep.CONTINUE
                }
            }
        }
        if (task.sha256 == null) {
            task = task.copy(state = TransferTaskState.PREPARING, reason = null)
            TransferRepository.update(task)
            task.recordDiagnostic(TransferDiagnosticPhase.HASHING)
            task = task.copy(sha256 = sha256(file, task.id))
            TransferRepository.update(task)
        }
        if (stopped(task.id)) return QueueStep.CONTINUE
        if (task.includeDriveData) {
            task.recordDiagnostic(TransferDiagnosticPhase.PREPARING_DRIVE_DATA)
            try {
                if (task.driveData == null) {
                    val descriptor = DriveAttachmentSnapshot.prepare(applicationContext, task)
                    task = TransferRepository.attachDriveData(task.id, descriptor) ?: run {
                        DriveAttachmentSnapshot.remove(applicationContext, task.id)
                        return QueueStep.CONTINUE
                    }
                }
                DriveAttachmentSnapshot.read(applicationContext, task)
            } catch (_: Exception) {
                TransferRepository.finish(task, TransferTaskState.FAILED, Utils.t(
                    "Drive data could not be prepared. Check recording history or send video only.",
                    "未能准备行车数据，请检查录像记录或选择仅发送视频。"))
                return QueueStep.CONTINUE
            }
        }
        if (stopped(task.id)) return QueueStep.CONTINUE
        if (task.uploadId == null) {
            task.recordDiagnostic(TransferDiagnosticPhase.CREATING_UPLOAD)
            TransferHttp.create(task.id, endpoint, UploadCreateRequest(
                clientTransferId = task.id,
                fileName = task.fileName,
                sizeBytes = task.sizeBytes,
                sha256 = task.sha256!!,
                carId = PhoneConnectionStore.carId,
                sidecarJson = task.sidecarJson,
                driveData = task.driveData,
            ))
            // Its create response persisted the cleanup handle before leaving the HTTP registry.
            task = TransferRepository.get(task.id) ?: return QueueStep.CONTINUE
        }
        if (stopped(task.id)) return QueueStep.CONTINUE
        task.recordDiagnostic(TransferDiagnosticPhase.CHECKING_REMOTE_STATUS)
        val status = TransferHttp.status(task.id, endpoint, task.uploadId!!)
        if (status.status == "COMPLETED") {
            TransferRepository.finish(task, TransferTaskState.COMPLETED)
            return QueueStep.CONTINUE
        }
        val received = status.receivedChunks.toSet()
        if (stopped(task.id)) return QueueStep.CONTINUE
        if (task.driveData != null && !status.driveDataReceived) {
            task.recordDiagnostic(TransferDiagnosticPhase.SENDING_DRIVE_DATA)
            TransferHttp.driveData(task.id, endpoint, task.uploadId!!, DriveAttachmentSnapshot.read(applicationContext, task))
        }
        for (index in 0 until task.totalChunks) {
            if (stopped(task.id)) return QueueStep.CONTINUE
            if (index !in received) {
                val offset = index.toLong() * task.chunkSize
                val length = minOf(task.chunkSize.toLong(), task.sizeBytes - offset).toInt()
                TransferRepository.update(task.copy(state = TransferTaskState.UPLOADING, uploadedChunks = index, reason = null))
                TransferHttp.chunk(task.id, endpoint, task.uploadId!!, index, file, offset, length)
            }
            task = TransferRepository.get(task.id)?.copy(uploadedChunks = index + 1) ?: return QueueStep.CONTINUE
            TransferRepository.update(task)
        }
        if (stopped(task.id)) return QueueStep.CONTINUE
        task = task.copy(state = TransferTaskState.COMMITTING, reason = null)
        TransferRepository.update(task)
        val completed = TransferHttp.complete(task.id, endpoint, task.uploadId!!, task.sha256!!, task.fileName)
        if (!completed.ok) error("Phone rejected commit")
        TransferRepository.finish(task, TransferTaskState.COMPLETED)
        return QueueStep.CONTINUE
    }

    private fun resolveFactorySentry(task: TransferTask): FactorySentrySourceResolution =
        FactorySentryTransferSource.resolve(
            context = applicationContext,
            storageUuid = task.sourceStorageUuid,
            eventId = task.sourceEventId,
            relativePath = task.sourceRelativePath,
            expectedBytes = task.sizeBytes,
            expectedLastModifiedEpochMs = task.sourceLastModifiedEpochMs,
        )

    private fun resolveOpenAvmUsb(task: TransferTask): OpenAvmUsbSourceResolution =
        OpenAvmUsbTransferSource.resolve(applicationContext, task)

    private fun cancelRemote(task: TransferTask, endpoint: PhoneEndpoint) {
        val uploadId = task.uploadId
        if (uploadId == null) {
            TransferRepository.finish(task, TransferTaskState.CANCELLED)
            return
        }
        val deleted = TransferHttp.delete(task.id, endpoint, uploadId)
        when {
            deleted.code in 200..299 || deleted.code == 404 -> TransferRepository.finish(task, TransferTaskState.CANCELLED)
            deleted.code == 409 -> {
                val status = TransferHttp.status(task.id, endpoint, uploadId)
                if (status.status == "COMPLETED") TransferRepository.finish(task, TransferTaskState.COMPLETED)
                else error("Cancel pending (${deleted.code})")
            }
            else -> error("Cancel pending (${deleted.code})")
        }
    }

    private fun stopped(id: String): Boolean = TransferRepository.get(id)?.let { !TransferCancellationPolicy.canUpload(it.state) } != false

    private fun sha256(file: File, taskId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        file.inputStream().use { input -> while (true) {
            if (stopped(taskId)) throw TransferCancelledException()
            val n = input.read(buffer); if (n < 0) break; if (n > 0) digest.update(buffer, 0, n)
        } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @SuppressLint("WakelockTimeout")
    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        if (wakeLock?.isHeld != true) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "openavm:phone-transfer").apply { setReferenceCounted(false); acquire() }
        }
        if (wifiLock?.isHeld != true) {
            wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "openavm:phone-transfer").apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun notification(text: String): Notification {
        val pending = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(Utils.t("Sending recordings to phone", "正在发送录像到手机")).setContentText(text).setContentIntent(pending).setOngoing(true).build()
    }

    private fun removeForegroundNotification() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION)
    }
}
