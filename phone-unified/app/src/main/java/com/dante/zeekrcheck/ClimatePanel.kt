package com.dante.zeekrcheck

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dante.zeekrcheck.core.*

@Composable
fun ClimatePanel(state: ClimateUiState, busy: Boolean, model: CheckViewModel) {
    var temperature by remember { mutableIntStateOf(22) }
    var minutes by remember { mutableIntStateOf(15) }
    val enabled = state.enabled && !busy && !state.queue.halted
    Surface(shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth().testTag("climate_panel")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    UiText("快捷控制", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    UiText(if (state.enabled) "实车测试已启用" else "启用后，操作会发送到本车", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = state.enabled, onCheckedChange = model::enableClimate, enabled = !busy, modifier = Modifier.testTag("enable_climate"))
            }
            UiText("短按切换档位 · 长按直接选择\n等待执行时可继续改目标，系统只保留最后选择。", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(ClimateChannel.FRONT_LEFT, ClimateChannel.FRONT_RIGHT).forEach { channel ->
                    val reported = state.snapshot?.seat(channel)
                    val desired = state.queue.desired(channel)?.value
                    SeatControl(channel.title, reported, desired, enabled, channel.name, Modifier.weight(1f)) { level ->
                        model.climateTarget(ClimateTarget(channel, level, minutes))
                    }
                }
            }
            UiText("首次测试请核对澳洲车型的左右座椅对应。回读显示未知时，不把它当作关闭。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                UiText("运行时长", style = MaterialTheme.typography.labelMedium)
                listOf(5, 15, 30).forEach { value ->
                    FilterChip(selected = minutes == value, onClick = { minutes = value }, label = { UiText("${value}分") })
                }
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    UiText("空调目标", fontWeight = FontWeight.SemiBold)
                    UiText("车内 ${state.snapshot?.cabinTemperature?.let { "${it}°C" } ?: "未知"}", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(onClick = { if (temperature > 18) temperature-- }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(42.dp)) { UiText("−") }
                UiText("${temperature}°", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp))
                OutlinedButton(onClick = { if (temperature < 28) temperature++ }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(42.dp)) { UiText("+") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { model.climateTarget(ClimateTarget(ClimateChannel.AC, temperature, minutes)) }, enabled = enabled,
                    modifier = Modifier.weight(1f)) { UiText("开启到 ${temperature}°C") }
                OutlinedButton(onClick = { model.climateTarget(ClimateTarget(ClimateChannel.AC, 0, minutes)) }, enabled = enabled) { UiText("关闭空调") }
            }
            UiText("空调设定值暂不回传；受理后可继续选择，结果未确认时队列会暂停。空调出风量待接入。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    UiText(state.queue.phase.label, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("climate_phase"))
                    (state.queue.inFlight ?: state.queue.last)?.let { UiText(it.label, style = MaterialTheme.typography.bodySmall) }
                    state.queue.pending.forEach { UiText("下一目标：${it.label}", style = MaterialTheme.typography.bodySmall) }
                    UiText("状态来源 ${displayTime(state.snapshot?.sourceTime)}", style = MaterialTheme.typography.labelSmall)
                    UiText("本次登录已尝试发送 ${state.requestAttempts} 条控制", style = MaterialTheme.typography.labelSmall)
                    if (state.queue.active) TextButton(onClick = model::cancelPendingClimate) { UiText("取消尚未发送的目标") }
                    if (state.queue.halted) {
                        UiText("未确认的动作不会自动重发。已发送动作无法撤回，请先核对结果。", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = model::acknowledgeClimate, enabled = !busy) { UiText("我已核对结果，清空本轮") }
                    }
                }
            }
            state.message?.let { UiText(it, style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = model::refreshClimate, enabled = !busy && !state.refreshing && !state.queue.active) {
                UiText(if (state.refreshing) "正在读取…" else "刷新座椅与空调状态")
            }
            UiText("切到后台会取消未发送目标；本版不在后台继续排队。", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
internal fun SeatControl(title: String, reported: Int?, desired: Int?, enabled: Boolean, tag: String,
    modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    if (!enabled && expanded) LaunchedEffect(enabled) { expanded = false }
    val choice = desired ?: reported
    fun label(value: Int?) = when (value) { null -> "未知"; 0 -> "关闭"; else -> "${value} 档" }
    Box(modifier) {
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium)
            .combinedClickable(enabled = enabled, onClickLabel = ui("切换座椅档位"), onLongClickLabel = ui("选择关闭或指定档位"),
                onClick = { onSelect(((choice ?: 0) + 1) % 4) }, onLongClick = { expanded = true })
            .testTag("seat_$tag").padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            UiText(title, style = MaterialTheme.typography.labelLarge)
            UiText(label(choice), fontSize = 25.sp, fontWeight = FontWeight.Bold,
                color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                (1..3).forEach { step -> Box(Modifier.weight(1f).height(4.dp)
                    .background(if ((choice ?: 0) >= step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        MaterialTheme.shapes.small)) }
            }
            UiText("回读 ${label(reported)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("seat_reported_$tag"))
            UiText(if (desired != null) "目标 ${label(desired)} · 等待确认" else "长按选择档位", style = MaterialTheme.typography.labelSmall)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                (0..3).forEach { level ->
                    OutlinedButton(onClick = { expanded = false; onSelect(level) },
                        contentPadding = PaddingValues(horizontal = 10.dp), modifier = Modifier.testTag("seat_choice_${tag}_$level")) {
                        UiText(if (level == 0) "关闭" else "$level")
                    }
                }
            }
        }
    }
}
