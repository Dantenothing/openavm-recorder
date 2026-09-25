package com.dante.zeekrcheck

import android.app.PendingIntent
import android.app.AlarmManager
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.util.SizeF
import android.widget.RemoteViews
import com.dante.zeekrcheck.core.*
import java.time.Instant

class CompactVehicleWidgetProvider : VehicleWidgetProvider()

open class VehicleWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { update(context, manager, it) }
        AwayGuardService.schedule(context)
        WidgetRefreshService.request(context, interactive = false)
    }
    override fun onDeleted(context: Context, ids: IntArray) {
        AppearanceStore.get(context).edit { it.copy(widgets = it.widgets - ids.toSet()) }
        AwayGuardService.schedule(context)
    }
    override fun onDisabled(context: Context) { AwayGuardService.schedule(context) }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) { update(context, manager, id) }
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == EXPIRE) { updateAll(context); return }
        if (intent.action != REFRESH) return
        val store = OverviewStore.get(context)
        val expected = intent.getStringExtra("vehicleKey")
        if (expected != null && expected != store.state.value.vehicleKey) return
        val widget = intent.getIntExtra("originWidget", 0)
        if (widget != 0 && AppearanceStore.get(context).state.value.widgets[widget]?.vehicleKey != expected) return
        WidgetRefreshService.request(context, interactive = true)
    }
    companion object {
        val providers = listOf(VehicleWidgetProvider::class.java, CompactVehicleWidgetProvider::class.java, SquareVehicleWidgetProvider::class.java, StripVehicleWidgetProvider::class.java)
        const val REFRESH = "com.dante.zeekrcheck.WIDGET_REFRESH"
        const val EXPIRE = "com.dante.zeekrcheck.WIDGET_CACHE_EXPIRE"
        const val JOB_ID = 3101
        internal fun refreshIntent(context: Context, key: String?, widget: Int) =
            Intent(context, VehicleWidgetProvider::class.java).setAction(REFRESH)
                .setData(android.net.Uri.parse("openavm://status-refresh/$widget"))
                .putExtra("vehicleKey", key).putExtra("originWidget", widget)
        internal fun refreshPending(context: Context, key: String?, widget: Int): PendingIntent =
            PendingIntent.getBroadcast(context, widget, refreshIntent(context, key, widget),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            providers.forEach { provider ->
                manager.getAppWidgetIds(ComponentName(context, provider)).forEach { update(context, manager, it) }
            }
            val state=OverviewStore.get(context).state.value
            val preparation=AssistantStore.get(context).state.value.activePreparation
            val now=System.currentTimeMillis()
            val expires=listOfNotNull(state.readings["cabin_temperature"]?.source?.plus(300_001),
                state.climateSource?.plus(300_001), state.climateFetched?.plus(300_001),
                state.actionAt?.plus(100_001), state.refreshingAt?.plus(100_001), preparation?.lastSource?.plus(300_001),preparation?.deadline,
                preparation?.endedAt?.takeIf { it > 0 }?.plus(45_001),
                AssistantStore.get(context).state.value.temperatureUpdate?.finishedAt?.takeIf { it > 0 }?.plus(60_001))
                .filter { it>now }.minOrNull()
            val alarm = context.getSystemService(AlarmManager::class.java)
            val pending = PendingIntent.getBroadcast(context, 3102, Intent(context, VehicleWidgetProvider::class.java).setAction(EXPIRE), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            alarm.cancel(pending)
            if (expires != null && expires > System.currentTimeMillis()) alarm.set(AlarmManager.RTC, expires, pending)
        }
        private fun update(context: Context, manager: AppWidgetManager, id: Int) {
            val defaultHeight = if (manager.getAppWidgetInfo(id)?.provider?.className == CompactVehicleWidgetProvider::class.java.name) 224 else 360
            val compact = manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, defaultHeight) < 340
            val assistant = AssistantStore.get(context).state.value
            val location = assistant.location
            val label = HomeZone.label(location, assistant.home, assistant.homeRadius, System.currentTimeMillis())
            val overview = OverviewStore.get(context).state.value
            val appearances = AppearanceStore.get(context)
            val binding = runCatching { appearances.binding(id, overview.vehicleKey, overview.nickname) }.getOrNull()
            val selected = binding?.canControl(overview.vehicleKey) == true
            val displayed = if (selected) overview else VehicleOverview(vehicleKey = binding?.vehicleKey,
                nickname = appearances.state.value.names[binding?.vehicleKey] ?: "未绑定车辆", message = "请在 App 切换到此车辆后操作")
            val provider = manager.getAppWidgetInfo(id)?.provider?.className
            if (provider in setOf(SquareVehicleWidgetProvider::class.java.name, StripVehicleWidgetProvider::class.java.name)) {
                manager.updateAppWidget(id, SmallVehicleWidget.views(context, displayed, provider == StripVehicleWidgetProvider::class.java.name,
                    widgetId = id, binding = binding, controlsEnabled = selected,
                    preparation = assistant.activePreparation?.takeIf { selected && it.vehicleKey == displayed.vehicleKey },
                    guardPaused = selected && assistant.parkingGuard.manualPaused,
                    temperatureUpdate = assistant.temperatureUpdate?.takeIf { selected && it.vehicleKey == displayed.vehicleKey }))
                return
            }
            val appearance = appearances.appearance(binding?.vehicleKey)
            val bitmap = AppearanceRenderer.ready(appearance, 520, binding?.showPlateText == true)
            if (bitmap == null) AppearanceRenderer.request(context, appearance, 520, binding?.showPlateText == true) {
                if (appearances.appearance(binding?.vehicleKey) == appearance) updateAll(context)
            }
            fun layout(small: Boolean, strip: Boolean = false): RemoteViews = views(context, displayed, small,
                locationLabel = if (selected) label else "车辆未选中 · 请先切换", strip = strip,
                widgetId = id, binding = binding, controlsEnabled = selected,
                preparation=assistant.activePreparation?.takeIf { selected && it.vehicleKey==displayed.vehicleKey },
                guardPaused=selected && assistant.parkingGuard.manualPaused,
                temperatureUpdate=assistant.temperatureUpdate?.takeIf { selected && it.vehicleKey == displayed.vehicleKey }).apply {
                    if (bitmap != null) setImageViewBitmap(R.id.widget_car, bitmap)
                }
            val responsive = if (Build.VERSION.SDK_INT >= 31) RemoteViews(mapOf(
                SizeF(250f, 224f) to layout(true),
                SizeF(250f, 340f) to layout(false),
                SizeF(300f, 100f) to layout(true, true),
            )) else layout(compact)
            manager.updateAppWidget(id, responsive)
        }
        fun views(context: Context, overview: VehicleOverview, compact: Boolean, now: Instant = Instant.now(), locationLabel: String? = null, strip: Boolean = false, preview: Boolean = false, widgetId: Int = 0, binding: WidgetAppearance? = null, controlsEnabled: Boolean = true, preparation: PreparationSession? = null, guardPaused: Boolean = false, temperatureUpdate: TemperatureUpdate? = null): RemoteViews {
            val thermal = ThermalPresentation.from(overview, preparation, now)
            val displayMessage = PreparationProgress.message(preparation,overview.message,now.toEpochMilli())
            val views = RemoteViews(context.packageName, if (strip) R.layout.vehicle_widget_strip else if (compact) R.layout.vehicle_widget_compact else R.layout.vehicle_widget)
            views.setTextViewText(R.id.widget_name, overview.nickname)
            views.setUiText(R.id.widget_open, "打开应用 ↗")
            views.setUiText(R.id.widget_find, if (strip) "找车" else "一键找车")
            views.setUiText(R.id.widget_prepare, if (strip) "备车" else "一键备车")
            views.setUiText(R.id.widget_cabin, overview.cabin(now))
            if (!strip) views.setUiText(R.id.widget_cabin_caption, overview.cabinCaption(now))
            views.setUiText(R.id.widget_energy, (overview.readings["battery"]?.value ?: "— %") + " · " + (overview.readings["range"]?.value ?: "— km"))
            views.setUiText(R.id.widget_climate, overview.motionLabel(now))
            views.setImageViewResource(R.id.widget_car, R.drawable.zeekr_7x_default)
            views.setInt(R.id.widget_car, "setBackgroundResource", 0)
            views.setUiDescription(R.id.widget_car, "${overview.thermalLabel(now)} · ${thermal.label} · ${overview.motionLabel(now)}")
            if (!strip) {
                views.setViewVisibility(R.id.widget_ambient, if (thermal.ambient in setOf("hot","cold")) android.view.View.VISIBLE else android.view.View.GONE)
                if (thermal.ambient in setOf("hot","cold")) views.setImageViewBitmap(R.id.widget_ambient,ThermalWidgetArtwork.ambientBitmap(thermal.ambient))
                if (compact) views.setUiText(R.id.widget_cabin_caption,
                    if (overview.readings["cabin_temperature"]?.fresh(now) != true) overview.cabinCaption(now)
                    else if (thermal.airflow != Airflow.NONE) thermal.compactLabel else overview.thermalLabel(now))
                else views.setUiText(R.id.widget_thermal, thermal.label)
                views.setViewVisibility(R.id.widget_airflow, if (thermal.airflow == Airflow.NONE) android.view.View.GONE else android.view.View.VISIBLE)
                if (thermal.airflow != Airflow.NONE) views.setImageViewBitmap(R.id.widget_airflow, ThermalWidgetArtwork.bitmap(thermal.airflow))
            }
            val temp = overview.readings["cabin_temperature"]
            views.setUiText(R.id.widget_source, overview.temperatureTimeLabel(now))
            views.setUiText(R.id.widget_location, locationLabel ?: "车辆位置待核实 · 点开查看")
            mapOf(R.id.widget_lock to "lock", R.id.widget_guard to "guard", R.id.widget_trunk to "trunk", R.id.widget_port to "port").forEach { (id, action) ->
                views.setUiText(id, controlLabel(action, overview))
                if (!strip) views.setTextViewCompoundDrawables(id, 0, stateIcon(action, overview, now), 0, 0)
                val observed = overview.readings[CardControl.field(action)]
                views.setUiDescription(id, "${controlLabel(action, overview)} · 最近上报 · ${observed?.timeLabel(now) ?: "状态未知"} · ${CardControl.target(action, observed?.value)?.title ?: "点按读取状态"}")
            }
            val message = when {
                overview.refreshing(now) -> overview.refreshMessage()
                overview.refreshingAt != null -> "上次刷新未完成 · 可点右上角重试"
                displayMessage != null -> displayMessage
                overview.vehicleKey == null -> "请先在 App 连接并选择车辆"
                guardPaused -> "本次自动守护已暂停"
                else -> overview.oldFields(now)
            }
            views.setUiText(R.id.widget_message, message)
            // Source time and action feedback have separate rows, including on the compact card.
            views.setUiDescription(R.id.widget_cabin, "${overview.cabin(now)} · ${temp?.timeLabel(now) ?: "来源未知"}")
            views.setUiText(R.id.widget_refresh, overview.queryLabel(now))
            views.setUiDescription(R.id.widget_refresh, "${overview.queryLabel(now)} · 点击刷新车况；查询时间不代表车辆采样时间")
            if (guardPaused && overview.readings["sentry"]?.value != "开启") views.setUiText(R.id.widget_guard, "本次暂停")
            if (!strip && compact && !overview.refreshing(now) && displayMessage == null) views.setUiText(R.id.widget_message,
                if (guardPaused) "本次自动守护已暂停" else overview.oldFields(now))
            val connecting=overview.actionRunning(now) && overview.actionInFlight=="prepare" && (preparation==null || preparation.finished)
            val preparing=PreparationProgress.inProgress(preparation,now.toEpochMilli()) && preparation?.let { PreparationProgress.phase(it,now.toEpochMilli()) in setOf(PreparationPhase.READING,PreparationPhase.SENDING,PreparationPhase.ACCEPTED,PreparationPhase.STOPPING) }==true
            if(preparation!=null) {
                views.setUiText(R.id.widget_prepare,if(strip) thermal.preparationTitle(preparation,now.toEpochMilli()) else thermal.preparationButton(preparation,now.toEpochMilli(),overview.target))
            }
            if(connecting) views.setUiText(R.id.widget_prepare,if(strip) "正在准备…" else "正在准备…\n目标 ${overview.target}°C")
            views.setUiDescription(R.id.widget_prepare, "${preparation?.let { thermal.preparationTitle(it,now.toEpochMilli()) } ?: "一键备车"} · ${PreparationControl.label(preparation,now.toEpochMilli())}")
            if(!strip) {
                views.setViewVisibility(R.id.widget_prepare_progress,if(connecting || preparing) android.view.View.VISIBLE else android.view.View.GONE)
                views.setProgressBar(R.id.widget_prepare_progress,100,0,true)
                views.setTextViewCompoundDrawablesRelative(R.id.widget_prepare,
                    if(CardAppearance.tone("prepare",overview,now,preparation)==CardTone.ACTIVE) R.drawable.ic_widget_temp_active else R.drawable.ic_widget_temp,0,0,0)
            }
            mapOf(R.id.widget_prepare to "prepare",R.id.widget_find to "find",R.id.widget_lock to "lock",
                R.id.widget_guard to "guard",R.id.widget_trunk to "trunk",R.id.widget_port to "port").forEach { (id,action) ->
                val tone=CardAppearance.tone(action,overview,now,preparation)
                views.setInt(id,"setBackgroundResource",when(tone) {
                    CardTone.NEUTRAL -> R.drawable.widget_control_idle
                    CardTone.ACTIVE -> R.drawable.widget_primary
                    CardTone.ATTENTION -> R.drawable.widget_control_attention
                })
                views.setTextColor(id,tone.foreground.toInt())
            }
            // Keep refresh tappable after a process death. The receiver deduplicates live work;
            // disabling a RemoteViews button could otherwise strand it until the next host update.
            views.setBoolean(R.id.widget_refresh, "setEnabled", controlsEnabled && !preview)
            TemperatureWidget.bind(context, views, overview, temperatureUpdate, now.toEpochMilli(), strip, controlsEnabled, preview, widgetId)
            if (preview) return views
            val open=PendingIntent.getActivity(context,widgetId,openAppIntent(context,widgetId),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_open,open)
            views.setOnClickPendingIntent(R.id.widget_name,open)
            val actions = linkedMapOf(R.id.widget_prepare to "prepare", R.id.widget_find to "find", R.id.widget_lock to "lock", R.id.widget_guard to "guard", R.id.widget_trunk to "trunk", R.id.widget_port to "port", R.id.widget_cabin to "temperature", R.id.widget_location to "location")
            actions.forEach { (viewId, action) ->
                views.setBoolean(viewId, "setEnabled", controlsEnabled)
                val data = android.net.Uri.parse("zeekr-widget://$widgetId/$action")
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                val pending = if (action in CardControl.actions) cardPendingIntent(context, viewId, action,
                    binding?.vehicleKey ?: overview.vehicleKey, if(action=="prepare") PreparationControl.shown(preparation,overview.vehicleKey,now.toEpochMilli())
                    else overview.readings[CardControl.field(action)]?.value, widgetId)
                else PendingIntent.getActivity(context, viewId, actionIntent(context, action).setData(data)
                    .putExtra("originWidget", widgetId).putExtra("vehicleKey", binding?.vehicleKey), flags)
                views.setOnClickPendingIntent(viewId, pending)
            }
            views.setOnClickPendingIntent(R.id.widget_car, PendingIntent.getActivity(context, widgetId,
                Intent(context, AppearanceActivity::class.java).setData(android.net.Uri.parse("zeekr-widget://$widgetId/appearance"))
                    .putExtra("vehicleKey", binding?.vehicleKey).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            return views
        }
        internal fun cardPendingIntent(context: Context, viewId: Int, action: String, vehicleKey: String?, shown: String?, widgetId: Int) =
            PendingIntent.getForegroundService(context, viewId, CardActionService.intent(context, action, vehicleKey, shown, widgetId)
                .setData(android.net.Uri.parse("zeekr-widget://$widgetId/$action" + if(action=="prepare") "?intent=${android.net.Uri.encode(shown)}" else "")), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        internal fun openAppIntent(context:Context,widgetId:Int)=Intent(context,MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setData(android.net.Uri.parse("zeekr-widget://$widgetId/open-app"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        fun controlIcon(action: String, overview: VehicleOverview): Int = when (action) {
            "lock" -> when (overview.readings["lock"]?.value) { "已锁" -> R.drawable.ic_widget_lock; "未锁" -> R.drawable.ic_widget_unlock; else -> R.drawable.ic_widget_lock_unknown }
            "guard" -> if (overview.readings["sentry"]?.value == "开启") R.drawable.ic_widget_shield else R.drawable.ic_widget_shield_off
            "trunk" -> if (overview.readings["trunk"]?.value == "打开") R.drawable.ic_widget_trunk_open else R.drawable.ic_widget_trunk
            else -> if (overview.readings["port"]?.value == "打开") R.drawable.ic_widget_port_open else R.drawable.ic_widget_port
        }
        internal fun stateIcon(action:String,overview:VehicleOverview,now:Instant):Int = when(CardAppearance.tone(action,overview,now)) {
            CardTone.ACTIVE -> R.drawable.ic_widget_shield_active
            CardTone.ATTENTION -> when(action) {
                "lock" -> R.drawable.ic_widget_unlock_attention
                "trunk" -> R.drawable.ic_widget_trunk_open_attention
                else -> R.drawable.ic_widget_port_open_attention
            }
            CardTone.NEUTRAL -> controlIcon(action,overview)
        }
        fun controlLabel(action: String, overview: VehicleOverview): String {
            val value = overview.readings[CardControl.field(action)]?.value
            return when (action) {
                "lock" -> when (value) { "已锁" -> "已锁车"; "未锁" -> "已解锁"; else -> "车锁未知" }
                "guard" -> when (value) { "开启" -> "哨兵已开"; "关闭" -> "哨兵已关"; else -> "哨兵未知" }
                "trunk" -> when (value) { "打开" -> "尾门已开"; "关闭" -> "尾门已关"; else -> "尾门未知" }
                else -> when (value) { "打开" -> "充电口开"; "关闭" -> "充电口关"; else -> "打开充电口" }
            }
        }
        fun carResource(overview: VehicleOverview, now: Instant): Int {
            val air = ThermalPresentation.from(overview,null,now).airflow != Airflow.NONE
            return when (overview.thermalState(now)) {
                "hot" -> if (air) R.drawable.widget_car_hot_air else R.drawable.widget_car_hot
                "cold" -> if (air) R.drawable.widget_car_cold_air else R.drawable.widget_car_cold
                else -> if (air) R.drawable.widget_car_neutral_air else R.drawable.widget_car_neutral
            }
        }
        fun actionIntent(context: Context, action: String) = Intent(context, WidgetActionActivity::class.java)
            .setAction("com.dante.zeekrcheck.widget.$action").putExtra("widgetAction", action)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
    }
}
