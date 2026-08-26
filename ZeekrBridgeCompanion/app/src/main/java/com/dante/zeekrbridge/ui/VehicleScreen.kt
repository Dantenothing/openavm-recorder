package com.dante.zeekrbridge.ui

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.provider.Settings
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dante.zeekrbridge.core.CarCatalogStore
import com.dante.zeekrbridge.core.PairingManager
import com.dante.zeekrbridge.core.ReceivedStore
import com.dante.zeekrbridge.core.WsType
import com.dante.zeekrbridge.server.BridgeServer
import com.dante.zeekrbridge.service.BridgeService
import kotlinx.coroutines.delay

@Composable
fun VehicleScreen() {
    val context = LocalContext.current
    val running by BridgeService.running.collectAsState()
    val serverState by BridgeServer.state.collectAsState()
    val devices by PairingManager.devices.collectAsState()
    val pairingCode by PairingManager.code.collectAsState()
    val pairingExpiresAt by PairingManager.codeExpiresAt.collectAsState()
    val catalogOnline by CarCatalogStore.online.collectAsState()
    val carStatus by CarCatalogStore.carStatus.collectAsState()
    val catalogItems by CarCatalogStore.items.collectAsState()
    val lastMessage by CarCatalogStore.lastMessage.collectAsState()
    val receivedFiles by ReceivedStore.files.collectAsState()

    var statusText by remember { mutableStateOf("") }
    var hotpotStatus by remember { mutableStateOf("") }
    var autoStartServer by remember {
        mutableStateOf(
            context.getSharedPreferences("phone_product_settings", Context.MODE_PRIVATE)
                .getBoolean("auto_start_server", true),
        )
    }
    var revokeDevice by remember { mutableStateOf<String?>(null) }
    val sentDeleteNames = remember { mutableStateOf<Set<String>>(emptySet()) }

    val now by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
    }

    fun openHotspotSettings() {
        hotpotStatus = try {
            context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            "已打开无线网络设置，请进入“热点与网络共享/个人热点”开启热点后返回"
        } catch (t: Throwable) {
            try {
                context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                "已打开 Wi-Fi 设置，请在系统设置中找到“个人热点”并开启后返回"
            } catch (t2: Throwable) {
                "无法打开系统设置页面"
            }
        }
    }

    fun setAutoStart(enabled: Boolean) {
        autoStartServer = enabled
        context.getSharedPreferences("phone_product_settings", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("auto_start_server", enabled)
            .apply()
    }

    fun checkHotspot() {
        BridgeServer.refreshAddresses()
        hotpotStatus = try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val method = wifi.javaClass.getMethod("isWifiApEnabled")
            val enabled = method.invoke(wifi) as Boolean
            if (enabled) "热点已开启" else "热点未开启"
        } catch (t: Throwable) {
            "本系统不允许 App 检测热点状态，请在系统设置中确认已开启"
        }
    }

    LaunchedEffect(Unit) {
        if (autoStartServer && !running) {
            BridgeService.start(context)
        }
    }

    // Hotspot interfaces can appear after the service was started. Refreshing
    // keeps the manual fallback addresses accurate without restarting uploads.
    LaunchedEffect(running) {
        while (running) {
            BridgeServer.refreshAddresses()
            delay(3_000)
        }
    }

    // Poll car status while online.
    LaunchedEffect(catalogOnline, running) {
        while (catalogOnline && running) {
            BridgeServer.sendToCars(
                WsType.CAR_STATUS,
                mapOf("request" to "1"),
            )
            delay(5000)
        }
    }

    // Keep catalog fresh once online.
    LaunchedEffect(catalogOnline) {
        if (catalogOnline) {
            delay(800)
            BridgeServer.sendToCars(WsType.LIST_RECORDINGS, emptyMap())
        }
    }

    // “下载后删除车机副本”：新接收到的文件自动通知车机删除（仅未保护、未传输文件可被删除）。
    LaunchedEffect(receivedFiles.size, catalogOnline) {
        if (!catalogOnline) return@LaunchedEffect
        val prefs = context.getSharedPreferences("phone_product_settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("delete_car_copy", false)) return@LaunchedEffect
        val names = receivedFiles.map { it.name }.toSet()
        val toSend = names - sentDeleteNames.value
        if (toSend.isNotEmpty()) {
            toSend.forEach { name ->
                BridgeServer.sendToCars(
                    WsType.DELETE_RECORDING,
                    mapOf("fileName" to name),
                )
            }
            sentDeleteNames.value = sentDeleteNames.value + toSend
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
    ) {
        Text(t("Vehicle", "车辆"), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))

        // 局域网服务
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text(t("Local network service", "局域网服务"), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (running) "运行中 · 已连接车机 ${serverState.connectedCars}" else "未运行（配对和传输均需要本服务）",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (running) {
                    Spacer(Modifier.height(6.dp))
                    if (serverState.endpointCandidates.isEmpty()) {
                        Text(
                            "尚未检测到热点/局域网地址，请先开启热点后点“刷新地址”",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        serverState.endpointCandidates.forEachIndexed { index, endpoint ->
                            StatusLine(
                                if (index == 0) "首选地址" else "备用地址",
                                "${endpoint.ipv4}:${serverState.port} (${endpoint.interfaceName})",
                            )
                        }
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(onClick = { BridgeService.start(context) }) { Text(t("Start service", "启动服务")) }
                    OutlinedButton(onClick = { BridgeService.stop(context) }) { Text(t("Stop service", "停止服务")) }
                    OutlinedButton(onClick = { BridgeServer.refreshAddresses() }) { Text(t("Refresh address", "刷新地址")) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = autoStartServer, onCheckedChange = { setAutoStart(it) })
                        Text(t("Starts automatically on this page", "进入本页自动启动"), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(
                    "服务仅监听当前热点/局域网，传输不消耗蜂窝数据，也不访问公网。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // 热点引导
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text(t("Phone hotspot", "手机热点"), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "1. 开启手机热点（系统不支持 App 静默开启，需跳转系统设置）；\n" +
                        "2. 让车机加入该热点；\n" +
                        "3. 启动上方服务；\n" +
                        "4. 在车机“手机”页自动发现本手机，或输入本页 IP 与配对码。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { openHotspotSettings() }) { Text(t("Open hotspot settings", "打开系统热点设置")) }
                    OutlinedButton(onClick = { checkHotspot() }) { Text(t("Check hotspot", "检测热点状态")) }
                }
                if (hotpotStatus.isNotBlank()) {
                    Text(hotpotStatus, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // 配对：手机作为服务器，车机主动外连。
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text(t("Car pairing", "车机配对信息"), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "手机不再扫描车机二维码。请保持本页服务运行，由车机主动连接手机。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                if (running && pairingCode.isNotBlank() && pairingExpiresAt > now) {
                    Text(t("Pairing code", "配对码"), style = MaterialTheme.typography.labelMedium)
                    Text(pairingCode, style = MaterialTheme.typography.headlineLarge)
                    Text(
                        "剩余 ${((pairingExpiresAt - now) / 1_000).coerceAtLeast(0)} 秒 · 成功使用一次后立即失效",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        if (running) "当前没有有效配对码，请生成新配对码" else "请先启动手机服务",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    if (running) {
                        PairingManager.newPairingCode()
                    } else {
                        BridgeService.start(context)
                    }
                }) {
                    Text(if (running) "生成新配对码" else "启动并生成配对码")
                }
                Spacer(Modifier.height(12.dp))
                Text(t("Paired cars", "已配对车机"), style = MaterialTheme.typography.titleSmall)
                if (devices.isEmpty()) {
                    Text(t("No paired car yet. It will appear here after pairing.", "尚未配对。车机连接成功后会自动出现在这里。"))
                } else {
                    devices.forEach { device ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(device.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "最后连接 ${java.util.Date(device.lastSeen)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            OutlinedButton(onClick = { revokeDevice = device.carDeviceId }) {
                                Text(t("Unpair", "解除配对"))
                            }
                        }
                    }
                }
                if (statusText.isNotBlank()) {
                    Text(statusText, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // 车机状态与遥控
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    if (catalogOnline) "车机在线" else "车机离线",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (catalogOnline) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                if (!catalogOnline) {
                    Text(
                        "车机连上本热点后会出现在这里。自动重连由车机端完成。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    StatusLine("录像状态", carStatus["status"] ?: "未知")
                    StatusLine("画面/参数", carStatus["profile"] ?: "—")
                    StatusLine("分段", carStatus["segmentNumber"] ?: "—")
                    StatusLine("剩余空间", carStatus["storageFreeBytes"]?.toLongOrNull()?.let { formatBytes(it) } ?: "—")
                    carStatus["lastError"]?.takeIf { it.isNotBlank() }?.let {
                        Text(t("Latest error: ", "最近异常：") + it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            BridgeServer.sendToCars(
                                WsType.CAR_CONTROL,
                                mapOf("action" to "START_RECORDING"),
                            )
                            statusText = "已发送开始录像"
                        }) { Text(t("Start recording", "开始录像")) }
                        OutlinedButton(onClick = {
                            BridgeServer.sendToCars(
                                WsType.CAR_CONTROL,
                                mapOf("action" to "STOP_RECORDING"),
                            )
                            statusText = "已发送停止录像"
                        }) { Text(t("Stop recording", "停止录像")) }
                        OutlinedButton(onClick = {
                            BridgeServer.sendToCars(
                                WsType.CAR_CONTROL,
                                mapOf("action" to "BOOKMARK"),
                            )
                            statusText = "已发送保存事故片段"
                        }) { Text(t("Save incident", "保存事故片段")) }
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(onClick = {
                            BridgeServer.sendToCars(WsType.LIST_RECORDINGS, emptyMap())
                            statusText = "已请求录像列表"
                        }) { Text(t("Refresh recordings", "刷新录像列表")) }
                        OutlinedButton(onClick = {
                            BridgeServer.sendToCars(WsType.SYNC_PROTECTED, emptyMap())
                            statusText = "已请求同步保护事件"
                        }) { Text(t("Sync protected events", "同步保护事件")) }
                    }
                    if (lastMessage.isNotBlank()) {
                        Text(lastMessage, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "传输速率：${if (serverState.lastUploadSpeedBps > 0) formatSpeed(serverState.lastUploadSpeedBps) else "未测量"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (catalogItems.isNotEmpty()) {
                        Text(
                            "车机录像 ${catalogItems.size} 条（可在“媒体库”查看和下载）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (receivedFiles.isNotEmpty()) {
                        Text(
                            "已接收 ${receivedFiles.size} 个文件",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    revokeDevice?.let { carId ->
        AlertDialog(
            onDismissRequest = { revokeDevice = null },
            title = { Text(t("Unpair?", "解除配对？")) },
            text = { Text(t("The car will need the phone IP and a new pairing code.", "解除后，该车机需要重新输入手机 IP 与新配对码。")) },
            confirmButton = {
                TextButton(onClick = {
                    PairingManager.revoke(carId)
                    revokeDevice = null
                }) { Text(t("Unpair", "解除")) }
            },
            dismissButton = {
                TextButton(onClick = { revokeDevice = null }) { Text(t("Cancel", "取消")) }
            },
        )
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Text(
            label,
            modifier = Modifier.width(96.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    else -> "$bytes B"
}

private fun formatSpeed(bytesPerSec: Long): String = when {
    bytesPerSec >= 1024L * 1024L -> String.format("%.1f MB/s", bytesPerSec / (1024.0 * 1024.0))
    bytesPerSec >= 1024L -> "${bytesPerSec / 1024} KB/s"
    else -> "$bytesPerSec B/s"
}
