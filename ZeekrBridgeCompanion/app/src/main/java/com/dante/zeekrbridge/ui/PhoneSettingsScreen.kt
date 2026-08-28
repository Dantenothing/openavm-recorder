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
import com.dante.zeekrbridge.core.LocalMediaMaintenance
import com.dante.zeekrbridge.core.PairingManager
import com.dante.zeekrbridge.core.ReceivedStore
import com.dante.zeekrbridge.core.TrashStore
import com.dante.zeekrbridge.core.VehicleIdentityPolicy

@Composable
fun PhoneSettingsScreen(onOpenLab: () -> Unit) {
    val context = LocalContext.current
    val devices by PairingManager.devices.collectAsState()
    val received by ReceivedStore.files.collectAsState()
    val trashEntries by TrashStore.entries.collectAsState()
    val receivedVideos = received.filter { it.extension.equals("mp4", ignoreCase = true) }
    val prefs = remember { context.getSharedPreferences("phone_product_settings", Context.MODE_PRIVATE) }

    var autoCleanupLocal by remember { mutableStateOf(prefs.getBoolean("auto_cleanup_local", false)) }
    var revokeCar by remember { mutableStateOf<String?>(null) }
    var clearTrashConfirm by remember { mutableStateOf(false) }
    var showTrash by remember { mutableStateOf(false) }
    var versionTaps by remember { mutableStateOf(0) }
    var lastTapAt by remember { mutableStateOf(0L) }

    if (showTrash) {
        TrashScreen(onBack = { showTrash = false })
        return
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
                            Text(VehicleIdentityPolicy.displayName(device.name))
                            Text(
                                t(
                                    "Paired ${java.text.DateFormat.getDateInstance().format(java.util.Date(device.pairedAt))}",
                                    "配对于 ${java.text.DateFormat.getDateInstance().format(java.util.Date(device.pairedAt))}",
                                ),
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
                Text(t("Local media maintenance", "本机媒体维护"), style = MaterialTheme.typography.titleMedium)
                ToggleRow(t("Auto-clean local files after 30 days", "自动清理本地旧文件（30 天）"), autoCleanupLocal) {
                    autoCleanupLocal = it
                    prefs.edit().putBoolean("auto_cleanup_local", it).apply()
                    if (it) LocalMediaMaintenance.schedule(context)
                }
                Text(
                    t(
                        "Disabled by default. When enabled, unprotected recordings older than 30 days move to Trash; protected events and active transfers are never touched.",
                        "默认关闭。开启后，超过 30 天的未保护录像会移入回收站；保护事件和传输中的文件不会受影响。",
                    ),
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
                val total = receivedVideos.sumOf { it.length() }
                Text(
                    t(
                        "${receivedVideos.size} received videos · ${formatBytes(total)}",
                        "已接收录像 ${receivedVideos.size} 个，共 ${formatBytes(total)}",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showTrash = true }) {
                        Text(t("View trash (${trashEntries.size})", "查看回收站（${trashEntries.size}）"))
                    }
                    OutlinedButton(onClick = { clearTrashConfirm = true }, enabled = trashEntries.isNotEmpty()) {
                        Text(t("Empty trash", "清空回收站"))
                    }
                }
                if (autoCleanupLocal) {
                    Text(
                        t(
                            "Cleanup runs at most once per day when OpenAVM Companion starts.",
                            "OpenAVM Companion 启动时最多每天执行一次清理。",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text(t("Network / security / privacy / help", "网络 / 安全 / 隐私 / 帮助"), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    t(
                        "Pairing and file transfer stay on the same hotspot or trusted local network. Tokens are encrypted with Android Keystore on both devices.",
                        "所有配对与文件传输仅在同一热点或可信局域网内完成。车机与手机令牌均使用 Android Keystore 加密保存。",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    t(
                        "Help: enable the phone hotspot → connect the car → scan the pairing QR from Vehicle → the paired car can reconnect and transfer files.",
                        "帮助：开启手机热点 → 车机加入热点 → 在“车辆”页面扫描配对二维码 → 已配对车机即可重连并传输文件。",
                    ),
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
                    TrashStore.empty()
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

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
