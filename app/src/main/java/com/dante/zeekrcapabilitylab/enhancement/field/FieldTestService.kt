package com.dante.zeekrcapabilitylab.enhancement.field

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dante.zeekrcapabilitylab.BuildConfig
import com.dante.zeekrcapabilitylab.util.Utils

/** User-started, bounded read-only observation. No restart, wake lock or camera ownership. */
class FieldTestService : Service() {
    private var registered = false
    private var started = false
    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) FieldTestRuntime.stopFor("SCREEN_OFF")
        }
    }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { FieldTestRuntime.stop(); stopSelf(); return START_NOT_STICKY }
        if (!BuildConfig.VEHICLE_FIELD_TEST_ENABLED || !FieldTestRuntime.hasPendingRun()) {
            stopSelf(); return START_NOT_STICKY
        }
        if (started) return START_NOT_STICKY
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL,
            Utils.t("Vehicle field test", "车辆实测"), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 105, Intent(this, FieldTestActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 106, Intent(this, FieldTestService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(Utils.t("Vehicle field test running", "车辆实测进行中"))
            .setContentText(Utils.t("Read-only · stops at screen-off or the time limit", "只读观察 · 息屏或到时停止"))
            .setContentIntent(open).setOngoing(true).setSilent(true)
            .addAction(0, Utils.t("Stop", "停止"), stop).build()
        val location = FieldTestRuntime.wantsLocation() && (
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(105, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                    if (location) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
            else startForeground(105, notification)
        } catch (_: RuntimeException) {
            FieldTestRuntime.stopFor("FOREGROUND_START_FAILED"); stopSelf(); return START_NOT_STICKY
        }
        started = true
        ContextCompat.registerReceiver(this, screen, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
        registered = true
        if (!getSystemService(PowerManager::class.java).isInteractive) {
            FieldTestRuntime.stopFor("SCREEN_OFF"); stopSelf(); return START_NOT_STICKY
        }
        FieldTestRuntime.attach(this, location)
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) { FieldTestRuntime.stopFor("SERVICE_TIMEOUT"); stopSelf() }
    override fun onDestroy() {
        if (registered) { unregisterReceiver(screen); registered = false }
        FieldTestRuntime.serviceStopped(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    private companion object { const val CHANNEL = "vehicle-field-test"; const val STOP = "com.dante.openavm.FIELD_STOP" }
}
