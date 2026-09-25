package com.dante.zeekrcheck

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dante.zeekrcheck.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

/** Purely local editor: opening a car image does not create a vehicle client or submit any command. */
class AppearanceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val key = intent.getStringExtra("vehicleKey")?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }
            ?: OverviewStore.get(this).state.value.vehicleKey
        setContent { AssistantTheme {
            Column(Modifier.fillMaxSize().background(AssistantPaper).statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                UiText("车辆外观", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                AppearanceScreen(key) { finish() }
            }
        } }
    }
}

@Composable internal fun VehicleArt(vehicleKey: String?, modifier: Modifier = Modifier, thermalState: String = "neutral", airflow: Airflow = Airflow.NONE, onClick: (() -> Unit)? = null) {
    val context = LocalContext.current; val store = remember { AppearanceStore.get(context) }
    val saved by store.state.collectAsStateWithLifecycle()
    val appearance = saved.vehicles[vehicleKey] ?: VehicleAppearance()
    Box(if (onClick == null) modifier else modifier.clickable(onClick = onClick)) {
        ThermalAmbient(thermalState,Modifier.matchParentSize())
        AppearanceImage(appearance,true,720,Modifier.matchParentSize())
        ThermalAirflow(airflow,Modifier.matchParentSize())
    }
}

@Composable internal fun AppearanceImage(appearance: VehicleAppearance, showPlate: Boolean, width: Int, modifier: Modifier) {
    val context = LocalContext.current
    val key = appearance.cacheKey(width,showPlate)
    val bitmap by produceState<android.graphics.Bitmap?>(AppearanceRenderer.ready(appearance,width,showPlate), key) {
        value = AppearanceRenderer.ready(appearance,width,showPlate)
        value = withContext(Dispatchers.Default) { runCatching { AppearanceRenderer.load(context,appearance,width,showPlate) }.getOrNull() }
    }
    if (bitmap != null) Image(bitmap!!.asImageBitmap(),ui("7X 车辆外观"),modifier)
    else Image(painterResource(R.drawable.zeekr_7x_default),ui("7X 默认外观"),modifier)
}

@Composable internal fun AppearanceScreen(vehicleKey: String?, onClose: () -> Unit) {
    val context = LocalContext.current
    val store = remember { AppearanceStore.get(context) }
    val data by store.state.collectAsStateWithLifecycle()
    val current by OverviewStore.get(context).state.collectAsStateWithLifecycle()
    if (vehicleKey == null || !store.readable) {
        AssistantCard { UiText(if (vehicleKey == null) "请先选择车辆，再设置它的外观" else "本机外观记录未能读取，请重启后重试"); TextButton(onClick = onClose) { UiText("返回") } }
        return
    }
    val nickname = if (vehicleKey == current.vehicleKey) current.nickname else data.names[vehicleKey] ?: "绑定车辆"
    val initial = remember(vehicleKey) { store.appearance(vehicleKey) }
    val widgets = remember(vehicleKey) { data.widgets.filterValues { it.vehicleKey == vehicleKey }.mapValues { it.value.showPlateText } }
    AppearanceEditor(initial,nickname,widgets,onClose) { draft, privacy ->
        store.save(vehicleKey,draft,nickname,privacy)
        VehicleWidgetProvider.updateAll(context)
        onClose()
    }
}

@Composable internal fun AppearanceEditor(initial: VehicleAppearance, nickname: String, widgets: Map<Int,Boolean>,
    onClose: () -> Unit, onSave: (VehicleAppearance, Map<Int,Boolean>) -> Unit) {
    var draft by remember { mutableStateOf(initial) }
    var privacy by remember { mutableStateOf(widgets) }
    var error by remember { mutableStateOf<String?>(null) }
    var reset by remember { mutableStateOf(false) }
    var compact by remember { mutableStateOf(true) }
    val problem = draft.problem()
    var validPreview by remember { mutableStateOf(initial) }
    LaunchedEffect(draft) { if (problem == null) validPreview = draft }
    AssistantCard {
        UiText(nickname,raw = true,fontWeight = FontWeight.Bold, fontSize = 21.sp)
        AppearanceImage(validPreview,true,1000,Modifier.fillMaxWidth().height(205.dp).testTag("appearance_car"))
        UiText("ZEEKR 7X · 本地展示外观", color = AssistantMuted, fontSize = 12.sp)
    }
    AssistantCard {
        UiText("车身颜色",fontSize = 19.sp,fontWeight = FontWeight.Bold)
        val presets = listOf("白" to "#F2F3EF","黑" to "#20272B","灰" to "#818B90","蓝" to "#356EAD","红" to "#B83F3F","黄" to "#E8BD48")
        Row(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.SpaceBetween) {
            presets.forEach { (name,color) -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(40.dp).border(if (draft.bodyColor.equals(color,true)) 3.dp else 1.dp,if (draft.bodyColor.equals(color,true)) AssistantGreen else Color(0xFFD4DBD7),CircleShape)
                    .padding(4.dp).background(Color(android.graphics.Color.parseColor(color)),CircleShape).clickable { draft = draft.copy(bodyColor = color) }.testTag("paint_$name"))
                UiText(name,fontSize = 11.sp,color = AssistantMuted)
            } }
        }
        AppearanceColorPicker("自定义车身颜色",draft.bodyColor) { draft = draft.copy(bodyColor = it) }
        UiText("通用展示色，不代表原厂漆色的精确色差。",fontSize = 11.sp,color = AssistantMuted)
    }
    AssistantCard {
        UiText("展示车牌",fontSize = 19.sp,fontWeight = FontWeight.Bold)
        CheckPreference("显示自定义车牌",draft.plateEnabled) { draft = draft.copy(plateEnabled = it) }
        OutlinedTextField(draft.plateText,{ draft = draft.copy(plateText = it) },label = { UiText("车牌文字") },supportingText = { UiText("字母、数字、空格和连字符 · 最多 12 个字符") },singleLine = true,
            isError = draft.plateText.length > 12,modifier = Modifier.fillMaxWidth().testTag("plate_text"))
        PlateSwatches("车牌底色", "plate_bg", PlatePalette.backgrounds, draft.plateBackground) { draft = draft.copy(plateBackground = it) }
        PlateSwatches("车牌文字颜色", "plate_fg", PlatePalette.characters, draft.plateForeground) { draft = draft.copy(plateForeground = it) }
        if (draft.plateEnabled && draft.contrast() < 4.5) {
            UiText("文字和底色较接近，小尺寸下可能看不清",color = MaterialTheme.colorScheme.error,fontSize = 12.sp)
        }
        val plate = remember(validPreview) { AppearanceRenderer.plate(validPreview,true) }
        Image(plate.asImageBitmap(),ui("展示车牌放大预览"),Modifier.fillMaxWidth().height(96.dp))
        UiText("号码只用于本助手展示，不用于识别车辆。",fontSize = 11.sp,color = AssistantMuted)
    }
    AssistantCard {
        UiText("桌面显示",fontSize = 19.sp,fontWeight = FontWeight.Bold)
        CheckPreference("桌面显示车牌号码",draft.widgetPlateDefault) { show ->
            draft = draft.copy(widgetPlateDefault = show); privacy = privacy.mapValues { show }
        }
        UiText("关闭后，小组件图片中也不含号码。App 内保存的文字仍保留。",fontSize = 11.sp,color = AssistantMuted)
        privacy.forEach { (id,show) -> CheckPreference("卡片 #$id · 显示号码",show) { privacy = privacy + (id to it) } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(compact,{compact=true},label={UiText("4×2")}); FilterChip(!compact,{compact=false},label={UiText("4×3")})
        }
        WidgetAppearancePreview(validPreview,draft.widgetPlateDefault,compact,nickname)
        UiText("布局预览 · 示例车况。实际大小由手机桌面网格决定。",fontSize = 11.sp,color = AssistantMuted)
    }
    AssistantCard {
        (error ?: problem)?.let { UiText(it,color = MaterialTheme.colorScheme.error,modifier = Modifier.testTag("appearance_error")) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onClose,modifier = Modifier.weight(1f).testTag("appearance_cancel")) { UiText("取消") }
            Button(onClick = { runCatching { onSave(draft,privacy) }.onFailure { error = "保存失败，请重试；已保存的设置未改变" } },enabled = problem == null,modifier = Modifier.weight(1f).testTag("appearance_save")) { UiText("保存外观") }
        }
        TextButton(onClick = { reset = true }) { UiText("恢复默认外观") }
    }
    if (reset) AlertDialog(onDismissRequest = {reset=false},title={UiText("恢复默认外观？")},text={UiText("本页草稿恢复为白色车身、关闭自定义车牌；保存后生效。昵称、家的位置、预约和登录不变。")},
        confirmButton={TextButton(onClick={draft=VehicleAppearance();privacy=privacy.mapValues { false };reset=false}){UiText("恢复外观草稿")}},dismissButton={TextButton(onClick={reset=false}){UiText("取消")}})
}

@Composable private fun PlateSwatches(title: String, tag: String, choices: List<PlatePalette.Swatch>, selected: String, changed: (String) -> Unit) {
    UiText(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    choices.chunked(6).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            row.forEach { swatch ->
                val chosen = selected.equals(swatch.hex, true)
                Column(Modifier.weight(1f).clickable { changed(swatch.hex) }.testTag("${tag}_${swatch.hex.drop(1)}"), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(44.dp).border(if (chosen) 3.dp else 1.dp, if (chosen) AssistantGreen else Color(0xFFD4DBD7), RoundedCornerShape(10.dp))
                        .padding(4.dp).background(Color(android.graphics.Color.parseColor(swatch.hex)), RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                        if (chosen) UiText("✓", color = if (swatch.hex in listOf("#000000", "#1B427D", "#004437", "#A32727")) Color.White else Color.Black, fontWeight = FontWeight.Bold)
                    }
                    UiText(swatch.name, fontSize = 10.sp, color = AssistantMuted)
                }
            }
            repeat(6 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
    if (choices.none { it.hex.equals(selected, true) }) UiText("当前已保存颜色 $selected · 选择色块后替换", fontSize = 11.sp, color = AssistantMuted)
}

@Composable private fun WidgetAppearancePreview(appearance: VehicleAppearance, showPlate: Boolean, compact: Boolean, nickname: String) {
    val context = LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(null,appearance,showPlate) {
        value = null
        value = withContext(Dispatchers.Default) { runCatching { AppearanceRenderer.load(context,appearance,520,showPlate) }.getOrNull() }
    }
    val now = remember { Instant.now() }
    val example = remember(nickname) { VehicleOverview(nickname=nickname,readings=mapOf(
        "cabin_temperature" to OverviewReading("24.0 °C",now.toEpochMilli(),now.toEpochMilli()),
        "battery" to OverviewReading("68%",now.toEpochMilli(),now.toEpochMilli()),"range" to OverviewReading("412 km",now.toEpochMilli(),now.toEpochMilli()))) }
    // Use actual RemoteViews layouts; remove all PendingIntents so a preview cannot control a car.
    AndroidView(factory={ android.widget.FrameLayout(it).apply { descendantFocusability=android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS } },
        modifier=Modifier.fillMaxWidth().height(if(compact)224.dp else 340.dp).testTag("appearance_widget_preview"),
        update={ host ->
            val views=VehicleWidgetProvider.views(context,example,compact,now,"家 · 示例位置", preview=true)
            bitmap?.let { views.setImageViewBitmap(R.id.widget_car,it) }
            host.removeAllViews(); host.addView(views.apply(context,host))
        })
}

@Composable private fun AppearanceColorPicker(title: String, color: String, changed: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true },modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.size(24.dp).background(Color(android.graphics.Color.parseColor(color)),CircleShape).border(1.dp,AssistantMuted,CircleShape))
        Spacer(Modifier.width(10.dp));UiText(title);Spacer(Modifier.weight(1f));UiText(color,fontSize = 12.sp)
    }
    if (open) {
        val initial = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(android.graphics.Color.parseColor(color),it) } }
        var hue by remember { mutableFloatStateOf(initial[0]) };var saturation by remember { mutableFloatStateOf(initial[1]) };var brightness by remember { mutableFloatStateOf(initial[2]) }
        var hex by remember { mutableStateOf(color) }
        fun updateHex() { hex = "#%06X".format(android.graphics.Color.HSVToColor(floatArrayOf(hue,saturation,brightness)) and 0xFFFFFF) }
        val valid = hex.matches(Regex("#[0-9A-Fa-f]{6}"))
        AlertDialog(onDismissRequest = {open=false},title={UiText(title)},text={Column {
            Box(Modifier.fillMaxWidth().height(62.dp).background(if(valid)Color(android.graphics.Color.parseColor(hex)) else AssistantMuted,RoundedCornerShape(12.dp)))
            UiText("色相",modifier=Modifier.padding(top=12.dp));Slider(hue,{hue=it;updateHex()},valueRange=0f..360f)
            UiText("浓淡");Slider(saturation,{saturation=it;updateHex()},valueRange=0f..1f)
            UiText("明暗");Slider(brightness,{brightness=it;updateHex()},valueRange=0f..1f)
            OutlinedTextField(hex,{ hex=it; if(it.matches(Regex("#[0-9A-Fa-f]{6}"))) {
                val hsv=FloatArray(3);android.graphics.Color.colorToHSV(android.graphics.Color.parseColor(it),hsv);hue=hsv[0];saturation=hsv[1];brightness=hsv[2]
            } },label={UiText("自定义色值（可选）")},singleLine=true,isError=!valid)
        }},confirmButton={TextButton(onClick={changed(hex);open=false},enabled=valid){UiText("使用此颜色")}},dismissButton={TextButton(onClick={open=false}){UiText("取消")}})
    }
}
