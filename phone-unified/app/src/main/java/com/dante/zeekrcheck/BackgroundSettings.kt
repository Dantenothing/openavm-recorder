package com.dante.zeekrcheck

import android.Manifest
import android.app.ActivityManager
import android.app.job.JobScheduler
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dante.zeekrcheck.core.*
import kotlinx.coroutines.launch
import java.time.Instant

data class BackgroundHealth(val notifications: Boolean, val batteryExempt: Boolean,
    val backgroundRestricted: Boolean, val dataStatus: Int, val scheduled: Boolean) {
    companion object {
        fun read(context: Context) = BackgroundHealth(NotificationManagerCompat.from(context).areNotificationsEnabled(),
            context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName),
            Build.VERSION.SDK_INT >= 28 && context.getSystemService(ActivityManager::class.java).isBackgroundRestricted,
            context.getSystemService(ConnectivityManager::class.java).restrictBackgroundStatus,
            context.getSystemService(JobScheduler::class.java).getPendingJob(AwayGuardService.PERIODIC_JOB) != null)
    }
}

@Composable internal fun SyncStatusCard(status: SyncStatus, working: Boolean, result: String?, onCheck: () -> Unit) {
    AssistantCard {
        UiText("最近一次实际检查", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        val now = Instant.now()
        UiText(if (status.started > 0) "${status.reason} · ${SyncPolicy.clockLabel(status.started, now)}" else "等待首次查询", fontSize = 13.sp)
        UiText(status.result, color = AssistantGreen, fontSize = 13.sp, modifier = Modifier.testTag("background_last_result"))
        UiText("最近查询成功：${SyncPolicy.clockLabel(status.success, now)}", fontSize = 12.sp, color = AssistantMuted)
        UiText("查询成功表示取得云端响应；各项车辆采样时间单独核对。", fontSize = 11.sp, color = AssistantMuted)
        Button(onClick = onCheck, enabled = !working, modifier = Modifier.fillMaxWidth().testTag("background_diagnostic")) {
            UiText(if (working) "正在只读检查…" else "运行只读自检")
        }
        result?.let { UiText(it, fontSize = 13.sp, modifier = Modifier.testTag("background_diagnostic_result")) }
    }
}

@Composable internal fun BackgroundSettingsScreen(model: CheckViewModel, onGuard: () -> Unit) {
    val context = LocalContext.current
    val settings by model.assistant.state.collectAsStateWithLifecycle()
    val overview by OverviewStore.get(context).state.collectAsStateWithLifecycle()
    val session by model.state.collectAsStateWithLifecycle()
    var health by remember { mutableStateOf(BackgroundHealth.read(context)) }
    var message by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var diagnostic by remember { mutableStateOf<String?>(null) }
    var devices by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) {
            AwayGuardService.schedule(context); health = BackgroundHealth.read(context)
        } }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) }
    }
    fun openSettings(intent: Intent) {
        runCatching { context.startActivity(intent) }.onFailure {
            runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
                .onFailure { message = "请在手机设置中打开 OpenAVM 的应用信息" }
        }
    }
    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { health = BackgroundHealth.read(context) }
    fun showDevices() {
        if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            message = "未允许附近设备；定期查询仍可使用"; return
        }
        runCatching {
            context.getSystemService(BluetoothManager::class.java).adapter?.bondedDevices.orEmpty()
                .map { it.address to (it.name?.take(80) ?: "已配对设备") }.sortedBy { it.second }
        }.onSuccess { devices = it }.onFailure { message = "无法读取已配对设备，请检查附近设备权限" }
    }
    val bluetoothPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) showDevices() else message = "未允许附近设备；定期查询仍可使用"
    }
    AssistantCard {
        UiText("桌面卡片自动更新", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            UiText("后台查询车况", fontSize = 15.sp)
            Switch(settings.widgetSyncEnabled, { enabled ->
                model.assistant.edit { it.copy(widgetSyncEnabled = enabled) }; AwayGuardService.schedule(context)
                health = BackgroundHealth.read(context)
            }, modifier = Modifier.testTag("background_sync_toggle"))
        }
        UiText("桌面上有卡片时，约每 15 分钟安排查询。多个卡片共用数据；系统省电可能延迟。", fontSize = 12.sp, color = AssistantMuted)
        UiText(if (health.scheduled) "系统周期任务：已安排" else "系统周期任务：未安排", color = AssistantGreen, fontSize = 13.sp)
        if (!AwayGuardService.hasWidgets(context)) UiText("尚未添加桌面卡片；启用离家守护或到家关闭后也会安排检查。", fontSize = 12.sp, color = AssistantMuted)
        FeatureRow("哨兵自动化 · 离家与到家", settings.guardMessage, onClick = onGuard)
    }
    AssistantCard {
        UiText("系统后台设置", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        FeatureRow("通知", if (health.notifications) "已允许" else "未允许 · 影响任务通知") {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else openSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }
        FeatureRow("电池与后台运行", when {
            health.backgroundRestricted -> "系统限制后台运行 · 建议调整"
            health.batteryExempt -> "已豁免电池优化"
            else -> "仍受电池优化 · 可调整为不限制"
        }) { openSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        FeatureRow("后台联网", when (health.dataStatus) {
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED -> "流量节省正在限制后台数据"
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED -> "已允许不受流量节省限制"
            else -> "流量节省未限制"
        }) { openSettings(Intent(Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
        UiText("返回此页面会重新检查实际设置。强行停止或手机离线期间无法保证自动执行；再次打开应用会恢复调度。", fontSize = 11.sp, color = AssistantMuted)
    }
    AssistantCard {
        UiText("下车后尽快检查", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        UiText(if (settings.carBluetoothAddress.isBlank()) "可选：绑定已经配对的车载蓝牙" else "已绑定：${settings.carBluetoothName}", fontSize = 13.sp)
        UiText("断连后安排一次检查；以车辆位置和停车记录决定守护。无需持续读取手机位置。", fontSize = 12.sp, color = AssistantMuted)
        OutlinedButton(onClick = {
            if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
            else showDevices()
        }) { UiText("选择车载蓝牙") }
        if (settings.carBluetoothAddress.isNotBlank()) TextButton(onClick = { model.assistant.edit { it.copy(carBluetoothAddress = "", carBluetoothName = "") } }) { UiText("移除蓝牙触发") }
        UiText("解锁手机时也会尽量补查；系统回收应用后仍依靠定期任务。", fontSize = 11.sp, color = AssistantMuted)
    }
    SyncStatusCard(overview.sync, checking || overview.refreshing(), diagnostic) {
        scope.launch {
            checking = true
            try {
                val report = model.syncOverview(SyncReason.DIAGNOSTIC, interactive = true)
                val decision = model.checkAwayGuard(report, dryRun = true)
                diagnostic = "只读自检：${decision.reason}。本次没有发送车控。"
            } finally { checking = false; health = BackgroundHealth.read(context) }
        }
    }
    val parking = ParkingEvidence.from(session.report?.probes?.lastOrNull { it.endpoint == Endpoint.STATUS })
    ParkingStatusCard(parking, parkingClock(parking?.fetched))
    AssistantCard {
        UiText("连接与数据", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        UiText(if (session.connected) "当前连接可用 · 复用已保存的登录" else "连接待恢复", fontSize = 13.sp)
        listOf("cabin_temperature" to "车温", "vehicle_location" to "车辆位置", "lock" to "车锁", "sentry" to "哨兵").forEach { (id, title) ->
            UiText("$title：${overview.readings[id]?.timeLabel(Instant.now()) ?: "尚未读取"}", fontSize = 12.sp, color = AssistantMuted)
        }
        UiText("缺少采样时间时保留上次值；不会把查询时间替代车辆采样时间。", fontSize = 11.sp, color = AssistantMuted)
    }
    message?.let { UiText(it, fontSize = 12.sp) }
    devices?.let { choices -> AlertDialog(onDismissRequest = { devices = null }, title = { UiText("选择车载蓝牙") },
        text = { Column { if (choices.isEmpty()) UiText("尚无已配对设备，请先在手机蓝牙设置中配对车辆。")
            choices.take(20).forEach { (address, name) -> TextButton(onClick = {
                model.assistant.edit { it.copy(carBluetoothAddress = address, carBluetoothName = name) }; devices = null
            }) { UiText(name) } }
        } }, confirmButton = { TextButton(onClick = { devices = null }) { UiText("关闭") } }) }
}
