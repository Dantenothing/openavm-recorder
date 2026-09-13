package com.dante.zeekrcapabilitylab.usbexport

import android.app.Notification
import android.app.NotificationChannel
import com.dante.zeekrcapabilitylab.util.Utils
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.dante.zeekrcapabilitylab.MainActivity
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class UsbExportService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, Utils.t("USB exports", "USB 导出"), NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification(Utils.t("Preparing USB export", "正在准备 USB 导出")), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification(Utils.t("Preparing USB export", "正在准备 USB 导出")))
        }
        acquireWakeLock()
        if (workerRunning.compareAndSet(false, true)) {
            executor.execute { runQueue() }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        removeForegroundNotification()
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun runQueue() {
        try {
            val engine = UsbExportEngine(applicationContext)
            while (true) {
                val task = UsbExportRepository.nextRunnable() ?: break
                engine.execute(task.id)
            }
        } finally {
            workerRunning.set(false)
            if (UsbExportRepository.nextRunnable() != null && workerRunning.compareAndSet(false, true)) {
                executor.execute { runQueue() }
            } else {
                removeForegroundNotification()
                stopSelf()
            }
        }
    }

    @Suppress("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "openavm:usb-export")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun notification(text: String): Notification {
        val pending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(Utils.t("Exporting recording to USB", "正在将录像导出到 USB"))
            .setContentText(text)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    /** Some vehicle builds retain an ongoing notification after stopForeground alone. */
    private fun removeForegroundNotification() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        runCatching { getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID) }
    }

    companion object {
        private const val CHANNEL_ID = "usb_export"
        private const val NOTIFICATION_ID = 2202
        private val workerRunning = AtomicBoolean(false)
        private val executor = Executors.newSingleThreadExecutor()

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context.applicationContext,
                Intent(context.applicationContext, UsbExportService::class.java),
            )
        }
    }
}
