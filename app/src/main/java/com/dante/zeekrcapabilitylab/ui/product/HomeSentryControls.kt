package com.dante.zeekrcapabilitylab.ui.product

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dante.zeekrcapabilitylab.BuildConfig
import com.dante.zeekrcapabilitylab.sentry.runtime.GuardRunDuration
import com.dante.zeekrcapabilitylab.sentry.runtime.GuardState
import com.dante.zeekrcapabilitylab.util.Utils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun sentryDurationLabel(value: GuardRunDuration): String = when (value) {
    GuardRunDuration.UNLIMITED -> Utils.t("No time limit", "不限时")
    GuardRunDuration.MINUTES_30 -> Utils.t("30 minutes", "30 分钟")
    else -> Utils.t("{0} hours", "{0} 小时", value.minutes!! / 60)
}

@Composable
internal fun SentryDurationSelector(value: GuardRunDuration, enabled: Boolean, compact: Boolean = false, onChange: (GuardRunDuration) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled) {
            Text((if (compact) "" else Utils.t("Duration: ", "运行时长：")) + sentryDurationLabel(value) + " ▾", maxLines = 1)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            GuardRunDuration.entries.forEach { duration ->
                DropdownMenuItem(text = { Text(sentryDurationLabel(duration)) }, onClick = {
                    expanded = false; onChange(duration)
                })
            }
        }
    }
}

@Composable
internal fun SentryInfoButton() {
    var visible by remember { mutableStateOf(false) }
    IconButton(onClick = { visible = true }, modifier = Modifier.semantics {
        contentDescription = Utils.t("Sentry instructions and limitations", "哨兵说明与限制")
    }) { Text("!", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
    if (visible) AlertDialog(
        onDismissRequest = { visible = false },
        title = { Text(Utils.t("OpenAVM Sentry · instructions & limitations", "OpenAVM 哨兵 · 说明与限制")) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!BuildConfig.SENTRY_CAPTURE_ENABLED) {
                Text(Utils.t("Sentry is temporarily disabled in this version because unattended parking capture is not reliable yet.",
                    "本版本暂时停用哨兵，离车后的持续运行仍未达到可靠使用要求。"))
                Text(Utils.t("Normal recording and time-lapse remain available. Existing Sentry videos, USB export and diagnostic copying are still accessible from Videos & diagnostics.",
                    "普通录像和延时摄影可继续使用。已有哨兵视频、USB 导出和诊断复制仍可从“视频与诊断”进入。"))
            } else {
                Text(Utils.t("Finalized Sentry events are automatically copied to a writable USB and verified. Local originals remain available; unavailable USB or failed copies are shown in event details.",
                    "哨兵事件结束后自动保存到可写 USB，并校验完整性。本机原件继续保留；USB 不可用或保存失败会在事件详情中说明。"))
                Text(Utils.t("While occupied, the app records normally. When the app is in the background and both interactive and main screen signals are continuously confirmed off for about 1 second, it starts switching to Sentry. Both screen signals must return for 5 seconds to resume normal recording.",
                    "开启后在车普通录像。应用在后台、系统交互与主屏均关闭，连续确认约 1 秒后开始切入哨兵；交互与主屏恢复 5 秒后回到普通录像。"))
                Text(Utils.t("Sentry keeps up to 3 minutes of video in 768 MiB of RAM. Triggering saves that history and 60 seconds afterwards. Repeated triggers extend the tail up to 120 seconds after the first trigger. Early triggers may have less history.",
                    "哨兵用 768 MiB RAM 保留最多 3 分钟预录；触发后保存预录及后续 60 秒，重复触发最多延长至首次触发后 120 秒。刚开启时历史可能不足。"))
                Text(Utils.t("AI triggering is a trial and must be enabled and calibrated in AI settings. It can miss events or produce false alerts, especially at night, with reflections or occlusion. Highlighted positions show detected/manual triggers, not proof of a collision.",
                    "AI 触发需在设置中开启试用并确认四路方向/区域。夜间、反光、遮挡可能误报或漏报。回看高亮表示 AI 或手动触发的位置，不代表已确认碰撞。"))
                Text(Utils.t("Orange ticks mark the recorded trigger times. The following 5-second highlight is a navigation aid, not a measured danger duration. Missing or unsaved video cannot be highlighted.",
                    "橙色刻度表示已记录的触发时刻，随后 5 秒高亮仅用于回看定位，不代表危险持续时间。未保存或缺失的视频无法标记。"))
                Text(Utils.t("The timer counts from Enable, including time spent recording normally. No time limit removes this timer only. Stop ends the entire run; an app/process restart does not re-enable it.",
                    "定时从点击开启开始，包含普通录像阶段；不限时只取消倒计时。停止会结束本次运行，应用或进程重启后不会自动开启。"))
                Text(Utils.t("Camera mapping and recording settings are fixed for this run. Stop and start again to apply changes. Recent run reports are retained locally and included when copying diagnostics.",
                    "本次运行沿用开启时的相机与录像配置，修改设置后需停止再开启。最近几次运行记录会在本机保留，复制诊断时一并带上。"))
                Text(Utils.t("Keeping the camera, encoder and CPU awake uses vehicle power. Sustained vehicle/OEM power-off can still stop the app. Screen signals are only presence estimates; an unexpected screen wake can resume normal recording.",
                    "相机、编码器和 CPU 持续工作会耗电，车辆或系统断电仍可能结束运行。屏幕状态只是离车/回车的代理信号，车辆自行亮屏可能触发普通录像。"))
                Text(Utils.t("Severe heat, memory pressure, storage errors or unreleased camera resources can stop the run. Saved Sentry events have a 10 GiB budget; export or delete them when space runs low. The OEM camera has priority.",
                    "严重发热、内存压力、存储异常、相机资源无法释放等情况会停止运行。哨兵事件区预算 10 GiB，空间不足时需导出或删除。原厂相机功能优先。"))
            }
        } },
        confirmButton = { TextButton(onClick = { visible = false }) { Text(Utils.t("Got it", "知道了")) } },
    )
}

@Composable
internal fun HomeSentryControls(
    state: GuardState, active: Boolean, preparing: Boolean, canStart: Boolean,
    duration: GuardRunDuration, onDurationChange: (GuardRunDuration) -> Unit,
    onStart: () -> Unit, onStop: () -> Unit, onDetails: () -> Unit,
) {
    if (!BuildConfig.SENTRY_CAPTURE_ENABLED) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(Utils.t("Sentry temporarily disabled", "哨兵暂时停用"), modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium)
            SentryInfoButton()
            TextButton(onClick = onDetails) { Text(Utils.t("Videos & diagnostics", "视频与诊断")) }
        }
        return
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = if (active) onStop else onStart,
                enabled = !preparing && (active || canStart), modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(if (preparing) Utils.t("Preparing…", "准备中…") else if (active) Utils.t("Stop Sentry", "停止哨兵")
                    else Utils.t("Enable Sentry", "开启哨兵"), maxLines = 1)
            }
            SentryDurationSelector(if (active) state.runDuration else duration,
                !active && !preparing && canStart, compact = true, onChange = onDurationChange)
            SentryInfoButton()
            IconButton(onClick = onDetails, enabled = !preparing, modifier = Modifier.semantics {
                contentDescription = Utils.t("Sentry videos, AI and diagnostics", "哨兵详情：视频、AI 与诊断")
            }) { Text("⋯", style = MaterialTheme.typography.titleLarge) }
        }
        val failure = state.firstFailure
        if (failure != null || state.error != null) {
            val at = failure?.atEpochMs?.let { SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(it)) + " · " }.orEmpty()
            val reason = failure?.reason ?: state.error.orEmpty()
            val description = if (reason in setOf("NO_DECLARED_SURROUND_PROFILE", "RUN_SURROUND_CONFIGURATION_MISSING", "未找到环视相机配置"))
                Utils.t("surround camera configuration unavailable", "未取得环视相机配置")
            else Utils.t("see saved stop reason in ⋯", "点 ⋯ 查看已保存的停止原因")
            Text(at + Utils.t("Sentry stopped: ", "哨兵已停止：") + description,
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        } else if (state.phase == "INTERRUPTED" && !active) {
            Text(Utils.t("The previous Sentry run was interrupted. See ⋯ for the last recorded state.",
                "上次哨兵运行中断，点 ⋯ 查看最后记录的状态。"), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
        } else if (active && !preparing) {
            Text((if (state.manualMode != null) Utils.t("In-car test · return to the launcher to resume automatic switching · ", "车内测试中 · 返回桌面即恢复自动切换 · ")
                else Utils.t("Automatic switching on · ", "自动切换已开启 · ")) + state.message + if (state.runtime.runExpiresAtEpochMs > 0) Utils.t(" · until ", " · 至 ") +
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(state.runtime.runExpiresAtEpochMs)) else "",
                style = MaterialTheme.typography.bodySmall, maxLines = 2)
            if (state.mode == "SENTRY" && state.ai.status != "TRIAL_ACTIVE") Text(
                Utils.t("AI auto-trigger is not ready. Open ⋯ for its status.", "AI 自动触发尚未就绪，点 ⋯ 查看状态。"),
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        } else if (!canStart && !preparing) {
            Text(Utils.t("Stop recording before enabling Sentry.", "请先停止当前录像，再开启哨兵。"), style = MaterialTheme.typography.bodySmall)
        }
    }
}
