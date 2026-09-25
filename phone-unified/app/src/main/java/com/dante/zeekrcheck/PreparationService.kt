package com.dante.zeekrcheck

import android.app.*
import android.content.*
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.dante.zeekrcheck.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

/** Observes a user-created, bounded session. START_NOT_STICKY never replays a start after a kill. */
class PreparationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var worker: Job? = null
    private var generation = 0
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val key = intent?.getStringExtra("vehicleKey") ?: run { stopSelf(); return START_NOT_STICKY }
        val prefs = runCatching { ComfortPreferences.parse(Json.parseToJsonElement(intent.getStringExtra("preferences")!!)) }.getOrNull()
            ?: run { stopSelf(); return START_NOT_STICKY }
        val model by lazy { ViewModelProvider(application as VehicleApplication, ViewModelProvider.AndroidViewModelFactory.getInstance(application))[CheckViewModel::class.java] }
        val store = AssistantStore.get(this)
        val resume = intent.getBooleanExtra("observeOnly",false)
        val expectedStarted=intent.getLongExtra("started",0)
        if (worker?.isActive == true) {
            val previous = store.state.value.activePreparation
            // Never replace an in-flight request. A completed observer can be retired immediately.
            if (resume || model.operating.value || previous == null || !PreparationProgress.idle(previous,System.currentTimeMillis())) return START_NOT_STICKY
        }
        val run = ++generation
        worker?.cancel()
        notifyState(if (resume) "正在恢复备车进度" else "正在读取车温并准备")
        worker = scope.launch {
            try {
                CloudAccess.loaded(this@PreparationService)
                if (!CloudAccess.accepts(intent)) return@launch
                val departureAt=intent.getLongExtra("departureAt",0).takeIf { it>0 }
                if(!resume && !model.prepareFromService(key, prefs,departureAt,intent.getStringExtra("appointmentId"))) return@launch
                val session = store.state.value.activePreparation ?: return@launch
                if(session.vehicleKey!=key || (resume && (session.started!=expectedStarted || !PreparationProgress.resumable(session,System.currentTimeMillis())))) return@launch
                val observerStart=SystemClock.elapsedRealtime()
                val observeFor=(ComfortPolicy.endAt(session)-System.currentTimeMillis()).coerceIn(0,session.preferences.minutes*60_000L)+120_000
                while(System.currentTimeMillis()<ComfortPolicy.endAt(session)+120_000 && SystemClock.elapsedRealtime()-observerStart<observeFor) {
                    val current=store.state.value.activePreparation ?: break
                    if(current.started!=session.started || current.vehicleKey!=key || current.finished) break
                    val now=System.currentTimeMillis()
                    val visual=ThermalPresentation.from(OverviewStore.get(this@PreparationService).state.value,current,java.time.Instant.ofEpochMilli(now))
                    notifyState("${visual.preparationTitle(current,now)} · ${visual.preparationDetail(current,now)}")
                    // Initial readback follows promptly, then the bounded observer uses fewer GETs.
                    val ended = withTimeoutOrNull(if(now-session.started<90_000) 10_000L else 30_000L) {
                        store.state.first { it.activePreparation?.let { s -> s.started!=session.started || s.vehicleKey!=key || s.finished } != false }
                    }
                    if(ended != null) break
                    model.advancePreparation()
                }
                val current = store.state.value.activePreparation
                if (current?.started==session.started && !current.finished &&
                    (System.currentTimeMillis() >= ComfortPolicy.endAt(current) || SystemClock.elapsedRealtime()-observerStart>=observeFor)) {
                    // A bounded stop was attempted only with fresh parked evidence. Expiry is never an off acknowledgement.
                    store.updatePreparation(current, current.copy(status = "本次跟踪已到时限",phase=PreparationPhase.EXPIRED,finished=true,endedAt=System.currentTimeMillis()))
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                runCatching { store.edit { it.copy(operationMessage = "备车观察已中断，实际结果待核实；未重新发送") } }
            } finally { if (run == generation) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() } }
        }
        return START_NOT_STICKY
    }
    private fun notifyState(message: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("preparation", ui("备车任务"), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 401, VehicleWidgetProvider.actionIntent(this, "prepare").putExtra("executeOnOpen", false), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        startForeground(401, NotificationCompat.Builder(this, "preparation").setSmallIcon(R.drawable.ic_car_check)
            .setContentTitle(ui("OpenAVM · 本次备车")).setContentText(ui(message)).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).build())
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    companion object {
        fun start(context: Context, key: String, prefs: ComfortPreferences, departureAt: Long? = null, appointmentId: String? = null) {
            ContextCompat.startForegroundService(context, Intent(context, PreparationService::class.java)
                .putExtra("vehicleKey", key).putExtra("preferences", prefs.json().toString())
                .putExtra("departureAt",departureAt ?: 0).putExtra("appointmentId",appointmentId).also { CloudAccess.stamp(context, it) })
        }
        fun resume(context:Context,session:PreparationSession) {
            ContextCompat.startForegroundService(context,Intent(context,PreparationService::class.java)
                .putExtra("vehicleKey",session.vehicleKey).putExtra("preferences",session.preferences.json().toString())
                .putExtra("observeOnly",true).putExtra("started",session.started).also { CloudAccess.stamp(context, it) })
        }
    }
}
