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
import com.dante.zeekrbridge.OpenAvmHost
import com.dante.zeekrbridge.OpenAvmRuntime
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

        fun start(context: Context, openPairingWindow: Boolean = false) {
            context.startForegroundService(Intent(context, BridgeService::class.java)
                .putExtra("open_pairing_window", openPairingWindow))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BridgeService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        OpenAvmRuntime.initialize(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        BridgeServer.start(this)
        if (!BridgeServer.state.value.running) {
            _running.value = false
            stopSelf()
            return START_NOT_STICKY
        }
        // A restarted sticky service has no explicit pairing intent.
        if (intent?.getBooleanExtra("open_pairing_window", false) == true) {
            com.dante.zeekrbridge.core.PairingManager.newPairingCode()
        }
        BridgePowerLocks.acquire(this)
        _running.value = true
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification())
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
            OpenAvmHost.openIntent(this,"connection"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(
                if (BridgeServer.state.value.running)
                    t("Ready to receive on the same network. Tap for pairing details.", "接收已就绪，请让车机与手机连接同一网络。点此查看配对。")
                else t("Starting recording receiver…", "正在启动录像接收…"),
            )
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
