package com.dante.zeekrcheck

import android.app.*
import android.content.*
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.dante.zeekrcheck.core.*
import kotlinx.coroutines.*

/** Only explicit taps start this service. Non-sticky: a process death must never replay a vehicle write. */
class CardActionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var worker: Job? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = getSystemService(NotificationManager::class.java)
        notification.createNotificationChannel(NotificationChannel("card_action", ui("卡片操作"), NotificationManager.IMPORTANCE_LOW))
        startForeground(406, NotificationCompat.Builder(this, "card_action").setSmallIcon(R.drawable.ic_car_check)
            .setContentTitle("OpenAVM").setContentText(ui("正在处理卡片操作，进度显示在卡片上")).setOngoing(true).setOnlyAlertOnce(true).build())
        val action = intent?.getStringExtra("cardAction")?.takeIf { it in CardControl.actions }
        val key = intent?.getStringExtra("vehicleKey")
        val widgetId = intent?.getIntExtra("originWidget", 0) ?: 0
        if (action == null || key == null) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
        fun bound() = widgetId == 0 || AppearanceStore.get(this).state.value.widgets[widgetId]?.vehicleKey == key
        if (!bound()) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
        val overview=OverviewStore.get(this)
        if(overview.state.value.vehicleKey!=key) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
        if (action == "guard" && intent.getStringExtra("shownValue") == "开启") AssistantStore.get(this).pauseGuard(key)
        if (action == "guard" && intent.getStringExtra("shownValue") == "关闭") AssistantStore.get(this).holdHomeGuard(key)
        if (worker?.isActive == true) return START_NOT_STICKY
        val stopping = action=="prepare" && intent.getStringExtra("shownValue")?.startsWith("stop:")==true
        overview.edit { it.copy(actionInFlight=action,actionAt=System.currentTimeMillis(),message=if(stopping) "收到停止操作 · 正在结束备车"
            else if(action=="prepare") "收到备车操作 · 正在准备" else "收到操作 · 正在连接车辆") }
        val model = ViewModelProvider(application as VehicleApplication, ViewModelProvider.AndroidViewModelFactory.getInstance(application))[CheckViewModel::class.java]
        worker = scope.launch {
            try { withTimeout(95_000) { model.cardAction(action, key, intent.getStringExtra("shownValue"), ::bound) } }
            finally {
                if(overview.state.value.vehicleKey==key) overview.edit { it.copy(actionInFlight=null,actionAt=null) }
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    companion object {
        fun intent(context: Context, action: String, key: String?, shown: String?, widgetId: Int = 0) =
            Intent(context, CardActionService::class.java).putExtra("cardAction", action).putExtra("vehicleKey", key)
                .putExtra("shownValue", shown).putExtra("originWidget", widgetId)
        fun start(context: Context, action: String, shownPreparation: String? = null) {
            val overview = OverviewStore.get(context).state.value
            val shown = if(action=="prepare") shownPreparation ?: PreparationControl.shown(
                AssistantStore.get(context).state.value.activePreparation,overview.vehicleKey,System.currentTimeMillis())
                else overview.readings[CardControl.field(action)]?.value
            runCatching { ContextCompat.startForegroundService(context, intent(context, action, overview.vehicleKey,
                shown)) }.onFailure {
                OverviewStore.get(context).edit { it.copy(message = "系统未允许启动操作 · 请稍后重试") }
            }
        }
    }
}
