package com.dante.zeekrcapabilitylab.transfer

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
            NotificationChannel(CHANNEL, "Phone transfers", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION, notification("Preparing phone transfer"))
        acquireLocks()
        if (workerRunning.compareAndSet(false, true)) executor.execute { runQueue() }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
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
                if (task.state == TransferTaskState.CANCEL_PENDING && task.uploadId == null) {
                    TransferRepository.finish(task, TransferTaskState.CANCELLED)
                    continue
                }
                val endpoint = PhoneConnectionStore.saved()
                if (endpoint == null) {
                    TransferRepository.update(task.copy(state = TransferTaskState.WAITING_RETRY, reason = "Phone is not paired"))
                    break
                }
                try {
                    if (task.state == TransferTaskState.CANCEL_PENDING) cancelRemote(task, endpoint) else upload(task, endpoint)
                    TransferRepository.markConnected(endpoint, true, "Connected to ${endpoint.phoneName}")
                } catch (t: Throwable) {
                    val latest = TransferRepository.get(task.id) ?: continue
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
            stopSelf()
        }
    }

    private fun upload(initial: TransferTask, endpoint: PhoneEndpoint) {
        var task = TransferRepository.get(initial.id) ?: return
        val file = File(task.filePath)
        if (!file.isFile || file.length() != task.sizeBytes) {
            TransferRepository.finish(task, TransferTaskState.FAILED, "Recording is missing or changed")
            return
        }
        if (task.sha256 == null) {
            task = task.copy(state = TransferTaskState.PREPARING, reason = null)
            TransferRepository.update(task)
            task = task.copy(sha256 = sha256(file))
            TransferRepository.update(task)
        }
        if (TransferRepository.get(task.id)?.state == TransferTaskState.CANCEL_PENDING) return
        if (task.uploadId == null) {
            val created = TransferHttp.create(task.id, endpoint, UploadCreateRequest(
                clientTransferId = task.id,
                fileName = task.fileName,
                sizeBytes = task.sizeBytes,
                sha256 = task.sha256!!,
                carId = PhoneConnectionStore.carId,
                sidecarJson = task.sidecarJson,
            ))
            task = task.copy(uploadId = created.uploadId, chunkSize = created.chunkSize, totalChunks = created.totalChunks)
            TransferRepository.update(task)
        }
        val status = TransferHttp.status(task.id, endpoint, task.uploadId!!)
        if (status.status == "COMPLETED") {
            TransferRepository.finish(task, TransferTaskState.COMPLETED)
            return
        }
        val received = status.receivedChunks.toSet()
        for (index in 0 until task.totalChunks) {
            if (TransferRepository.get(task.id)?.state == TransferTaskState.CANCEL_PENDING) return
            if (index !in received) {
                val offset = index.toLong() * task.chunkSize
                val length = minOf(task.chunkSize.toLong(), task.sizeBytes - offset).toInt()
                TransferRepository.update(task.copy(state = TransferTaskState.UPLOADING, uploadedChunks = index, reason = null))
                TransferHttp.chunk(task.id, endpoint, task.uploadId!!, index, file, offset, length)
            }
            task = TransferRepository.get(task.id)?.copy(uploadedChunks = index + 1) ?: return
            TransferRepository.update(task)
        }
        if (TransferRepository.get(task.id)?.state == TransferTaskState.CANCEL_PENDING) return
        task = task.copy(state = TransferTaskState.COMMITTING, reason = null)
        TransferRepository.update(task)
        val completed = TransferHttp.complete(task.id, endpoint, task.uploadId!!, task.sha256!!, task.fileName)
        if (!completed.ok) error("Phone rejected commit")
        TransferRepository.finish(task, TransferTaskState.COMPLETED)
    }

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

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        file.inputStream().use { input -> while (true) { val n = input.read(buffer); if (n < 0) break; if (n > 0) digest.update(buffer, 0, n) } }
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
            .setContentTitle("Sending recordings to phone").setContentText(text).setContentIntent(pending).setOngoing(true).build()
    }
}
