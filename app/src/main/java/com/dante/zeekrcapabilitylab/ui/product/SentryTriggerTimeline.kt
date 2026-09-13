package com.dante.zeekrcapabilitylab.ui.product

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.dante.zeekrcapabilitylab.player.PlaybackTrigger
import com.dante.zeekrcapabilitylab.player.TriggerTimeline
import com.dante.zeekrcapabilitylab.util.Utils
import java.util.Locale

internal fun triggerTypeLabel(type: String): String = when (type) {
    "VISUAL_RISK" -> Utils.t("AI alert", "AI 触发")
    "MANUAL" -> Utils.t("Manual trigger", "手动触发")
    else -> Utils.t("Trigger", "触发")
}
private fun markerTime(ms: Long): String = String.format(Locale.ROOT, "%02d:%02d", ms / 60_000, ms / 1000 % 60)

@Composable
internal fun SentryTriggerTimeline(
    markers: List<PlaybackTrigger>, durationMs: Long, positionMs: Long,
    enabled: Boolean, onSeek: (PlaybackTrigger) -> Unit,
) {
    if (markers.isEmpty() || durationMs <= 0) return
    val active = TriggerTimeline.active(markers, positionMs)
    val warning = Color(0xFFFF9800)
    val rail = MaterialTheme.colorScheme.outlineVariant
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(if (active == null) Utils.t("Trigger points · tap to jump", "触发位置 · 点击跳转")
            else Utils.t("At trigger: ", "正在回看触发点：") + markerTime(active.positionMs) + " · " + triggerTypeLabel(active.marker.type),
            color = if (active != null) warning else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall)
        Canvas(Modifier.fillMaxWidth().height(14.dp)) {
            drawLine(rail, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2.dp.toPx())
            markers.forEach { point ->
                val x = (point.positionMs.toDouble() / durationMs).coerceIn(0.0, 1.0).toFloat() * size.width
                val end = ((point.positionMs.toDouble() + 5_000) / durationMs).coerceIn(0.0, 1.0).toFloat() * size.width
                drawRect(warning.copy(alpha = .22f), Offset(x, 0f), Size((end - x).coerceAtLeast(1f), size.height))
                drawLine(warning, Offset(x, 0f), Offset(x, size.height), if (point == active) 4.dp.toPx() else 2.dp.toPx())
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            markers.forEachIndexed { index, point ->
                FilterChip(selected = point == active, enabled = enabled,
                    onClick = { onSeek(point) },
                    label = { Text("${index + 1} · ${markerTime(point.positionMs)} ${triggerTypeLabel(point.marker.type)}") })
            }
        }
    }
    }
}
