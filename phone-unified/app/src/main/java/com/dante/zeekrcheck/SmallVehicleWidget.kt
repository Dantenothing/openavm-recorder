package com.dante.zeekrcheck

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import com.dante.zeekrcheck.core.*
import java.time.Instant

class SquareVehicleWidgetProvider : VehicleWidgetProvider()
class StripVehicleWidgetProvider : VehicleWidgetProvider()

/** Four direct controls with a separate app entry. Uses the same binding and command service as larger cards. */
internal object SmallVehicleWidget {
    fun views(context: Context, overview: VehicleOverview, strip: Boolean, now: Instant = Instant.now(),
              widgetId: Int = 0, binding: WidgetAppearance? = null, controlsEnabled: Boolean = true,
              preparation: PreparationSession? = null, guardPaused: Boolean = false, preview: Boolean = false, temperatureUpdate: TemperatureUpdate? = null): RemoteViews {
        val view = RemoteViews(context.packageName, if (strip) R.layout.vehicle_widget_bar else R.layout.vehicle_widget_square)
        val progress = PreparationProgress.message(preparation, overview.message, now.toEpochMilli())
        val source = overview.readings["cabin_temperature"]
        val message = when {
            !controlsEnabled -> "请在 App 选择绑定车辆"
            overview.refreshing(now) -> overview.refreshMessage()
            overview.refreshingAt != null -> "读取未完成 · 点刷新重试"
            progress != null -> progress
            overview.vehicleKey == null -> "请先连接车辆"
            guardPaused -> "本次自动守护已暂停"
            else -> overview.temperatureTimeLabel(now)
        }
        view.setUiText(R.id.widget_energy, (overview.readings["battery"]?.value ?: "— %") + if (source != null && !source.fresh(now)) " · 旧" else "")
        view.setUiText(R.id.widget_cabin, overview.cabin(now))
        view.setUiText(R.id.widget_message, message)
        view.setUiDescription(R.id.widget_message, message)
        view.setUiDescription(R.id.widget_cabin, "${overview.cabin(now)} · ${source?.timeLabel(now) ?: "来源未知"}")
        view.setUiDescription(R.id.widget_open, "打开 OpenAVM · ${overview.nickname}")
        view.setUiDescription(R.id.widget_refresh, "刷新车辆状态")
        val actions = mapOf(R.id.widget_prepare to "prepare", R.id.widget_find to "find", R.id.widget_lock to "lock", R.id.widget_guard to "guard")
        actions.forEach { (id, action) ->
            val tone = CardAppearance.tone(action, overview, now, preparation)
            val icon = when (action) {
                "prepare" -> if (tone == CardTone.ACTIVE) R.drawable.ic_widget_temp_active else R.drawable.ic_widget_temp
                "find" -> R.drawable.ic_widget_horn
                else -> VehicleWidgetProvider.stateIcon(action, overview, now)
            }
            view.setImageViewResource(id, icon)
            view.setInt(id, "setBackgroundResource", when (tone) {
                CardTone.ACTIVE -> R.drawable.widget_primary
                CardTone.ATTENTION -> R.drawable.widget_control_attention
                CardTone.NEUTRAL -> R.drawable.widget_control_idle
            })
            val label = when (action) {
                "prepare" -> (preparation?.let { ThermalPresentation.from(overview,it,now).preparationTitle(it,now.toEpochMilli()) + " · " } ?: "") + PreparationControl.label(preparation, now.toEpochMilli())
                "find" -> "找车鸣笛一次"
                else -> VehicleWidgetProvider.controlLabel(action, overview)
            }
            view.setUiDescription(id, "$label · $message")
            view.setBoolean(id, "setEnabled", controlsEnabled && !preview)
            if (!preview) view.setOnClickPendingIntent(id, VehicleWidgetProvider.cardPendingIntent(context, id, action,
                binding?.vehicleKey ?: overview.vehicleKey, if(action=="prepare") PreparationControl.shown(preparation,overview.vehicleKey,now.toEpochMilli())
                else overview.readings[CardControl.field(action)]?.value, widgetId))
        }
        val busy = overview.actionRunning(now) || overview.refreshing(now) || PreparationProgress.inProgress(preparation, now.toEpochMilli())
        view.setViewVisibility(R.id.widget_small_progress, if (busy) View.VISIBLE else View.GONE)
        view.setProgressBar(R.id.widget_small_progress, 100, 0, true)
        if (!preview) {
            view.setOnClickPendingIntent(R.id.widget_open, PendingIntent.getActivity(context, widgetId,
                VehicleWidgetProvider.openAppIntent(context, widgetId), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        TemperatureWidget.bind(context, view, overview, temperatureUpdate, now.toEpochMilli(), true, controlsEnabled, preview, widgetId, iconOnly = true)
        return view
    }
}
