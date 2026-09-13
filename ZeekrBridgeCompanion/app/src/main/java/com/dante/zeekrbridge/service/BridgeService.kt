package com.dante.zeekrbridge.service

import android.app.Notification
import android.app.NotificationChannel
import com.dante.zeekrbridge.ui.t
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.dante.zeekrbridge.MainActivity
import com.dante.zeekrbridge.R
import com.dante.zeekrbridge.server.BridgeServer
import com.dante.zeekrbridge.server.BluetoothServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BridgeService : Service() {

    companion object {
        private const val CHANNEL_ID = "bridge"
        private const val NOTIFICATION_ID = 2001

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        fun start(context: Context) {
            context.startForegroundService(Intent(context, BridgeService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BridgeService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        BridgeServer.start(this)
        BluetoothServer.start(this)
        BridgePowerLocks.acquire(this)
        _running.value = true
        return START_STICKY
    }

    override fun onDestroy() {
        BridgeServer.stop(this)
        BluetoothServer.stop(this)
        BridgePowerLocks.release()
        _running.value = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            t("Vehicle connection", "车辆连接"),
            NotificationManager.IMPORTANCE_LOW,
        )
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(
                t("Ready to receive from your vehicle. Keeping the connection active uses battery.", "已准备接收车机文件，保持连接会增加耗电。"),
            )
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
