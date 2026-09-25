package com.dante.zeekrcheck

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.dante.zeekrcheck.core.AssistantState

@Composable internal fun HomeGuardSettingsCard(state: AssistantState, onEnabled: (Boolean) -> Unit, onResume: () -> Unit) {
    AssistantCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            UiText("确认到家后自动关闭", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Switch(state.homeGuardEnabled, onEnabled, enabled = state.home?.verified == true,
                modifier = Modifier.testTag("home_guard_toggle"))
        }
        UiText("连续两次确认车辆在家附近、已驻车、车速为零，且两次至少间隔 1 分钟，自动关闭原厂哨兵。沿用家的范围与 100 米定位缓冲。", fontSize = 12.sp, color = AssistantMuted)
        if (state.home?.verified != true) UiText("先设置并核对家的位置", fontSize = 12.sp, color = AssistantMuted)
        UiText(if (!state.homeGuardEnabled) "到家自动关闭未启用" else if (state.paused) "自动化总开关已暂停" else state.guardMessage,
            color = AssistantGreen, fontSize = 13.sp, modifier = Modifier.testTag("home_guard_status"))
        if (state.homeGuard.held) {
            UiText("本次手动保持开启。确认开始新行程后恢复；解锁取物、定位漂移或重启应用不会取消你的选择。", fontSize = 12.sp, color = AssistantMuted)
            OutlinedButton(onClick = onResume, enabled = state.homeGuardEnabled && !state.paused,
                modifier = Modifier.testTag("home_guard_resume")) { UiText("恢复本次到家自动关闭") }
        }
        UiText("手动开启优先。在本应用开启会立即保留；原厂 App 或车机的操作，需要在家中观察到哨兵由关变开才能识别。", fontSize = 11.sp, color = AssistantMuted)
        UiText("首次发现到家后约 70 秒复查，最多补查 3 次；车辆旧数据不会算作第二次确认。后台省电、网络或车辆未更新可能延迟，卡片显示实际回传状态。", fontSize = 11.sp, color = AssistantMuted)
    }
}
