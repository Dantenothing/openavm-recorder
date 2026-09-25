package com.dante.zeekrcheck

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dante.zeekrcheck.core.*

/** Each desktop action has its own small task; Back returns directly to the launcher. */
class WidgetActionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = intent.getStringExtra("widgetAction")?.takeIf { it in setOf("prepare", "find", "lock", "guard", "trunk", "port", "windows", "climate", "temperature", "temperature_update", "location") }
            ?: run { finish(); return }
        val expectedKey = intent.getStringExtra("vehicleKey")
        val widgetId = intent.getIntExtra("originWidget", 0)
        val entered = System.currentTimeMillis()
        val shownPreparation=PreparationControl.shown(AssistantStore.get(this).state.value.activePreparation,
            OverviewStore.get(this).state.value.vehicleKey,entered)
        setContent {
            val model = assistantModel()
            val account by model.state.collectAsStateWithLifecycle()
            val store = remember { OverviewStore.get(this) }; val overview by store.state.collectAsStateWithLifecycle()
            val appearances by AppearanceStore.get(this).state.collectAsStateWithLifecycle()
            val targetMatches = widgetId == 0 || (expectedKey != null && overview.vehicleKey == expectedKey && appearances.widgets[widgetId]?.vehicleKey == expectedKey)
            val assistant by model.assistant.state.collectAsStateWithLifecycle()
            var preferences by remember { mutableStateOf(action == "temperature") }
            var initialActionConsumed by rememberSaveable { mutableStateOf(savedInstanceState != null || !intent.getBooleanExtra("executeOnOpen", true)) }
            AssistantSessionEffects(model)
            LaunchedEffect(account.connected, account.busy, targetMatches) {
                if (!targetMatches) initialActionConsumed = true
                if (targetMatches && !initialActionConsumed && account.connected && !account.busy && System.currentTimeMillis() - entered < 10_000) {
                    initialActionConsumed = true
                    if (action == "find") model.bodyAction(BodyAction.HORN)
                    if (action == "prepare") CardActionService.start(this@WidgetActionActivity,"prepare",shownPreparation)
                }
            }
            AssistantTheme {
                Column(Modifier.fillMaxWidth().heightIn(max = 680.dp).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    UiText(mapOf("prepare" to "本次备车", "find" to "找车鸣笛", "lock" to "车辆门锁", "guard" to "原厂哨兵", "trunk" to "尾门", "port" to "充电口",
                        "windows" to "车窗", "climate" to "车内舒适", "temperature" to "备车偏好", "temperature_update" to "更新车温", "location" to "车辆位置")[action] ?: "车辆操作", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    if (!account.connected) {
                        UiText(if (account.busy) account.stage else "车辆未连接，请恢复保存的登录。", fontSize = 13.sp)
                        if (account.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        else if (account.sessionSaved) Button(onClick = model::reconnect) { UiText("恢复登录") }
                    }
                    if (!targetMatches) UiText("这张卡片绑定的是另一辆车，请先在 App 选择对应车辆。未发送任何操作。")
                    else when (action) {
                        "prepare" -> PreparationPanel(model) { preferences = true }
                        "climate" -> ComfortControlPanel(model)
                        "location" -> PlacesScreen(model)
                        "temperature" -> TextButton(onClick = { preferences = true }) { UiText("编辑备车设置") }
                        "temperature_update" -> {
                            UiText(overview.cabin(java.time.Instant.now()), fontSize = 28.sp)
                            UiText(overview.temperatureTimeLabel(java.time.Instant.now()), fontSize = 11.sp)
                            UiText("短暂开启空调后读取车温，再请求停止。已有空调或备车只读取，不会关闭。", fontSize = 13.sp)
                            TemperatureUpdatePanel(assistant.temperatureUpdate?.takeIf { it.vehicleKey == overview.vehicleKey },
                                account.connected && !account.busy, { TemperatureUpdateService.request(this@WidgetActionActivity) })
                        }
                        else -> BodyControlPanel(action, model)
                    }
                    OutlinedButton(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) { UiText("返回") }
                }
                if (preferences && targetMatches) PreferenceEditor(assistant.preferences.copy(target = overview.target), { preferences = false; if (action == "temperature") finish() }) { p ->
                    model.assistant.edit { it.copy(preferences = p) }; store.preferences(overview.nickname, p.target); preferences = false
                    if (action == "temperature") finish()
                }
            }
        }
    }
}

@Composable internal fun ComfortControlPanel(model: CheckViewModel) {
    val account by model.state.collectAsStateWithLifecycle()
    val climate by model.climateState.collectAsStateWithLifecycle()
    val assistant by model.assistant.state.collectAsStateWithLifecycle()
    val operating by model.operating.collectAsStateWithLifecycle()
    var temperature by remember { mutableIntStateOf(assistant.preferences.target) }
    val enabled = account.connected && !account.busy && !operating && (!assistant.operationPending || climate.queue.active) && !climate.queue.halted
    LaunchedEffect(account.connected) { if (account.connected) model.refreshClimate() }
    fun submit(target: ClimateTarget) { model.enableClimate(true); model.climateTarget(target) }
    Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
        UiText("车内 ${climate.snapshot?.cabinTemperature?.let { "${it}°C" } ?: "未知"}", fontSize = 20.sp, color = AssistantGreen)
        UiText("目标温度 ${temperature}°C", fontWeight = FontWeight.Bold)
        Slider(temperature.toFloat(), { temperature = it.toInt() }, valueRange = 18f..28f, steps = 9)
        AcTemperatureActions(temperature, assistant.preferences.minutes, enabled, ::submit)
        UiText("LO / HI 请求 5 分钟，不改变舒适目标。需要恢复目标时，点上方的温度按钮。", fontSize = 11.sp, color = AssistantMuted)
        val snapshot = climate.snapshot
        UiText("空调回传：${when(snapshot?.acOn) { true -> "开启"; false -> "关闭"; null -> "未知" }} · 出风回传：${when(snapshot?.blowerActive) { true -> "开启"; false -> "关闭"; null -> "未知" }}", fontSize = 12.sp, color = AssistantMuted)
        UiText("回传时间 ${displayTime(snapshot?.sourceTime)}", fontSize = 11.sp, color = AssistantMuted)
        SeatModeControls(climate.snapshot, climate.queue, enabled) { channel, level ->
            submit(ClimateTarget(channel, level, assistant.preferences.minutes))
        }
        UiText("轻点切档，长按直接选关闭 / 1 / 2 / 3。连续选择会合并成最新目标，排队发送。", fontSize = 12.sp, color = AssistantMuted)
        SteeringHeatControl(snapshot, climate.queue, enabled, ::submit)
        UiText(climate.queue.phase.label, fontWeight = FontWeight.SemiBold)
        (climate.queue.inFlight ?: climate.queue.last)?.let { UiText(it.label, fontSize = 12.sp) }
        if (climate.queue.active) TextButton(onClick = model::cancelPendingClimate) { UiText("取消未发送的目标") }
        if (climate.queue.halted) TextButton(onClick = model::acknowledgeClimate) { UiText("我已核对结果，继续") }
        UiText("车辆暂不回传空调设定值或 LO / HI 模式；已受理不代表模式已确认。方向盘可核对开关状态，表面温度未直接测量。", fontSize = 11.sp, color = AssistantMuted)
        OperationStatus(model)
    }
}

/** Stateless controls let UI tests exercise every button without a ViewModel or a vehicle command. */
@Composable internal fun AcTemperatureActions(temperature: Int, minutes: Int, enabled: Boolean, onSubmit: (ClimateTarget) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSubmit(ClimateTarget(ClimateChannel.AC, temperature, minutes)) }, enabled = enabled,
                modifier = Modifier.weight(1f).testTag("ac_comfort")) { UiText("设为 ${temperature}°C") }
            OutlinedButton(onClick = { onSubmit(ClimateTarget(ClimateChannel.AC, 0)) }, enabled = enabled,
                modifier = Modifier.testTag("ac_off")) { UiText("关闭空调") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onSubmit(ClimateTarget(ClimateChannel.AC, temperature, 5, AcMode.LO)) }, enabled = enabled,
                modifier = Modifier.weight(1f).testTag("ac_lo")) { UiText("LO 快速降温") }
            OutlinedButton(onClick = { onSubmit(ClimateTarget(ClimateChannel.AC, temperature, 5, AcMode.HI)) }, enabled = enabled,
                modifier = Modifier.weight(1f).testTag("ac_hi")) { UiText("HI 快速升温") }
        }
    }
}

@Composable internal fun SteeringHeatControl(snapshot: ClimateSnapshot?, queue: ClimateQueueState, enabled: Boolean,
                                             onSubmit: (ClimateTarget) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        UiText("方向盘加热", fontWeight = FontWeight.SemiBold)
        UiText("回传：${when(snapshot?.steeringHeat) { true -> "开启"; false -> "关闭"; null -> "未知" }}",
            modifier = Modifier.testTag("wheel_reported"), fontSize = 12.sp, color = AssistantMuted)
        queue.desired(ClimateChannel.STEERING)?.let { UiText(it.label, fontSize = 11.sp, color = AssistantMuted) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onSubmit(ClimateTarget(ClimateChannel.STEERING, 1, 8)) }, enabled = enabled,
                modifier = Modifier.weight(1f).testTag("wheel_on")) { UiText("开启 8 分钟") }
            OutlinedButton(onClick = { onSubmit(ClimateTarget(ClimateChannel.STEERING, 0)) }, enabled = enabled,
                modifier = Modifier.weight(1f).testTag("wheel_off")) { UiText("关闭") }
        }
    }
}

@Composable internal fun SeatModeControls(snapshot: ClimateSnapshot?, queue: ClimateQueueState, enabled: Boolean,
                                         onSelect: (ClimateChannel, Int) -> Unit) {
    var heating by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !heating, onClick = { heating = false }, label = { UiText("座椅通风") },
                modifier = Modifier.testTag("seat_mode_vent"))
            FilterChip(selected = heating, onClick = { heating = true }, label = { UiText("座椅加热") },
                modifier = Modifier.testTag("seat_mode_heat"))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (if (heating) listOf(ClimateChannel.HEAT_LEFT, ClimateChannel.HEAT_RIGHT) else listOf(ClimateChannel.FRONT_LEFT, ClimateChannel.FRONT_RIGHT)).forEachIndexed { index, channel ->
                key(channel) { SeatControl(if (index == 0) "左前座椅" else "右前座椅", snapshot?.seat(channel), queue.desired(channel)?.value,
                    enabled, channel.name, Modifier.weight(1f)) { onSelect(channel, it) } }
            }
        }
    }
}
