package com.dante.zeekrcapabilitylab.enhancement.field

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dante.zeekrcapabilitylab.BuildConfig
import com.dante.zeekrcapabilitylab.enhancement.probe.MultiCameraProbeActivity
import com.dante.zeekrcapabilitylab.ui.theme.ZeekrCapabilityLabTheme
import com.dante.zeekrcapabilitylab.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class FieldTestActivity : ComponentActivity() {
    private val exports by lazy { ViewModelProvider(this)[FieldTestExportModel::class.java] }
    private var locationGranted by mutableStateOf(false)
    private var permissionNotice by mutableStateOf<String?>(null)
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        locationGranted = hasLocationPermission()
        permissionNotice = if (locationGranted)
            Utils.t("Location permission granted. Check the options and tap Start; nothing has started yet.", "位置权限已授予，请核对选项后点击开始；测试尚未开始。")
        else Utils.t("Location permission denied. Uncheck GPS to continue with vehicle signals only.", "位置权限未授予，可取消勾选 GPS，仅检查车辆信号。")
    }
    private val save = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) exports.cancelSave() else exports.saveTo(applicationContext, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.VEHICLE_FIELD_TEST_ENABLED) { finish(); return }
        locationGranted = hasLocationPermission()
        if (exports.pendingReport == null) exports.pendingReport = savedInstanceState?.getString(PENDING_EXPORT)
        setContent { ZeekrCapabilityLabTheme {
            val state by FieldTestRuntime.state.collectAsState()
            // The retained model owns export preparation, so an Activity recreation cannot lose its callback.
            LaunchedEffect(exports.readyAction) {
                when (exports.readyAction) {
                    FieldExportAction.COPY -> {
                        exports.readyAction = null
                        exports.pendingReport?.let { report ->
                            getSystemService(ClipboardManager::class.java)
                                .setPrimaryClip(ClipData.newPlainText("OpenAVM field reports", report))
                            exports.pendingReport = null
                            exports.notice = Utils.t("Combined JSON copied", "合并 JSON 已复制")
                        }
                    }
                    FieldExportAction.SAVE -> {
                        exports.readyAction = null
                        save.launch("OpenAVM-field-reports-${System.currentTimeMillis()}.json")
                    }
                    null -> Unit
                }
            }
            FieldTestScreen(state = state, locationGranted = locationGranted,
                notice = permissionNotice ?: exports.notice, exportBusy = exports.busy,
                cameraExperimentAvailable = BuildConfig.CAMERA_CONCURRENCY_PROBE_ENABLED,
                onRequestLocation = {
                    permissionNotice = null
                    locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }, onStart = { duration, coordinates, gps ->
                    permissionNotice = null
                    exports.notice = null
                    FieldTestRuntime.start(this, duration, coordinates, gps)
                }, onStop = { FieldTestRuntime.stop() }, onMark = { FieldTestRuntime.mark(it) },
                onBack = ::finish,
                onCopy = { permissionNotice = null; exports.prepare(applicationContext, FieldExportAction.COPY) },
                onSave = { permissionNotice = null; exports.prepare(applicationContext, FieldExportAction.SAVE) },
                onOpenCamera = { startActivity(Intent(this, MultiCameraProbeActivity::class.java)) })
        } }
    }

    override fun onResume() {
        super.onResume()
        if (BuildConfig.VEHICLE_FIELD_TEST_ENABLED) {
            locationGranted = hasLocationPermission()
            FieldTestRuntime.setVisible(true)
            FieldTestRuntime.loadSaved(applicationContext)
        }
    }

    override fun onPause() {
        if (BuildConfig.VEHICLE_FIELD_TEST_ENABLED) FieldTestRuntime.setVisible(false)
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (BuildConfig.VEHICLE_FIELD_TEST_ENABLED) outState.putString(PENDING_EXPORT, exports.pendingReport)
        super.onSaveInstanceState(outState)
    }

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private companion object { const val PENDING_EXPORT = "pendingFieldExport" }
}

internal enum class FieldExportAction { COPY, SAVE }

/** Holds only a requested JSON export; creating this model does not read vehicle or GPS data. */
class FieldTestExportModel : ViewModel() {
    internal var pendingReport: String? = null
    internal var readyAction by mutableStateOf<FieldExportAction?>(null)
    internal var busy by mutableStateOf(false)
    internal var notice by mutableStateOf<String?>(null)

    internal fun prepare(context: Context, action: FieldExportAction) {
        if (busy || pendingReport != null) return
        busy = true
        notice = null
        FieldTestRuntime.export(context.applicationContext) { report ->
            Handler(Looper.getMainLooper()).post {
                busy = false
                if (report == null) notice = Utils.t("No saved report is available to export.", "暂无可导出的已保存报告。")
                else { pendingReport = report; readyAction = action }
            }
        }
    }

    internal fun cancelSave() { pendingReport = null }

    internal fun saveTo(context: Context, uri: Uri) {
        val report = pendingReport
        pendingReport = null
        if (report == null) {
            notice = Utils.t("Report is unavailable. Tap Save JSON again.", "报告暂不可用，请再次点击保存 JSON。")
            return
        }
        busy = true
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { runCatching {
                requireNotNull(context.contentResolver.openOutputStream(uri, "wt"))
                    .use { it.write(report.toByteArray(Charsets.UTF_8)) }
            }.isSuccess }
            busy = false
            notice = if (saved) Utils.t("Combined JSON saved", "合并 JSON 已保存")
                else Utils.t("Could not save JSON. Tap Save JSON to try again.", "JSON 保存失败，请再次点击保存 JSON。")
        }
    }
}

/** Injectable screen for synthetic UI tests. All hardware work is supplied explicitly by the host. */
@Composable
internal fun FieldTestScreen(
    state: FieldTestUiState,
    locationGranted: Boolean,
    notice: String? = null,
    exportBusy: Boolean = false,
    cameraExperimentAvailable: Boolean = true,
    onRequestLocation: () -> Unit,
    onStart: (durationSeconds: Int, includeCoordinates: Boolean, locationRequested: Boolean) -> Unit,
    onStop: () -> Unit,
    onMark: (String) -> Unit,
    onBack: () -> Unit,
    onCopy: () -> Unit,
    onSave: () -> Unit,
    onOpenCamera: () -> Unit,
) {
    var duration by rememberSaveable { mutableIntStateOf(120) }
    // A new screen requires a fresh parked acknowledgement, including after Activity recreation.
    var parked by remember { mutableStateOf(false) }
    var gps by rememberSaveable { mutableStateOf(true) }
    var coordinates by rememberSaveable { mutableStateOf(false) }
    val editable = !state.active && !state.busy
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(Utils.t("Vehicle field check", "车辆实测检查"), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = onBack, modifier = Modifier.testTag("field-back")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    Text(Utils.t("Back", "返回"))
                }
            }
            Text(Utils.t("Start while parked; drive without using this screen; stop/export after parking.",
                "停车后开始；行驶时请勿操作此页面；再次停车后停止并导出。"), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("field-driving-guidance"))
            FieldStatus(state, onStop)
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(0.43f).fillMaxHeight().verticalScroll(rememberScrollState()).testTag("field-controls"),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(120, 1800).forEach { seconds ->
                            OutlinedButton(onClick = { duration = seconds; parked = false }, enabled = editable,
                                modifier = Modifier.weight(1f).testTag("field-duration-$seconds")) {
                                Text((if (duration == seconds) "✓ " else "") + if (seconds == 120)
                                    Utils.t("Quick · 2 min", "短测 · 2 分钟") else Utils.t("Road · 30 min", "路测 · 30 分钟"))
                            }
                        }
                    }
                    FieldCheck(parked, { parked = it }, editable, "field-parked",
                        Utils.t("I am parked and ready to start", "已停车，可以开始"))
                    FieldCheck(gps, { gps = it; if (!it) coordinates = false }, editable, "field-gps",
                        Utils.t("Check GPS alongside vehicle signals", "同时检查 GPS 和车辆信号"))
                    if (gps && !locationGranted) {
                        Text(Utils.t("Allow location for GPS, or uncheck GPS to check vehicle signals only.",
                            "检查 GPS 需要位置权限；也可取消勾选 GPS，仅检查车辆信号。"), style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = onRequestLocation, enabled = editable,
                            modifier = Modifier.fillMaxWidth().testTag("field-location-permission")) {
                            Text(Utils.t("Allow location for GPS", "允许 GPS 位置权限"))
                        }
                    }
                    FieldCheck(coordinates, { coordinates = it }, editable && gps, "field-coordinates",
                        Utils.t("Include coordinates in this report", "在本次报告中包含坐标"))
                    Text(Utils.t("Coordinates are excluded by default. GPS accuracy, provider and speed are still checked without coordinates in the report.",
                        "默认不导出坐标。即使报告不含坐标，仍会检查 GPS 精度、来源和速度。"), style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("field-privacy-note"))
                    Button(onClick = { onStart(duration, coordinates && gps, gps); parked = false },
                        enabled = editable && parked && (!gps || locationGranted), modifier = Modifier.fillMaxWidth().testTag("field-start")) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Text(Utils.t("Start manual check", "手动开始检查"))
                    }
                    Text(Utils.t("You can return to ordinary recording. Read-only observation continues until its time limit or screen-off.",
                        "可返回普通录像页面；只读观测会继续，直到到时或息屏。"), style = MaterialTheme.typography.bodySmall)
                    if (state.busy && !state.active) Text(Utils.t("Waiting for the previous SDK read to finish. Another check cannot start yet.",
                        "仍在等待上次 SDK 读取结束，暂不能开始新检查。"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider()
                    Text(Utils.t("Optional observation markers", "可选观测标记"), style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onMark("Parked check") }, enabled = state.active,
                            modifier = Modifier.weight(1f).testTag("field-marker-parked")) { Text(Utils.t("Parked check", "停车检查")) }
                        OutlinedButton(onClick = { onMark("Returned") }, enabled = state.active,
                            modifier = Modifier.weight(1f).testTag("field-marker-returned")) { Text(Utils.t("Returned", "已返回")) }
                    }
                    Text(Utils.t("Markers record your labels only; they do not prove the vehicle state.",
                        "标记仅记录您的标签，不能自动证明车辆状态。"), style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider()
                    Text(Utils.t("Saved reports · {0}", "已保存报告 · {0}", state.savedReports), style = MaterialTheme.typography.titleSmall)
                    Text(Utils.t("After stopping, copy the combined JSON: saved field/camera checks, recent recording-data counts and map environment. Recording-data counts contain no coordinates; older field checks may include locations you opted to save.",
                        "停止后复制合并 JSON：包含车辆／相机检查、最近录像数据统计和地图环境。录像统计不含坐标；旧车辆检查仍可能包含您之前选择保存的位置。"), style = MaterialTheme.typography.bodySmall)
                    Text(Utils.t("Recording status comes from the recorder. This check does not verify saved videos or playback.",
                        "录像状态由录像器报告；此检查不验证已保存视频或回放。"), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onCopy, enabled = !state.active && state.reportAvailable && !exportBusy,
                            modifier = Modifier.weight(1f).testTag("field-copy")) { Text(Utils.t("Copy combined JSON", "复制合并 JSON")) }
                        OutlinedButton(onSave, enabled = !state.active && state.reportAvailable && !exportBusy,
                            modifier = Modifier.weight(1f).testTag("field-save")) { Text(Utils.t("Save JSON", "保存 JSON")) }
                    }
                    if (exportBusy) Text(Utils.t("Preparing or saving JSON…", "正在准备或保存 JSON…"), style = MaterialTheme.typography.bodySmall)
                    (notice ?: state.notice?.let(::fieldReasonLabel))?.let {
                        Text(it, modifier = Modifier.testTag("field-notice"), style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(onOpenCamera, enabled = editable && cameraExperimentAvailable,
                        modifier = Modifier.fillMaxWidth().testTag("field-camera")) { Text(Utils.t("Open separate camera preview test", "打开独立相机预览测试")) }
                    Text(Utils.t("The camera experiment needs its own manual start while parked.", "相机实验需在停车后另行手动开始。"), style = MaterialTheme.typography.bodySmall)
                }
                Column(Modifier.weight(0.57f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(Utils.t("Signal readings · {0}", "信号读数 · {0}", state.signals.size), style = MaterialTheme.typography.titleMedium)
                    Text(Utils.t("Current value · reading status · age · changes", "当前值 · 读取状态 · 距今 · 变化次数"), style = MaterialTheme.typography.bodySmall)
                    LazyColumn(Modifier.fillMaxSize().testTag("field-signals"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (state.signals.isEmpty()) item {
                            Text(Utils.t("No samples yet. A manual start is required to read signals.", "暂无采样，手动开始后才会读取信号。"), Modifier.padding(vertical = 16.dp))
                        }
                        items(state.signals, key = { it.key }) { signal -> FieldSignalRow(signal) }
                    }
                }
            }
        }
    }
}

@Composable
private fun FieldCheck(checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean, tag: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange, enabled = enabled, modifier = Modifier.testTag(tag))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun FieldStatus(state: FieldTestUiState, onStop: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("${fieldStatusLabel(state.phase)} · ${Utils.formatDuration(state.elapsedSeconds * 1000)} / ${Utils.formatDuration(state.durationSeconds * 1000L)}",
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("field-status"))
                Text(Utils.t("Valid / changed / all: {0} / {1} / {2}", "有效 / 有变化 / 全部：{0} / {1} / {2}",
                    state.validSignals, state.changedSignals, state.signals.size.takeIf { it > 0 } ?: state.sampledSignals),
                    modifier = Modifier.testTag("field-counts"))
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text(Utils.t("Vehicle speed: {0}", "车辆速度：{0}", fieldSpeed(state.vehicleSpeedKmh)), modifier = Modifier.testTag("field-vehicle-speed"))
                    Text(Utils.t("GPS speed: {0}", "GPS 速度：{0}", fieldSpeed(state.gpsSpeedKmh)), modifier = Modifier.testTag("field-gps-speed"))
                }
                Text(Utils.t("Vehicle SDK: {0} · GPS: {1}", "车辆 SDK：{0} · GPS：{1}", fieldStatusLabel(state.sdkStatus), fieldStatusLabel(state.gpsStatus)),
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("field-source-status"))
                if (';' in state.sdkStatus || ':' in state.sdkStatus) Text(state.sdkStatus,
                    style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (state.recorderRunning) Utils.t("Recorder reports: recording", "录像器报告：正在录像")
                    else Utils.t("Recorder reports: not recording", "录像器报告：未在录像"), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("field-recorder"))
                state.reason?.let { Text(fieldReasonLabel(it), style = MaterialTheme.typography.bodySmall) }
            }
            Button(onClick = onStop, enabled = state.active, modifier = Modifier.testTag("field-stop").heightIn(min = 56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)) {
                Icon(Icons.Default.Close, contentDescription = null)
                Text(Utils.t("Stop now", "立即停止"))
            }
        }
    }
}

@Composable
private fun FieldSignalRow(signal: FieldSignalUi) {
    Card(Modifier.fillMaxWidth().testTag("field-signal-${signal.key}")) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(Utils.t(signal.nameEn, signal.nameZh), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(fieldSignalValue(signal), fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("field-value-${signal.key}"))
            }
            Text(Utils.t("{0} · age {1} · changes {2}", "{0} · 距今 {1} · 变化 {2} 次", fieldStatusLabel(signal.status),
                signal.ageMs?.takeIf { it >= 0 }?.let { String.format(Locale.ROOT, "%.1f s", it / 1000.0) } ?: "—", signal.changes),
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun fieldSpeed(speed: Double?): String = speed?.takeIf { it.isFinite() && it >= 0 }
    ?.let { String.format(Locale.ROOT, "%.1f km/h", it) } ?: "—"

private fun fieldSignalValue(signal: FieldSignalUi): String {
    val sensitive = Regex("(^|[._-])(vin|latitude|longitude|coordinates?)([._-]|$)", RegexOption.IGNORE_CASE)
    if (sensitive.containsMatchIn(signal.key)) return Utils.t("Hidden", "已隐藏")
    return when (signal.value) {
        "on" -> Utils.t("On", "开启")
        "off" -> Utils.t("Off", "关闭")
        "closed" -> Utils.t("Closed", "已关闭")
        "open" -> Utils.t("Open", "已打开")
        "unfastened" -> Utils.t("Unfastened", "未系好")
        "fastened" -> Utils.t("Fastened", "已系好")
        "unoccupied" -> Utils.t("Unoccupied", "无人")
        "occupied" -> Utils.t("Occupied", "有人")
        "shown" -> Utils.t("Shown", "已显示")
        "hidden" -> Utils.t("Hidden", "已隐藏")
        "left" -> Utils.t("Left", "左")
        "right" -> Utils.t("Right", "右")
        "hazard" -> Utils.t("Hazard lights", "双闪")
        "normal" -> Utils.t("Normal", "正常位置")
        "tilted" -> Utils.t("Tilted", "已下翻")
        "returning" -> Utils.t("Returning", "正在回位")
        "tilting" -> Utils.t("Tilting", "正在下翻")
        "day" -> Utils.t("Day", "白天")
        "night" -> Utils.t("Night", "夜间")
        "accessory" -> Utils.t("Accessory", "附件电源")
        "driving" -> Utils.t("Driving", "行驶状态")
        "position" -> Utils.t("Position lights", "示宽灯")
        "low_beam" -> Utils.t("Low beam", "近光灯")
        "auto" -> Utils.t("Auto", "自动")
        "low" -> Utils.t("Low", "低")
        "medium" -> Utils.t("Medium", "中")
        "high" -> Utils.t("High", "高")
        else -> signal.value.ifBlank { "—" }
    }
}

private fun fieldStatusLabel(status: String): String = when (val code = status.substringBefore(';').substringBefore(':')) {
    "IDLE", "NOT_STARTED" -> Utils.t("Not started", "尚未开始")
    "STARTING" -> Utils.t("Starting", "正在开始")
    "RUNNING", "POLLING" -> Utils.t("Observing", "观测中")
    "STOPPING" -> Utils.t("Stopping", "正在停止")
    "FINISHED" -> Utils.t("Finished", "已结束")
    "NOT_REQUESTED" -> Utils.t("Not requested", "未启用")
    "GPS_TIMEOUT" -> Utils.t("No fresh GPS fix", "未收到新 GPS 定位")
    "NO_PERMISSION" -> Utils.t("Permission missing", "缺少权限")
    "LOCATION_OFF" -> Utils.t("Location is off", "系统位置已关闭")
    "NO_PROVIDER" -> Utils.t("No location provider", "无位置来源")
    "SUBSCRIBED", "WAITING" -> Utils.t("Waiting for readings", "等待读数")
    "UNAVAILABLE", "UNSUPPORTED", "SDK_UNAVAILABLE" -> Utils.t("Unavailable", "不可用")
    "GETTERS_READY" -> Utils.t("Read access ready", "读取接口已就绪")
    "NO_READABLE_GETTERS" -> Utils.t("No readable interfaces", "无可用读取接口")
    "GETTER_UNAVAILABLE", "MANAGER_GETTER_UNAVAILABLE", "MANAGER_RETURNED_NULL" -> Utils.t("Read interface unavailable", "读取接口不可用")
    "VALID", "OK", "ACCEPTED" -> Utils.t("Valid reading", "有效读数")
    "STALE", "OLD" -> Utils.t("Stale reading", "读数已过期")
    "INVALID", "NON_FINITE", "NON_FINITE_CONVERSION", "NON_INTEGER_ENUM", "NON_NUMERIC_VALUE", "OUT_OF_RANGE" -> Utils.t("Invalid reading", "无效读数")
    "SENTINEL", "TINY_SENTINEL" -> Utils.t("Sentinel, not a reading", "哨兵值，并非有效读数")
    "UNKNOWN_ENUM" -> Utils.t("Unknown state code", "状态码未知")
    "RAW_ONLY", "PROVISIONAL_RAW" -> Utils.t("Raw reading · interpretation unverified", "原始读数 · 含义待验证")
    "MISSING", "NO_VALUE", "NOT_READ" -> Utils.t("No reading", "无读数")
    "SDK_TIMEOUT", "TIMEOUT" -> Utils.t("Read timed out", "读取超时")
    "ERROR" -> Utils.t("Read failed", "读取失败")
    else -> code.take(80)
}

private fun fieldReasonLabel(reason: String): String = when (reason) {
    "USER_STOP" -> Utils.t("Stopped manually", "已手动停止")
    "TIME_LIMIT" -> Utils.t("Time limit reached", "已达到时限")
    "SCREEN_OFF" -> Utils.t("Stopped when the screen turned off", "已在息屏时停止")
    "SERVICE_STOPPED" -> Utils.t("Observation service stopped", "观测服务已停止")
    "SDK_TIMEOUT" -> Utils.t("Vehicle SDK read timed out", "车辆 SDK 读取超时")
    "SDK_ERROR" -> Utils.t("Vehicle SDK read failed", "车辆 SDK 读取失败")
    "FOREGROUND_START_FAILED" -> Utils.t("Could not start the observation service", "无法启动观测服务")
    "STORAGE_ERROR" -> Utils.t("Report storage failed; check export availability", "报告存储失败，请检查能否导出")
    else -> Utils.t("Detail: {0}", "详情：{0}", reason)
}
