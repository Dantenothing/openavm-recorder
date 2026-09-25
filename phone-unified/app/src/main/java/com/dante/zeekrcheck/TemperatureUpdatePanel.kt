package com.dante.zeekrcheck

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dante.zeekrcheck.core.*

@Composable internal fun TemperatureUpdatePanel(task: TemperatureUpdate?, connected: Boolean,
    onAction: () -> Unit, now: Long = System.currentTimeMillis(), showAction: Boolean = true) {
    val context = LocalContext.current
    val visible = task?.visible(now) == true
    if (!showAction && !visible) return
    Column(Modifier.fillMaxWidth().testTag("temperature_task"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (visible) {
            UiText(task!!.message, fontSize = 12.sp, color = if (task.needsStop) MaterialTheme.colorScheme.error else AssistantGreen,
                modifier = Modifier.testTag("temperature_task_message"))
            if (task.active) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("temperature_task_progress"))
        }
        if (showAction && task?.expiredOwnership(now) == true) {
            UiText("临时任务已过期，请在空调页核对并按需要关闭。", fontSize = 12.sp, color = AssistantMuted)
            TextButton(onClick = { context.startActivity(VehicleWidgetProvider.actionIntent(context, "climate")) }) { UiText("打开空调控制") }
        } else if (showAction) Row {
            TextButton(onClick = onAction, enabled = connected && !(task?.active == true && task.cancelRequested),
                modifier = Modifier.testTag("temperature_task_action")) {
                UiText(when { task?.active == true -> "结束取温"; task?.needsStop == true -> "停止临时空调"; else -> "更新车温" })
            }
            if (task?.active != true && task?.needsStop != true) UiText("会短暂开启空调", fontSize = 11.sp,
                color = AssistantMuted, modifier = Modifier.padding(top = 14.dp))
        }
    }
}

@Composable internal fun ManualRefreshButton(refreshing: Boolean, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled && !refreshing,
        modifier = Modifier.testTag("home_refresh")) {
        if (refreshing) {
            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
            Spacer(Modifier.width(6.dp))
        }
        UiText(if (refreshing) "刷新中" else "刷新", fontSize = 12.sp)
    }
}

@Composable internal fun CabinTemperatureReading(reading: String, task: TemperatureUpdate?, enabled: Boolean,
    onUpdate: () -> Unit, now: Long = System.currentTimeMillis()) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        UiText(reading, fontSize = 23.sp, color = AssistantGreen, modifier = Modifier.weight(1f, fill = false))
        IconButton(onClick = onUpdate, enabled = enabled && !(task?.active == true && task.cancelRequested),
            modifier = Modifier.size(48.dp).testTag("home_temperature_refresh")) {
            Icon(painterResource(if (task?.active == true || task?.needsStop == true) R.drawable.ic_widget_stop else R.drawable.ic_widget_refresh),
                contentDescription = ui(task.temperatureActionDescription(now)), tint = AssistantGreen, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable internal fun RefreshExplanation() {
    AssistantCard {
        UiText("车况与车温分开刷新", fontSize = 16.sp)
        UiText("顶部刷新只读取车况。停车休眠时，车温可能仍是上次记录；点击温度旁的刷新按钮可单独更新车温。",
            fontSize = 12.sp, color = AssistantMuted)
        UiText("更新车温时可能短暂开启空调，取温后关闭本次临时空调。已有空调或备车只读取；再次点击可结束取温。",
            fontSize = 11.sp, color = AssistantMuted)
        UiText("5 分钟内已读取车温时，不会重复开启空调。", fontSize = 11.sp, color = AssistantMuted)
    }
}
