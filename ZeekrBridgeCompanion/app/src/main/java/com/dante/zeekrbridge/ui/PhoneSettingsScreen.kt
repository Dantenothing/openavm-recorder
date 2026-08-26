package com.dante.zeekrbridge.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dante.zeekrbridge.BuildConfig
import com.dante.zeekrbridge.core.CarCatalogStore
import com.dante.zeekrbridge.core.PairingManager
import com.dante.zeekrbridge.core.ReceivedStore
import com.dante.zeekrbridge.core.WsType
import com.dante.zeekrbridge.server.BridgeServer
import java.io.File

@Composable
fun PhoneSettingsScreen(onOpenLab: () -> Unit) {
    val context = LocalContext.current
    val devices by PairingManager.devices.collectAsState()
    val received by ReceivedStore.files.collectAsState()
    val online by CarCatalogStore.online.collectAsState()
    val prefs = remember { context.getSharedPreferences("phone_product_settings", Context.MODE_PRIVATE) }

    var autoSync by remember { mutableStateOf(prefs.getBoolean("auto_sync_protected", true)) }
    var deleteAfterDownload by remember { mutableStateOf(prefs.getBoolean("delete_car_copy", false)) }
    var autoCleanupLocal by remember { mutableStateOf(prefs.getBoolean("auto_cleanup_local", true)) }
    var retention by remember { mutableStateOf(prefs.getInt("car_retention_minutes", 30)) }
    var segment by remember { mutableStateOf(prefs.getInt("car_segment_seconds", 60)) }
    var storage by remember { mutableStateOf(prefs.getLong("car_storage_limit", 4L * 1024L * 1024L * 1024L)) }
    var safety by remember { mutableStateOf(prefs.getLong("car_min_free", 256L * 1024L * 1024L)) }
    var statusText by remember { mutableStateOf("") }
    var revokeCar by remember { mutableStateOf<String?>(null) }
    var clearTrashConfirm by remember { mutableStateOf(false) }
    var versionTaps by remember { mutableStateOf(0) }
    var lastTapAt by remember { mutableStateOf(0L) }

    fun save() {
        prefs.edit()
            .putBoolean("auto_sync_protected", autoSync)
            .putBoolean("delete_car_copy", deleteAfterDownload)
            .putBoolean("auto_cleanup_local", autoCleanupLocal)
            .putInt("car_retention_minutes", retention)
            .putInt("car_segment_seconds", segment)
            .putLong("car_storage_limit", storage)
            .putLong("car_min_free", safety)
            .apply()
    }

    fun sendCarConfig() {
        if (!online) {
            statusText = t("Car offline. Settings will apply when it comes online.", "车机离线，参数将在车机在线时生效")
            return
        }
        BridgeServer.sendToCars(
            WsType.SET_RECORDER_CONFIG,
            mapOf(
                "retentionMinutes" to retention.toString(),
                "segmentSeconds" to segment.toString(),
                "storageLimitBytes" to storage.toString(),
                "minFreeBytes" to safety.toString(),
                "autoCleanup" to autoCleanupLocal.toString(),
            ),
        )
        statusText = t("Recording settings sent to car", "已发送录像参数到车机")
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
    ) {
        Text(t("Settings", "设置"), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text(t("Language", "语言"), style = MaterialTheme.typography.titleMedium)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(
                        PhoneLanguageMode.SYSTEM to t("System", "跟随系统"),
                        PhoneLanguageMode.SIMPLIFIED_CHINESE to "简体中文",
                        PhoneLanguageMode.ENGLISH to "English",
                    ).forEach { (mode, label) ->
                        OutlinedButton(
                            onClick = { PhoneLanguage.selectMode(mode) },
                            enabled = PhoneLanguage.mode != mode,
                            modifier = Modifier.weight(1f),
                        ) { Text(label) }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text(t("Paired vehicles", "已配对车辆"), style = MaterialTheme.typography.titleMedium)
                if (devices.isEmpty()) {
                    Text(
                        t("No paired vehicles.", "暂无已配对车辆。"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                devices.forEach { device ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(device.name)
                            Text(
                                "配对于 ${java.util.Date(device.pairedAt)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(onClick = { revokeCar = device.carDeviceId }) {
                            Text(t("Unpair", "解除配对"))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text(t("Transfer and sync", "传输与同步"), style = MaterialTheme.typography.titleMedium)
                ToggleRow(t("Auto-sync protected events", "自动同步保护事件"), autoSync) {
                    autoSync = it
                    save()
                    if (it && online) BridgeServer.sendToCars(WsType.SYNC_PROTECTED, emptyMap())
                }
                ToggleRow(t("Delete car copy after download", "下载后删除车机副本"), deleteAfterDownload) {
                    deleteAfterDownload = it
                    save()
                }
                ToggleRow(t("Auto-clean local files after 30 days", "自动清理本地旧文件（30 天）"), autoCleanupLocal) {
                    autoCleanupLocal = it
                    save()
                }
                Text(
                    "保护事件与传输中的文件不会被自动清理；下载后删除车机副本仅作用于未保护文件。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text(t("Phone storage", "手机本地存储"), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                val total = received.sumOf { it.length() }
                Text(
                    "已接收文件 ${received.size} 个，共 ${formatBytes(total)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = { clearTrashConfirm = true }) {
                    Text(t("Empty trash", "清空回收站"))
                }
                if (autoCleanupLocal) {
                    Text(
                        "将在下次启动时清理 30 天前的未保护文件。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text(t("Car recording settings", "车机录像参数"), style = MaterialTheme.typography.titleMedium)
                OptionRowPhoneInt(
                    label = "循环保留",
                    options = listOf("15 分钟", "30 分钟", "60 分钟"),
                    values = listOf(15, 30, 60),
                    selected = retention,
                    onSelect = {
                        retention = it
                        save()
                    },
                )
                OptionRowPhoneInt(
                    label = "分段长度",
                    options = listOf("1 分钟", "2 分钟", "3 分钟"),
                    values = listOf(60, 120, 180),
                    selected = segment,
                    onSelect = {
                        segment = it
                        save()
                    },
                )
                OptionRowPhoneLong(
                    label = "最大存储",
                    options = listOf("2 GB", "4 GB", "8 GB"),
                    values = listOf(
                        2L * 1024L * 1024L * 1024L,
                        4L * 1024L * 1024L * 1024L,
                        8L * 1024L * 1024L * 1024L,
                    ),
                    selected = storage,
                    onSelect = {
                        storage = it
                        save()
                    },
                )
                OptionRowPhoneLong(
                    label = "安全线",
                    options = listOf("128 MB", "256 MB", "512 MB"),
                    values = listOf(
                        128L * 1024L * 1024L,
                        256L * 1024L * 1024L,
                        512L * 1024L * 1024L,
                    ),
                    selected = safety,
                    onSelect = {
                        safety = it
                        save()
                    },
                )
                OutlinedButton(onClick = { sendCarConfig() }) { Text(t("Sync to car", "同步到车机")) }
                if (statusText.isNotBlank()) {
                    Text(statusText, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text(t("Network / security / privacy / help", "网络 / 安全 / 隐私 / 帮助"), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "所有配对与文件传输仅在同一热点/局域网内完成，不依赖公网、不消耗蜂窝数据。" +
                        "车机令牌由车机端 Android Keystore 加密保存，手机端令牌由 Keystore 加密后写入本机私有存储。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "帮助：开启手机热点 → 车机加入热点 → 车机显示二维码 → 本页“车辆”扫码配对 → 自动重连与传输。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t("Version", "版本") + " ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = {
                        val nowMs = System.currentTimeMillis()
                        versionTaps = if (nowMs - lastTapAt < 3000) versionTaps + 1 else 1
                        lastTapAt = nowMs
                        if (versionTaps >= 5) {
                            versionTaps = 0
                            onOpenLab()
                        }
                    }) { Text(t("Version (tap 5 times for Lab)", "版本号（连续点击 5 次进入实验室）")) }
                }
            }
        }
    }

    revokeCar?.let { carId ->
        AlertDialog(
            onDismissRequest = { revokeCar = null },
            title = { Text(t("Unpair?", "解除配对？")) },
            text = { Text(t("The car must scan the pairing code again.", "解除后该车机需要重新扫码配对。")) },
            confirmButton = {
                TextButton(onClick = {
                    PairingManager.revoke(carId)
                    revokeCar = null
                }) { Text(t("Unpair", "解除")) }
            },
            dismissButton = {
                TextButton(onClick = { revokeCar = null }) { Text(t("Cancel", "取消")) }
            },
        )
    }

    if (clearTrashConfirm) {
        AlertDialog(
            onDismissRequest = { clearTrashConfirm = false },
            title = { Text(t("Empty trash?", "清空回收站？")) },
            text = { Text(t("Files in trash will be permanently deleted.", "回收站中的文件将被永久删除，无法恢复。")) },
            confirmButton = {
                TextButton(onClick = {
                    clearTrashConfirm = false
                    File(context.filesDir, "trash").deleteRecursively()
                }) { Text(t("Empty", "清空")) }
            },
            dismissButton = {
                TextButton(onClick = { clearTrashConfirm = false }) { Text(t("Cancel", "取消")) }
            },
        )
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun OptionRowPhoneInt(
    label: String,
    options: List<String>,
    values: List<Int>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    OptionRowPhoneGeneric(label, options, values, selected, onSelect)
}

@Composable
private fun OptionRowPhoneLong(
    label: String,
    options: List<String>,
    values: List<Long>,
    selected: Long,
    onSelect: (Long) -> Unit,
) {
    OptionRowPhoneGeneric(label, options, values, selected, onSelect)
}

@Composable
private fun <T> OptionRowPhoneGeneric(
    label: String,
    options: List<String>,
    values: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.width(110.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEachIndexed { index, option ->
                OutlinedButton(onClick = { onSelect(values[index]) }) {
                    Text(if (values[index] == selected) "$option ✓" else option)
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
