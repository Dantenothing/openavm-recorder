package com.dante.zeekrcheck

import android.os.Bundle
import android.content.pm.ApplicationInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dante.zeekrcheck.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant

private val Forest = Color(0xFF28654F)
private val Paper = Color(0xFFF3F5F0)
private val Ink = Color(0xFF1D3027)
private val Amber = Color(0xFF805500)

class MainActivity : ComponentActivity() {
    private var openAvmDestination by mutableStateOf<String?>(null)
    private var navigationRequest by mutableIntStateOf(0)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OpenAvmIntegration.initialize(this)
        if(savedInstanceState==null) acceptNavigation(intent)
        enableEdgeToEdge()
        // The assistant uses a light surface even when the phone is in dark mode.
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
        setContent { AssistantApp(initialDestination=openAvmDestination,navigationRequest=navigationRequest) }
    }
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptNavigation(intent)
    }
    private fun acceptNavigation(intent: android.content.Intent) {
        openAvmDestination = if (intent.getBooleanExtra("cloudSetup", false)) "cloud" else
            intent.getStringExtra(com.dante.zeekrbridge.OpenAvmHost.DESTINATION)?.takeIf { it in setOf("media","connection") }
        ++navigationRequest
    }
}

@Composable
fun CheckApp(model: CheckViewModel = viewModel(), setupOnly: Boolean = false) {
    val state by model.state.collectAsStateWithLifecycle()
    val climate by model.climateState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var details by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(Instant.now()) }
    val debugUsb = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, model) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) model.onBackground() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // Explicit whitelist only: the USB helper never reads email/password, tokens, VIN or raw responses.
    LaunchedEffect(state, climate) {
        if (debugUsb) withContext(Dispatchers.IO) {
            val diagnostic = buildJsonObject {
                put("schema", "zeekr-usb-diagnostic/1"); put("toolVersion", BuildConfig.VERSION_NAME)
                put("configReady", state.configReady); put("connected", state.connected); put("busy", state.busy)
                put("configSaved", state.configSaved)
                put("session", model.sessionDiagnostic())
                put("stage", state.stage); put("message", state.message ?: ""); put("vehicleCount", state.vehicles.size)
                put("climate", model.climateExport())
                state.report?.let { put("report", Json.parseToJsonElement(it.export(Instant.now(), model.climateExport()))) }
            }.toString()
            try {
                val temporary = File(context.filesDir, "diagnostic-state.tmp")
                temporary.writeText(diagnostic, Charsets.UTF_8)
                temporary.renameTo(File(context.filesDir, "diagnostic-state.json"))
            } catch (_: Exception) { /* Diagnostic failure must not interrupt the user's session. */ }
        }
    }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = Instant.now() } }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        val bytes = ByteArrayOutputStream()
                        val buffer = ByteArray(4096)
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            require(bytes.size() + count <= 65_536)
                            bytes.write(buffer, 0, count)
                        }
                        ProtocolFile.utf8(bytes.toByteArray())
                    } ?: error("unreadable")
                }
                password = ""; email = ""
                model.importConfig(text)
            } catch (_: Exception) { model.fileError() }
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            val report = model.state.value.report ?: return@launch
            val success = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(report.export(Instant.now(), model.climateExport()).toByteArray(Charsets.UTF_8)) }
                        ?: return@withContext false
                    true
                } catch (_: Exception) { false }
            }
            model.exportResult(success)
        }
    }
    MaterialTheme(colorScheme = lightColorScheme(primary = Forest, background = Paper, surface = Color.White,
        onSurface = Ink, onBackground = Ink, secondary = Forest, surfaceVariant = Color(0xFFE5EBE3))) {
        Scaffold(containerColor = Paper, bottomBar = {
            if (state.report?.demo == true) {
                Surface(color = Color(0xFFFFEDC1), modifier = Modifier.fillMaxWidth().testTag("persistent_demo_label")) {
                    UiText("示例模式 · 未连接你的车", color = Amber, style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.navigationBarsPadding().padding(horizontal = 18.dp, vertical = 8.dp))
                }
            }
        }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding().testTag("check_list"),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    UiText(if (setupOnly) "OPENAVM" else "OPENAVM / DIAGNOSTICS", color = Forest, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    UiText(if (setupOnly) "极氪云端（可选）" else "本车能力检查", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 5.dp))
                    UiText("澳洲原厂 App 1.6.6 · guest 共享账号", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 5.dp))
                    UiText("手机直连极氪云端 · 不需要私人服务器", style = MaterialTheme.typography.bodySmall, color = Forest, modifier = Modifier.padding(top = 5.dp))
                }
                if (state.report?.demo == true) item {
                    Surface(color = Color(0xFFFFEDC1), shape = MaterialTheme.shapes.medium, modifier = Modifier.testTag("demo_banner")) {
                        UiText("示例数据 · 未连接你的车\n用于预览报告，不能作为本车权限验证。", color = Amber,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(14.dp))
                    }
                }
                item {
                    Panel {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            UiText("01  连接准备", fontWeight = FontWeight.Bold)
                            BadgeText(if (state.configReady) "已导入" else if (state.busy) "准备中" else "尚未配置", Forest)
                        }
                        UiText("使用云端车况和控制前，请导入你自行准备且有权使用的连接配置，再登录自己的极氪账号。OpenAVM 不内置或下载厂商连接参数。", style = MaterialTheme.typography.bodySmall)
                        UiText("未配置不影响车机配对、录像传输、播放和导出。", style = MaterialTheme.typography.bodySmall)
                        UiText("连接配置和登录状态加密保存在本机，重开 App 自动恢复。密码不会保存，服务端让会话失效时仍可能需要重新登录。", style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { import.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                            enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("import_config")) { UiText(if (state.configReady) "更换连接配置" else "导入连接配置") }
                        if (debugUsb && !setupOnly) UiText("USB 测试版会在本应用私有目录保存已脱敏的连接进度和报告，供电脑核对；不包含登录凭据、VIN 或精确位置。", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { details = !details }, modifier = Modifier.testTag("config_help")) {
                            UiText(if (details) "收起说明" else "配置文件说明")
                        }
                        if (details) {
                            UiText("选择不超过 64 KB 的 JSON 文件，只接受六个文本字段；不要导入账号令牌或账号导出文件。格式通过不代表来源或兼容性已验证。", style = MaterialTheme.typography.bodySmall)
                            UiText(ProtocolFile.fields.joinToString("\n"), raw = true, style = MaterialTheme.typography.bodySmall)
                            UiText("当前接入适配澳洲原厂 App 1.6.6。配置需与你使用的版本匹配，车辆权限以你的账号实际授权为准。", style = MaterialTheme.typography.bodySmall)
                            UiText("连接车辆需要网络。家的位置、蓝牙触发和后台自动化会在使用对应功能时申请权限。文件由系统选择器授权。", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { password = ""; email = ""; model.forgetConfig() }, enabled = !state.busy,
                                modifier = Modifier.testTag("forget_config")) { UiText("移除云端配置并退出登录") }
                        }
                    }
                }
                if (state.configReady) item {
                    Panel {
                        UiText("02  账号与车辆", fontWeight = FontWeight.Bold)
                        if (state.sessionSaved) BadgeText("保持登录 · 本机加密", Forest)
                        if (!state.connected && state.sessionSaved) {
                            UiText("已保存登录状态。连接失败时可以重试；切换账号请先退出。", style = MaterialTheme.typography.bodySmall)
                            Button(onClick = model::reconnect, enabled = !state.busy,
                                modifier = Modifier.fillMaxWidth().testTag("reconnect")) { UiText("重新连接") }
                        } else if (!state.connected) {
                            UiText("使用获得主账号授权的 guest 账号。可用功能以车辆实际返回为准。", style = MaterialTheme.typography.bodySmall)
                            OutlinedTextField(value = email, onValueChange = { email = it }, label = { UiText("guest 邮箱") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), singleLine = true,
                                enabled = state.configReady && !state.busy, modifier = Modifier.fillMaxWidth().testTag("email"))
                            OutlinedTextField(value = password, onValueChange = { password = it }, label = { UiText("密码 · 不会保存") },
                                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                singleLine = true, enabled = state.configReady && !state.busy, modifier = Modifier.fillMaxWidth().testTag("password"))
                            Button(onClick = { keyboard?.hide(); model.login(email, password); password = "" },
                                enabled = state.configReady && !state.busy && email.isNotBlank() && password.isNotBlank(),
                                modifier = Modifier.fillMaxWidth().testTag("login")) { UiText("登录并读取共享车辆") }
                            UiText("登录后自动保持登录，无需每次输入密码。", style = MaterialTheme.typography.bodySmall)
                        } else {
                            BadgeText("车辆服务已连接", Forest)
                            state.vehicles.forEachIndexed { index, vehicle ->
                                OutlinedButton(onClick = { model.selectVehicle(index) }, enabled = !state.busy && !climate.queue.active,
                                    modifier = Modifier.fillMaxWidth().testTag("vehicle_$index")) {
                                    UiText((if (state.selected == index) "✓  " else "") + vehicle.label)
                                }
                            }
                            if (!setupOnly) Button(onClick = model::checkVehicle, enabled = !state.busy && !climate.queue.active && state.vehicles.isNotEmpty(),
                                modifier = Modifier.fillMaxWidth().testTag("start_check")) { UiText(if (state.report == null) "开始本车只读检查" else "重新检查") }
                        }
                        if (!setupOnly) UiText("检查读取车况和现有计划，不发送空调、哨兵、门锁或预约设置动作。读取是否唤醒车辆仍需本车核对。", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (!setupOnly && state.connected && state.vehicles.isNotEmpty()) item {
                    ClimatePanel(climate, state.busy, model)
                }
                item {
                    Panel {
                        UiText(state.stage, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("stage"))
                        if (state.busy) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            TextButton(onClick = model::stop, modifier = Modifier.testTag("stop")) { UiText("停止本轮检查") }
                        }
                        state.message?.let { UiText(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("message")) }
                        if (!state.busy) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                if (!setupOnly) TextButton(onClick = { password = ""; email = ""; model.demo() }, modifier = Modifier.testTag("demo")) { UiText("查看示例报告") }
                                if (state.sessionSaved || state.connected) TextButton(onClick = { password = ""; email = ""; model.reset() }, modifier = Modifier.testTag("clear_session")) { UiText("退出账号") }
                            }
                        }
                    }
                }
                if (!setupOnly) state.report?.let { report ->
                    item {
                        UiText("03  能力证据", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        UiText(report.vehicleLabel, style = MaterialTheme.typography.bodySmall)
                        UiText("读取、控制、实际结果和后台执行分别验证。", style = MaterialTheme.typography.bodySmall)
                    }
                    item {
                        val caps = report.capabilities
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("battery", "cabin_temperature", "sentry").forEach { id ->
                                val cap = caps.first { it.id == id }
                                Surface(shape = MaterialTheme.shapes.medium, modifier = Modifier.weight(1f)) {
                                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        UiText(cap.title, style = MaterialTheme.typography.labelMedium)
                                        UiText(cap.value, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                                        UiText(if (cap.read == ReadEvidence.FOUND) cap.age(now).label else cap.read.label,
                                            fontSize = 11.sp, color = if (cap.age(now) == AgeLabel.RECENT) Forest else Amber)
                                    }
                                }
                            }
                        }
                    }
                    item {
                        UiText("时间按手机时区显示。‘来源时间较近’仅表示接口提供的分组 updateTime 在 5 分钟内，仍需核对是否为车辆采样时间；刚刷新不等于新车况。", style = MaterialTheme.typography.bodySmall)
                    }
                    items(report.capabilities, key = { "cap_" + it.id }) { cap -> CapabilityRow(cap, report, now) }
                    item {
                        Panel {
                            UiText("控制与智能规则", fontWeight = FontWeight.Bold)
                            report.controls.forEach { control ->
                                UiText(control.title, fontWeight = FontWeight.SemiBold)
                                UiText(control.officialApp, style = MaterialTheme.typography.bodySmall)
                                UiText(control.thisApp, style = MaterialTheme.typography.bodySmall, color = Amber)
                                HorizontalDivider()
                            }
                            UiText("空调、座椅通风与加热可在车辆页操作。设定温度回读与座舱风量仍待验证。", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    item {
                        Panel {
                            UiText("诊断与下一步", fontWeight = FontWeight.Bold)
                            UiText("车辆列表授权相关字段：${report.roleFieldNames.joinToString().ifEmpty { "未取得可解释字段" }}。即使存在字段，也未据此扩大或推断权限。", style = MaterialTheme.typography.bodySmall)
                            report.probes.forEach { UiText("${it.endpoint.title} · ${if (it.attempted) "" else "未执行："}${it.outcome.label}", style = MaterialTheme.typography.bodySmall) }
                            UiText("导出包含检查结果和来源时间，不包含账号、密码、令牌、完整或部分 VIN、精确位置及原始响应。", style = MaterialTheme.typography.bodySmall)
                            Button(onClick = { export.launch(if (report.demo) "zeekr-capability-DEMO.json" else "zeekr-capability-report.json") },
                                enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("export_report")) { UiText("导出诊断报告") }
                        }
                    }
                }
                item { UiText("OpenAVM ${BuildConfig.VERSION_NAME}", color = Forest, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(bottom = 16.dp)) }
            }
        }
    }
}

@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp), content = content)
    }
}

@Composable
private fun BadgeText(text: String, color: Color) {
    UiText(text, style = MaterialTheme.typography.labelSmall, color = color,
        modifier = Modifier.background(color.copy(alpha = 0.09f), MaterialTheme.shapes.small).padding(horizontal = 8.dp, vertical = 5.dp))
}

@Composable
private fun CapabilityRow(cap: Capability, report: Report, now: Instant) {
    var expanded by remember(cap.id) { mutableStateOf(false) }
    val probe = report.probes.lastOrNull { it.endpoint == cap.endpoint }
    Surface(shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().testTag("cap_${cap.id}")) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    UiText(cap.title, fontWeight = FontWeight.SemiBold)
                    UiText(cap.value, style = MaterialTheme.typography.bodyMedium)
                }
                TextButton(onClick = { expanded = !expanded }) { UiText(if (expanded) "收起" else "证据") }
            }
            UiText("${cap.read.label} · ${cap.age(now).label}", color = if (cap.read == ReadEvidence.FOUND && cap.age(now) == AgeLabel.RECENT) Forest else Amber,
                style = MaterialTheme.typography.labelMedium)
            UiText("来源 ${displayTime(cap.sourceTime)}  /  获取 ${displayTime(probe?.takeIf { it.attempted }?.fetchedAt)}", style = MaterialTheme.typography.bodySmall)
            if (expanded) {
                UiText(cap.detail, style = MaterialTheme.typography.bodySmall)
                UiText("字段：${cap.sourcePath}", style = MaterialTheme.typography.bodySmall)
                UiText("控制：待验证  ·  结果确认：待验证  ·  后台：待验证", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
