package com.dante.zeekrcheck

import android.app.*
import android.content.*
import android.net.Uri
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.dante.zeekrcheck.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/** A visible, user-started temperature task. Process restarts never replay HVAC commands. */
class TemperatureUpdateService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var worker: Job? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val store = AssistantStore.get(this)
        if (worker?.isActive != true) notification(if (intent?.getBooleanExtra("combined", false) == true) "正在刷新车况" else "正在准备更新车温", true)
        val key = intent?.getStringExtra("vehicleKey")
        val widget = intent?.getIntExtra("originWidget", 0) ?: 0
        fun bound() = key != null && OverviewStore.get(this).state.value.vehicleKey == key &&
            (widget == 0 || AppearanceStore.get(this).state.value.widgets[widget]?.vehicleKey == key)
        if (!bound()) {
            if (worker?.isActive != true) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            return START_NOT_STICKY
        }
        // A launcher may still hold local14–20's combined header PendingIntent after upgrade.
        // Never reuse a pre-configuration intent after a configuration or account change.
        if (intent?.getBooleanExtra("combined", false) == true) {
            if (CloudAccess.accepts(intent)) WidgetRefreshService.request(this, interactive = true)
            if (worker?.isActive != true) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            return START_NOT_STICKY
        }
        val expected = intent!!.getLongExtra("expectedId", 0)
        val stop = intent.getBooleanExtra("stop", false)
        // Initialize before the task is written, so process-recovery cannot retire this new task.
        val model by lazy { ViewModelProvider(application as VehicleApplication,
            ViewModelProvider.AndroidViewModelFactory.getInstance(application))[CheckViewModel::class.java] }
        val existing = store.state.value.temperatureUpdate
        if (worker?.isActive == true) {
            if (CloudAccess.accepts(intent) && stop && existing?.id == expected) store.edit { it.copy(temperatureUpdate = existing.copy(cancelRequested = true,
                message = if (existing.ownsAc) "正在结束临时空调" else "正在结束刷新")) }
            return START_NOT_STICKY
        }
        if ((existing?.id ?: 0) != expected || stop && existing?.ownsAc != true) {
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY
        }
        worker = scope.launch {
            val observer = launch { store.state.collectLatest { it.temperatureUpdate?.let { task -> notification(task.message, true) } } }
            try {
                CloudAccess.loaded(this@TemperatureUpdateService)
                if (!CloudAccess.accepts(intent)) return@launch
                model.updateTemperature(key!!, expected, stop) { bound() && CloudAccess.accepts(intent) }
            }
            finally {
              withContext(NonCancellable) {
                observer.cancelAndJoin()
                val unresolved = store.state.value.temperatureUpdate?.takeIf { it.vehicleKey == key && it.needsStop }
                if (unresolved != null) {
                    notification(unresolved.message, false)
                    stopForeground(STOP_FOREGROUND_DETACH)
                } else stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
              }
            }
        }
        return START_NOT_STICKY
    }
    private fun notification(message: String, ongoing: Boolean) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("temperature_update", ui("更新车温"), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 409, VehicleWidgetProvider.actionIntent(this, "temperature_update")
            .putExtra("executeOnOpen", false), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val task = AssistantStore.get(this).state.value.temperatureUpdate
        val builder = NotificationCompat.Builder(this, "temperature_update").setSmallIcon(R.drawable.ic_car_check)
            .setContentTitle(ui("OpenAVM · 更新车温")).setContentText(ui(message)).setStyle(NotificationCompat.BigTextStyle().bigText(ui(message)))
            .setContentIntent(open).setOngoing(ongoing).setOnlyAlertOnce(true)
        if (task?.active == true || task?.needsStop == true) builder.addAction(0, ui(task.temperatureActionLabel(System.currentTimeMillis())), pending(this, task.vehicleKey, task, 0))
        if (ongoing) startForeground(409, builder.build()) else manager.notify(409, builder.build())
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    companion object {
        fun pending(context: Context, key: String?, task: TemperatureUpdate?, widget: Int): PendingIntent =
            if (task?.expiredOwnership(System.currentTimeMillis()) == true)
                PendingIntent.getActivity(context, 4090 + widget, VehicleWidgetProvider.actionIntent(context, "temperature_update")
                    .putExtra("vehicleKey", key).putExtra("originWidget", widget).putExtra("executeOnOpen", false),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            else PendingIntent.getForegroundService(context, 4090 + widget, intent(context, key, task, widget), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        internal fun intent(context: Context, key: String?, task: TemperatureUpdate?, widget: Int, stop: Boolean = task?.let { it.active || it.needsStop } == true) =
            Intent(context, TemperatureUpdateService::class.java).setData(Uri.parse("openavm://temperature/$widget/${task?.id ?: 0}/$stop/false"))
                .putExtra("vehicleKey", key).putExtra("originWidget", widget).putExtra("expectedId", task?.id ?: 0).putExtra("stop", stop).also { CloudAccess.stamp(context, it) }
        fun request(context: Context, stop: Boolean? = null) {
            val key = OverviewStore.get(context).state.value.vehicleKey ?: return
            val task = AssistantStore.get(context).state.value.temperatureUpdate?.takeIf { it.vehicleKey == key }
            runCatching { ContextCompat.startForegroundService(context, intent(context, key, task, 0,
                stop ?: (task?.let { it.active || it.needsStop } == true))) }.onFailure {
                OverviewStore.get(context).edit { it.copy(message = "系统未允许更新车温 · 请打开应用重试") }
            }
        }
    }
}
