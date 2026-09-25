package com.dante.zeekrcheck

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dante.zeekrcheck.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
import java.time.Instant

internal val AssistantGreen = Color(0xFF275C50)
internal val AssistantInk = Color(0xFF203532)
internal val AssistantMuted = Color(0xFF657D73)
internal val AssistantPaper = Color(0xFFF2F5EF)

@Composable fun AssistantTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(primary = AssistantGreen, onPrimary = Color.White,
        secondary = AssistantGreen, background = AssistantPaper, surface = Color(0xFFF8FAF7),
        primaryContainer = Color(0xFFDCEAE0), onPrimaryContainer = AssistantInk,
        secondaryContainer = Color(0xFFDCEAE0), onSecondaryContainer = AssistantInk,
        tertiary = Color(0xFF8D704F), tertiaryContainer = Color(0xFFF1E9D9),
        surfaceContainer = Color(0xFFF0F5EF), surfaceContainerHigh = Color(0xFFECF2E9),
        surfaceContainerHighest = Color(0xFFE1E9DE), surfaceTint = AssistantGreen,
        outline = Color(0xFF8A9D90), outlineVariant = Color(0xFFD7E1D7),
        onSurfaceVariant = AssistantMuted, onSurface = AssistantInk, onBackground = AssistantInk, surfaceVariant = Color(0xFFE7EFEA)), content = content)
}

@Composable internal fun AssistantSessionEffects(model: CheckViewModel) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state by model.state.collectAsStateWithLifecycle()
    val climate by model.climateState.collectAsStateWithLifecycle()
    val assistant by model.assistant.state.collectAsStateWithLifecycle()
    DisposableEffect(lifecycle, model) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) model.onBackground() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(state, climate, assistant) {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) withContext(Dispatchers.IO) {
            val safe = buildJsonObject {
                put("schema", "zeekr-usb-diagnostic/1"); put("toolVersion", BuildConfig.VERSION_NAME)
                put("configReady", state.configReady); put("configSaved", state.configSaved)
                put("connected", state.connected); put("busy", state.busy); put("vehicleCount", state.vehicles.size)
                put("stage", state.stage); put("message", state.message ?: "")
                put("session", model.sessionDiagnostic()); put("climate", model.climateExport())
                state.report?.let { put("report", Json.parseToJsonElement(it.export(Instant.now(), model.climateExport()))) }
            }.toString()
            runCatching { val temp = File(context.filesDir, "diagnostic-state.tmp"); temp.writeText(safe); temp.renameTo(File(context.filesDir, "diagnostic-state.json")) }
        }
    }
}

@Composable internal fun AssistantCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = Color(0xFFF8FAF7), shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(11.dp), content = content)
    }
}

@Composable fun NativeVehicleCard(overview: VehicleOverview, now: Instant, busy: Boolean = false, onRefresh: () -> Unit, onAction: (String) -> Unit) {
    val context = LocalContext.current
    Surface(color = Color(0xFFF8FAF7), shape = RoundedCornerShape(26.dp), modifier = Modifier.fillMaxWidth().testTag("native_vehicle_card")) {
        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                UiText(overview.nickname, raw = true, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = onRefresh, enabled = !busy && !overview.refreshing(now), modifier = Modifier.testTag("overview_refresh")) {
                    UiText(if (busy) "读取中" else overview.queryLabel(now), fontSize = 12.sp)
                    Spacer(Modifier.width(5.dp)); Icon(painterResource(R.drawable.ic_widget_refresh), null, Modifier.size(16.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable { onAction("temperature") }.padding(vertical = 4.dp)) {
                    UiText(overview.cabinCaption(now), color = AssistantMuted, fontSize = 12.sp)
                    UiText(overview.cabin(now), fontSize = 40.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("overview_temperature"))
                    UiText(overview.thermalLabel(now), color = Color(0xFF987344), fontSize = 11.sp)
                    Spacer(Modifier.height(8.dp))
                    UiText((overview.readings["battery"]?.value ?: "— %") + "  ·  " + (overview.readings["range"]?.value ?: "— km"), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                VehicleArt(overview.vehicleKey, Modifier.fillMaxWidth().height(116.dp), overview.thermalState(now), ThermalPresentation.from(overview,null,now).airflow) {
                        context.startActivity(android.content.Intent(context, AppearanceActivity::class.java).putExtra("vehicleKey", overview.vehicleKey))
                    }
                    UiText(overview.motionLabel(now), color = AssistantGreen, fontSize = 12.sp)
                }
            }
            UiText(overview.temperatureTimeLabel(now), color = AssistantMuted, fontSize = 10.sp)
            Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).clickable { onAction("location") }, verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_widget_pin), null, Modifier.size(16.dp), tint = AssistantMuted)
                UiText(if (overview.readings["vehicle_location"] != null) "车辆位置已读取 · 地址待核对" else "车辆位置待读取", fontSize = 12.sp, color = AssistantMuted, modifier = Modifier.padding(start = 5.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickTile("一键备车", "智能调温", R.drawable.ic_widget_temp, Modifier.weight(1f), CardAppearance.tone("prepare",overview,now), "prepare", onAction)
                QuickTile("一键找车", "鸣笛一次", R.drawable.ic_widget_horn, Modifier.weight(1f), CardTone.NEUTRAL, "find", onAction)
            }
            HorizontalDivider(Modifier.padding(top = 3.dp), color = Color(0xFFDDE6DE))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("lock", "guard", "trunk", "port").forEach { id ->
                    val label = VehicleWidgetProvider.controlLabel(id, overview)
                    val icon = VehicleWidgetProvider.controlIcon(id, overview)
                    val tone=CardAppearance.tone(id,overview,now)
                    Surface(onClick = { onAction(id) }, color = Color(tone.background),contentColor=Color(tone.foreground), shape = RoundedCornerShape(13.dp), modifier = Modifier.weight(1f).testTag("quick_$id")) {
                        Column(Modifier.heightIn(min = 60.dp).padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Icon(painterResource(icon), null, Modifier.size(21.dp), tint = Color(tone.foreground))
                            Spacer(Modifier.height(5.dp)); UiText(label, fontSize = 11.sp)
                        }
                    }
                }
            }
            val lock = overview.readings["lock"]?.value ?: "未知"
            val guard = overview.readings["sentry"]?.value ?: "未知"
            UiText(overview.oldFields(now), fontSize = 10.sp, color = AssistantMuted)
            overview.message?.let { UiText(it, fontSize = 11.sp, color = Color(0xFF976A38)) }
        }
    }
}

@Composable private fun QuickTile(title: String, subtitle: String, icon: Int, modifier: Modifier, tone: CardTone, action: String, onAction: (String) -> Unit) {
    Surface(onClick = { onAction(action) }, modifier = modifier.testTag("quick_$action"), shape = RoundedCornerShape(16.dp),
        color = Color(tone.background), contentColor = Color(tone.foreground)) {
        Row(Modifier.heightIn(min = 60.dp).padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(painterResource(icon), null, Modifier.size(22.dp))
            Column { UiText(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold); UiText(subtitle, fontSize = 10.sp) }
        }
    }
}

@Composable internal fun OverviewRows(overview: VehicleOverview, now: Instant) {
    listOf("battery" to "电量", "range" to "续航", "cabin_temperature" to "车内温度", "sentry" to "哨兵", "lock" to "车锁", "odometer" to "里程", "tyres" to "胎压").forEach { (id, label) ->
        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            UiText(label, fontSize = 13.sp)
            Column(horizontalAlignment = Alignment.End) {
                UiText(overview.readings[id]?.value ?: "待读取", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                UiText(overview.readings[id]?.timeLabel(now) ?: "", fontSize = 10.sp, color = AssistantMuted)
            }
        }
    }
}

@Composable internal fun PreferenceDialog(overview: VehicleOverview, onClose: () -> Unit, onSave: (String, Int) -> Unit) {
    var name by remember { mutableStateOf(overview.nickname) }
    var target by remember { mutableIntStateOf(overview.target) }
    AlertDialog(onDismissRequest = onClose, title = { UiText("车辆与备车偏好") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(15.dp)) {
            OutlinedTextField(value = name, onValueChange = { if (it.length <= 20) name = it }, label = { UiText("车辆昵称") }, singleLine = true)
            UiText("下次备车目标", color = AssistantMuted)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { target-- }, enabled = target > 18) { UiText("−") }
                UiText("${target}°C", fontSize = 32.sp)
                OutlinedButton(onClick = { target++ }, enabled = target < 28) { UiText("＋") }
            }
            UiText("保存偏好不会启动车辆。首版温控仍需在实测面板中确认目标。", fontSize = 12.sp)
        }
    }, confirmButton = { TextButton(onClick = { onSave(name.trim(), target) }, enabled = name.isNotBlank()) { UiText("保存") } },
        dismissButton = { TextButton(onClick = onClose) { UiText("取消") } })
}
