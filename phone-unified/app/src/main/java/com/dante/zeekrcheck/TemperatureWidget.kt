package com.dante.zeekrcheck

import android.content.Context
import android.view.View
import android.widget.RemoteViews
import com.dante.zeekrcheck.core.*
import java.time.Instant

internal object TemperatureWidget {
    fun bind(context: Context, views: RemoteViews, overview: VehicleOverview, task: TemperatureUpdate?, now: Long,
        small: Boolean, enabled: Boolean, preview: Boolean, widgetId: Int, iconOnly: Boolean = false) {
        val instant = Instant.ofEpochMilli(now)
        val refreshing = overview.refreshing(instant)
        // Status refresh and temperature sampling have different PendingIntent identities and owners.
        if (iconOnly) views.setImageViewResource(R.id.widget_refresh, R.drawable.ic_widget_refresh)
        else {
            views.setUiText(R.id.widget_refresh, if (refreshing) "刷新中" else "刷新")
            views.setTextViewCompoundDrawablesRelative(R.id.widget_refresh, 0, 0, R.drawable.ic_widget_refresh, 0)
        }
        views.setUiDescription(R.id.widget_refresh, "刷新车辆状态 · ${overview.queryLabel(instant)}")
        views.setViewVisibility(R.id.widget_temperature_update, View.VISIBLE)
        views.setImageViewResource(R.id.widget_temperature_update,
            if (task?.active == true || task?.needsStop == true) R.drawable.ic_widget_stop else R.drawable.ic_widget_refresh)
        views.setUiDescription(R.id.widget_temperature_update, task.temperatureActionDescription(now))
        views.setViewVisibility(R.id.widget_temperature_progress, if (task?.active == true) View.VISIBLE else View.GONE)
        views.setProgressBar(R.id.widget_temperature_progress, 100, 0, true)
        // Keep recovery taps available after process death. Services deduplicate active work.
        views.setBoolean(R.id.widget_refresh, "setEnabled", enabled && !preview)
        views.setBoolean(R.id.widget_temperature_update, "setEnabled", enabled && !preview)
        if (task?.visible(now) == true && (task.needsStop || !overview.actionRunning(instant)))
            views.setUiText(R.id.widget_message, if (small && task.needsStop) "临时空调停止待确认" else task.message)
        if (!preview) {
            views.setOnClickPendingIntent(R.id.widget_refresh,
                VehicleWidgetProvider.refreshPending(context, overview.vehicleKey, widgetId))
            views.setOnClickPendingIntent(R.id.widget_temperature_update,
                TemperatureUpdateService.pending(context, overview.vehicleKey, task, widgetId))
        }
    }
}
