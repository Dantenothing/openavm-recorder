package com.dante.zeekrcheck

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.*
import android.os.Build
import com.dante.zeekrcheck.core.*
import java.time.Instant
import kotlinx.coroutines.*

object DepartureScheduler {
    fun permitted(context: Context) = Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    private fun pending(context: Context, id: String, occurrence: Long = 0) = PendingIntent.getBroadcast(context, 0,
        Intent(context, DepartureReceiver::class.java).setAction("com.dante.zeekrcheck.DEPARTURE.$id")
            .putExtra("planId", id).putExtra("occurrence", occurrence).also { CloudAccess.stamp(context, it) }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun cancel(context: Context, id: String) { context.getSystemService(AlarmManager::class.java).cancel(pending(context, id)) }
    fun sync(context: Context) {
        val store = AssistantStore.get(context); val state = store.state.value; val manager = context.getSystemService(AlarmManager::class.java)
        val now = Instant.now()
        val results = mutableMapOf<String, String>()
        state.plans.forEach { plan ->
            cancel(context, plan.id)
            if (CloudAccess.authorized && !state.paused && plan.enabled && permitted(context)) {
                val departure = plan.next(now) ?: run {
                    if (plan.handled == 0L) results[plan.id] = "已错过准备时间，本次未补发"
                    return@forEach
                }
                val trigger = departure.toEpochMilli() - plan.leadMinutes * 60_000L
                // Reboot or clock changes never cause a late catch-up start.
                if (trigger <= now.toEpochMilli()) return@forEach
                results[plan.id] = runCatching {
                    manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending(context, plan.id, departure.toEpochMilli()))
                    "已安排 · ${displayTime(Instant.ofEpochMilli(trigger))} 开始准备"
                }.getOrDefault("未能安排，请检查系统定时权限")
            } else {
                results[plan.id] = when { !CloudAccess.authorized -> "云端尚未连接，预约已暂停"; state.paused -> "已暂停新执行"; !plan.enabled -> "已停用"; else -> "等待闹钟与提醒权限" }
            }
        }
        if (state.plans.any { results[it.id]?.let { result -> result != it.result } == true }) {
            store.edit { current -> current.copy(plans = current.plans.map { plan -> results[plan.id]?.let { plan.copy(result = it) } ?: plan }) }
        }
        AwayGuardService.schedule(context)
    }
}

class DepartureReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Main).launch {
            try { CloudAccess.loaded(context); receive(context, intent) } finally { pending.finish() }
        }
    }
    private fun receive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) VehicleWidgetProvider.updateAll(context)
        if (intent.action in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
                AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)) { DepartureScheduler.sync(context); return }
        if (!CloudAccess.accepts(intent)) return
        val store = AssistantStore.get(context)
        val plan = store.state.value.plans.firstOrNull { it.id == intent.getStringExtra("planId") } ?: return
        val occurrence = intent.getLongExtra("occurrence", 0)
        if (!plan.enabled || store.state.value.paused || occurrence <= plan.handled || occurrence <= 0) return
        val lateness = System.currentTimeMillis() - (occurrence - plan.leadMinutes * 60_000L)
        val valid = lateness in 0..120_000 && OverviewStore.get(context).state.value.vehicleKey == plan.vehicleKey
        val result = if (valid) "本次已触发，正在读取车温" else "本次已跳过：已过执行窗口或车辆已更改"
        // Persist before launching: duplicate broadcasts cannot replay the physical start.
        store.edit { it.copy(plans = it.plans.map { p -> if (p.id == plan.id) p.copy(handled = occurrence, result = result) else p }) }
        store.record(plan.title, result)
        if (valid) runCatching { PreparationService.start(context, plan.vehicleKey, plan.preferences, occurrence, plan.id) }
            .onFailure { store.record(plan.title, "系统未允许后台启动，本次未重试") }
        DepartureScheduler.sync(context)
    }
}
