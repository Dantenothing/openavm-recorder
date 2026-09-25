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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dante.zeekrbridge.core.CarCatalogStore
import com.dante.zeekrbridge.R
import com.dante.zeekrbridge.core.PairedDevice
import com.dante.zeekrbridge.core.PairedVehicleSummary
import com.dante.zeekrbridge.core.PairingManager
import com.dante.zeekrbridge.core.ReceivedStore
import com.dante.zeekrbridge.core.VehicleConnectionStatus
import com.dante.zeekrbridge.core.VehicleHomePolicy
import com.dante.zeekrbridge.core.VehicleHomeSnapshot
import com.dante.zeekrbridge.core.VehicleIdentityPolicy
import com.dante.zeekrbridge.core.WsType
import com.dante.zeekrbridge.server.BridgeServer
import com.dante.zeekrbridge.server.BridgeServerState
import com.dante.zeekrbridge.service.BridgeService
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay

private enum class VehiclePage { OVERVIEW, CONNECTION_DETAILS }

@Composable
fun VehicleScreen(onOpenLibrary: () -> Unit, onOpenLab: () -> Unit, onDetailVisibilityChanged: (Boolean) -> Unit = {}) {
    val context = LocalContext.current
    val running by BridgeService.running.collectAsState()
    val serverState by BridgeServer.state.collectAsState()
    val devices by PairingManager.devices.collectAsState()
    val pairingCode by PairingManager.code.collectAsState()
    val pairingExpiresAt by PairingManager.codeExpiresAt.collectAsState()
    val pairingLockedOut by PairingManager.lockedOut.collectAsState()
    val catalogOnline by CarCatalogStore.online.collectAsState()
    val carStatus by CarCatalogStore.carStatus.collectAsState()
    val receivedFiles by ReceivedStore.files.collectAsState()
    val receivedVideos = receivedFiles.filter { it.extension.equals("mp4", ignoreCase = true) }

    var page by rememberSaveable { mutableStateOf(VehiclePage.OVERVIEW) }
    androidx.compose.runtime.DisposableEffect(page) {
        onDetailVisibilityChanged(page == VehiclePage.CONNECTION_DETAILS)
        onDispose { onDetailVisibilityChanged(false) }
    }
    androidx.activity.compose.BackHandler(page != VehiclePage.OVERVIEW) { page = VehiclePage.OVERVIEW }
    var statusText by remember { mutableStateOf("") }
    var hotspotStatus by remember { mutableStateOf("") }
    var autoStartServer by remember {
        mutableStateOf(
            context.getSharedPreferences(PRODUCT_SETTINGS, Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTO_START_SERVER, true),
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
    val home = VehicleHomePolicy.resolve(
        pairedVehicles = devices.filter { it.securityVersion == 2 && (serverState.identityFingerprint.isBlank() || it.phoneIdentityPin == serverState.identityFingerprint) }.map {
            PairedVehicleSummary(it.carDeviceId, VehicleIdentityPolicy.displayName(it.name), it.lastSeen)
        },
        carOnline = catalogOnline,
        serviceRunning = serverState.running,
        endpointCandidates = serverState.endpointCandidates.map { "${it.ipv4}:${serverState.port}" },
        receiverStartedAtEpochMs = serverState.startedAtEpochMs, nowEpochMs = now,
    )

    fun openHotspotSettings() {
        hotspotStatus = try {
            context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            t("Connect both devices to the same Wi-Fi, or enable this phone hotspot, then return.", "请连接同一 Wi-Fi 或开启手机热点，再返回。")
        } catch (_: Throwable) {
            try {
                context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                t("Wi-Fi settings opened. Find Hotspot or Tethering, then return here.", "已打开 Wi-Fi 设置，请找到个人热点或网络共享并开启。")
            } catch (_: Throwable) {
                t("Unable to open system network settings.", "无法打开系统网络设置。")
            }
        }
    }

    fun checkHotspot() {
        BridgeServer.refreshAddresses()
        hotspotStatus = try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val enabled = wifi.javaClass.getMethod("isWifiApEnabled").invoke(wifi) as Boolean
            if (enabled) t("Phone hotspot is on.", "手机热点已开启。") else t("Phone hotspot is off.", "手机热点未开启。")
        } catch (_: Throwable) {
            t("Android does not expose hotspot status on this phone. Check it in system settings.", "当前系统不允许 App 检测热点状态，请在系统设置中确认。")
        }
    }

    fun setAutoStart(enabled: Boolean) {
        autoStartServer = enabled
        context.getSharedPreferences(PRODUCT_SETTINGS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTO_START_SERVER, enabled).apply()
    }

    fun prepareReconnect() {
        if (!serverState.running) BridgeService.start(context)
        BridgeServer.refreshAddresses()
        statusText = t(
            "Checking the receiver. On the car, open Phone and choose Reconnect now on the same network.",
            "正在检查接收服务。请让两边连到同一网络，在车机「手机」页点立即重连。",
        )
    }

    LaunchedEffect(Unit) {
        if (autoStartServer && !serverState.running) BridgeService.start(context)
    }
    LaunchedEffect(running) {
        while (running) {
            BridgeServer.refreshAddresses()
            delay(3_000)
        }
    }
    LaunchedEffect(catalogOnline, running) {
        while (catalogOnline && running) {
            BridgeServer.sendToCars(WsType.CAR_STATUS, mapOf("request" to "1"))
            delay(5_000)
        }
    }
    LaunchedEffect(catalogOnline) {
        if (catalogOnline) {
            delay(800)
            BridgeServer.sendToCars(WsType.LIST_RECORDINGS, emptyMap())
        }
    }
    LaunchedEffect(receivedFiles.size, catalogOnline) {
        if (!REMOTE_CONTROLS_ENABLED) return@LaunchedEffect
        if (!catalogOnline) return@LaunchedEffect
        val prefs = context.getSharedPreferences(PRODUCT_SETTINGS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("delete_car_copy", false)) return@LaunchedEffect
        val names = receivedVideos.map { it.name }.toSet()
        val toSend = names - sentDeleteNames.value
        toSend.forEach { name ->
            BridgeServer.sendToCars(WsType.DELETE_RECORDING, mapOf("fileName" to name))
        }
        sentDeleteNames.value = sentDeleteNames.value + toSend
    }

    when (page) {
        VehiclePage.OVERVIEW -> VehicleOverview(
            home = home,
            serverState = serverState,
            carStatus = carStatus,
            receivedCount = receivedVideos.size,
            receivedBytes = receivedVideos.sumOf { it.length() },
            statusText = statusText,
            onConnectionDetails = { page = VehiclePage.CONNECTION_DETAILS },
            onReconnect = { prepareReconnect() },
            onOpenLibrary = onOpenLibrary,
        )
        VehiclePage.CONNECTION_DETAILS -> ConnectionDetails(
            home = home,
            serverState = serverState,
            devices = devices,
            pairingCode = pairingCode,
            pairingExpiresAt = pairingExpiresAt,
            pairingLockedOut = pairingLockedOut,
            now = now,
            autoStartServer = autoStartServer,
            hotspotStatus = hotspotStatus,
            statusText = statusText,
            carStatus = carStatus,
            onBack = { page = VehiclePage.OVERVIEW },
            onStartService = { BridgeService.start(context) },
            onStopService = { BridgeService.stop(context) },
            onRefreshAddresses = { BridgeServer.refreshAddresses() },
            onOpenHotspot = { openHotspotSettings() },
            onCheckHotspot = { checkHotspot() },
            onAutoStartChanged = { setAutoStart(it) },
            onGeneratePairingCode = {
                BridgeService.start(context, openPairingWindow = true)
            },
            onRevoke = { revokeDevice = it },
            onStartRecording = {
                BridgeServer.sendToCars(WsType.CAR_CONTROL, mapOf("action" to "START_RECORDING"))
                statusText = t("Start recording sent to car.", "已向车机发送开始录像。")
            },
            onStopRecording = {
                BridgeServer.sendToCars(WsType.CAR_CONTROL, mapOf("action" to "STOP_RECORDING"))
                statusText = t("Stop recording sent to car.", "已向车机发送停止录像。")
            },
            onBookmark = {
                BridgeServer.sendToCars(WsType.CAR_CONTROL, mapOf("action" to "BOOKMARK"))
                statusText = t("Save incident sent to car.", "已向车机发送保存事件录像。")
            },
            onRefreshRecordings = {
                BridgeServer.sendToCars(WsType.LIST_RECORDINGS, emptyMap())
                statusText = t("Recording list requested.", "已请求刷新车机录像列表。")
            },
            onSyncProtected = {
                BridgeServer.sendToCars(WsType.SYNC_PROTECTED, emptyMap())
                statusText = t("Protected-event sync requested.", "已请求同步保护事件。")
            },
            onOpenLab = onOpenLab,
        )
    }

    revokeDevice?.let { carId ->
        AlertDialog(
            onDismissRequest = { revokeDevice = null },
            title = { Text(t("Unpair vehicle?", "解除车辆配对？")) },
            text = { Text(t("The car will need a new pairing code before it can reconnect.", "解除后，车机需要使用新的配对码才能再次连接。")) },
            confirmButton = {
                TextButton(onClick = {
                    PairingManager.revoke(carId)
                    revokeDevice = null
                }) { Text(t("Unpair", "解除配对")) }
            },
            dismissButton = { TextButton(onClick = { revokeDevice = null }) { Text(t("Cancel", "取消")) } },
        )
    }
}

@Composable
private fun VehicleOverview(
    home: VehicleHomeSnapshot,
    serverState: BridgeServerState,
    carStatus: Map<String, String>,
    receivedCount: Int,
    receivedBytes: Long,
    statusText: String,
    onConnectionDetails: () -> Unit,
    onReconnect: () -> Unit,
    onOpenLibrary: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 20.dp),
    ) {
        Text(t("Recorder connection", "车机连接"), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(t("Pair OpenAVM Recorder for local recordings. Your ZEEKR account connection is separate.", "这里连接 OpenAVM Recorder 传录像，与极氪账号的远程车控分开设置。"), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(18.dp))
        VehicleHeroCard(home, onConnectionDetails, onReconnect)
        serverState.startFailure?.let {
            Text(t("Receiver could not start. Stop another recorder receiver, then try Start again.",
                "接收未能启动，请关闭其他录像接收 App 的接收服务，再点启动重试。"), color = MaterialTheme.colorScheme.error)
        }

        if (REMOTE_CONTROLS_ENABLED && home.status == VehicleConnectionStatus.CONNECTED) {
            Spacer(Modifier.height(14.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Text(t("Recorder", "录像机"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    StatusLine(t("Status", "状态"), carStatus["status"] ?: t("Connected", "已连接"))
                    StatusLine(t("Source", "录像源"), carStatus["sourceRole"] ?: carStatus["cameraId"] ?: "—")
                    StatusLine(t("Segment", "当前分段"), carStatus["segmentNumber"] ?: "—")
                    if (serverState.lastUploadSpeedBps > 0) {
                        StatusLine(t("Recent transfer", "最近传输"), formatSpeed(serverState.lastUploadSpeedBps))
                    }
                    carStatus["lastError"]?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(t("Recorder needs attention: ", "录像机需要留意：") + it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(t("On this phone", "本机媒体"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Card(onClick = onOpenLibrary, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_openavm_library), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(t("Media library", "媒体库"), style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (receivedCount == 0) t("Received recordings will appear here.", "接收完成的录像会出现在这里。")
                        else t("{0} videos · {1}", "{0} 个视频 · {1}", receivedCount, formatBytes(receivedBytes)),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
            }
        }
        if (statusText.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(statusText, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun VehicleHeroCard(home: VehicleHomeSnapshot, onDetails: () -> Unit, onReconnect: () -> Unit) {
    val connected = home.status == VehicleConnectionStatus.CONNECTED
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (connected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_openavm_vehicle), null, tint = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Text(
                    home.vehicle?.name?.ifBlank { null }
                        ?: if (home.status == VehicleConnectionStatus.UNPAIRED) t("Pair your recorder", "配对车机录像") else "OpenAVM Recorder",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(18.dp))
            when (home.status) {
                VehicleConnectionStatus.CONNECTED, VehicleConnectionStatus.RECENT -> {
                    Text(if (connected) "● ${t("Connected", "已连接")}" else t("Recent car communication", "最近与车机通信正常"), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    Text(t("Last communication: {0}", "上次通信：{0}", formatLastSeen(home.vehicle?.lastSeenEpochMs)), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    home.preferredEndpoint?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(t("Phone address · {0}", "手机地址 · {0}", it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = onDetails, modifier = Modifier.align(Alignment.End).testTag("recorder_pair_details")) {
                        Text(t("Connection details", "连接详情"))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, null)
                    }
                }
                VehicleConnectionStatus.OFFLINE -> {
                    Text(if (home.receiverReady) t("Receiver ready · confirm on the car", "接收已就绪 · 请在车机确认连接")
                        else t("Paired · receiver stopped", "已配对 · 接收未启动"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        t("Last connected: {0}", "上次连接：{0}", formatLastSeen(home.vehicle?.lastSeenEpochMs)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onReconnect) { Text(if (home.receiverReady) t("Check connection", "检查连接") else t("Start receiving", "启动接收")) }
                        OutlinedButton(onClick = onDetails, modifier = Modifier.testTag("recorder_pair_details")) { Text(t("Details", "详情")) }
                    }
                }
                VehicleConnectionStatus.UNPAIRED -> {
                    Text(t("Pair with OpenAVM Recorder to receive recordings from your car.", "与 OpenAVM Recorder 配对后，即可接收车机录像。"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(14.dp))
                    Button(onClick = onDetails, modifier = Modifier.testTag("recorder_pair_details")) { Text(t("Set up connection", "开始连接")) }
                }
            }
        }
    }
}

@Composable
private fun ConnectionDetails(
    home: VehicleHomeSnapshot,
    serverState: BridgeServerState,
    devices: List<PairedDevice>,
    pairingCode: String,
    pairingExpiresAt: Long,
    pairingLockedOut: Boolean,
    now: Long,
    autoStartServer: Boolean,
    hotspotStatus: String,
    statusText: String,
    carStatus: Map<String, String>,
    onBack: () -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onRefreshAddresses: () -> Unit,
    onOpenHotspot: () -> Unit,
    onCheckHotspot: () -> Unit,
    onAutoStartChanged: (Boolean) -> Unit,
    onGeneratePairingCode: () -> Unit,
    onRevoke: (String) -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onBookmark: () -> Unit,
    onRefreshRecordings: () -> Unit,
    onSyncProtected: () -> Unit,
    onOpenLab: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, t("Back", "返回")) }
            Text(t("Connection details", "连接详情"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(10.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                Text(t("Receiver and connection", "接收与连接"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                StatusLine(t("Status", "状态"), when (home.status) {
                    VehicleConnectionStatus.CONNECTED -> t("Connected", "已连接")
                    VehicleConnectionStatus.RECENT -> t("Recent car communication", "最近与车机通信正常")
                    VehicleConnectionStatus.OFFLINE -> t("Paired · confirm on the car", "已配对 · 请在车机确认")
                    VehicleConnectionStatus.UNPAIRED -> t("Not paired", "未配对")
                })
                StatusLine(t("Method", "连接方式"), t("Phone hotspot / local network", "手机热点 / 局域网"))
                StatusLine(t("Receiver", "手机接收服务"), if (home.receiverReady) t("Running", "正在运行") else t("Stopped", "已停止"))
                StatusLine(t("Phone address", "手机地址"), home.preferredEndpoint ?: t("Connect Wi-Fi or enable hotspot first", "先连接 Wi-Fi 或开启热点"))
                serverState.startFailure?.let {
                    Text(t("Receiver failed to start. Stop another receiver and try again.", "接收启动失败，请关闭其他接收服务后重试。"), color = MaterialTheme.colorScheme.error)
                }
                serverState.endpointCandidates.drop(1).forEach { StatusLine(t("Alternative", "备用地址"), "${it.ipv4}:${serverState.port}") }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(t("Start when this page opens", "打开此页时启动接收"), modifier = Modifier.weight(1f))
                    Switch(checked = autoStartServer, onCheckedChange = onAutoStartChanged)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onStartService, enabled = !home.receiverReady, modifier = Modifier.testTag("recorder_start")) { Text(t("Start", "启动")) }
                    OutlinedButton(onClick = onStopService, enabled = home.receiverReady, modifier = Modifier.testTag("recorder_stop")) { Text(t("Stop", "停止")) }
                    OutlinedButton(onClick = onRefreshAddresses) { Text(t("Refresh", "刷新")) }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                Text(t("Vehicle pairing", "车辆配对"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(t("Update both apps for secure pairing. On the car Phone page, find this phone, compare the full fingerprint below, then enter the six-digit code. Reconnecting later is automatic.",
                    "请先将两端更新为安全版。在车机「手机」页找到本机，核对下面的完整指纹一致，再输入六位码。以后自动重连。"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (serverState.identityFingerprint.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(t("Phone identity · SHA-256", "本机身份 · SHA-256"), style = MaterialTheme.typography.labelMedium)
                    Text(com.dante.zeekrbridge.server.PhoneIdentityCertificate.displayFingerprint(serverState.identityFingerprint),
                        modifier = Modifier.testTag("recorder_security_fingerprint"),
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium)
                    Text(t("Check all 4 lines on both screens. If different, cancel on the car.",
                        "请逐行核对两边的全部 4 行；不一致就在车机取消。"),
                        style = MaterialTheme.typography.bodySmall)
                }
                if (pairingLockedOut) Text(t("Too many failed attempts. Open a new window when you are ready.",
                    "尝试次数过多，配对窗口已关闭。准备好后请重新打开。"), color = MaterialTheme.colorScheme.error)
                if (devices.any { it.securityVersion != 2 || (serverState.identityFingerprint.isNotBlank() && it.phoneIdentityPin != serverState.identityFingerprint) }) {
                    Text(t("An earlier pairing needs secure pairing again. Your recordings are kept.",
                        "旧配对需要重新进行一次安全配对，已有录像保留。"), color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(10.dp))
                if (home.receiverReady && pairingCode.isNotBlank() && pairingExpiresAt > now) {
                    Text(t("Pairing code", "配对码"), style = MaterialTheme.typography.labelMedium)
                    Text(pairingCode, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                    Text(
                        t("Expires in {0} seconds", "{0} 秒后失效", ((pairingExpiresAt - now) / 1_000).coerceAtLeast(0)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(t("Generate a new code when the car is ready.", "车机准备好后，请生成新的配对码。"))
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = onGeneratePairingCode, modifier = Modifier.testTag("recorder_pair_code")) { Text(t("Open secure pairing", "开启安全配对")) }
                if (home.receiverReady && pairingCode.isNotBlank() && pairingExpiresAt > now) {
                    TextButton(onClick = { PairingManager.closePairingWindow() }, modifier = Modifier.testTag("recorder_pair_close")) {
                        Text(t("Close pairing window", "关闭配对窗口"))
                    }
                }
                if (devices.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(10.dp))
                    Text(t("Paired vehicles", "已配对车辆"), style = MaterialTheme.typography.titleSmall)
                    devices.sortedByDescending { it.lastSeen }.forEach { device ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(VehicleIdentityPolicy.displayName(device.name))
                                Text(t("Last connected {0}", "上次连接 {0}", formatLastSeen(device.lastSeen)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { onRevoke(device.carDeviceId) }) { Text(t("Unpair", "解除")) }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                Text(t("Connection steps", "连接步骤"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                RecorderConnectionGuide()
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onOpenHotspot) { Text(t("Open settings", "打开设置")) }
                    OutlinedButton(onClick = onCheckHotspot) { Text(t("Check", "检测")) }
                }
                if (hotspotStatus.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(hotspotStatus, style = MaterialTheme.typography.bodySmall)
                }
            }
        }


        if (REMOTE_CONTROLS_ENABLED && home.status == VehicleConnectionStatus.CONNECTED) {
            Spacer(Modifier.height(12.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Text(t("Recorder controls", "录像机控制"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    StatusLine(t("Status", "状态"), carStatus["status"] ?: "—")
                    StatusLine(t("Profile", "画面参数"), carStatus["profile"] ?: "—")
                    StatusLine(t("Segment", "当前分段"), carStatus["segmentNumber"] ?: "—")
                    carStatus["storageFreeBytes"]?.toLongOrNull()?.let { StatusLine(t("Free storage", "剩余空间"), formatBytes(it)) }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onStartRecording) { Text(t("Start", "开始录像")) }
                        OutlinedButton(onClick = onStopRecording) { Text(t("Stop", "停止录像")) }
                    }
                    TextButton(onClick = onBookmark) { Text(t("Save incident clip", "保存事件录像")) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onRefreshRecordings) { Text(t("Refresh media", "刷新录像")) }
                        TextButton(onClick = onSyncProtected) { Text(t("Sync protected", "同步保护事件")) }
                    }
                    if (statusText.isNotBlank()) Text(statusText, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Card(onClick = onOpenLab, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t("Network diagnostics", "网络诊断"), style = MaterialTheme.typography.titleMedium)
                    Text(t("Cannot connect? Check the shared network, phone address and receiver here.", "连不上？先检查是否同网、手机地址和接收服务，再查看诊断。"), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, modifier = Modifier.width(116.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

private fun formatLastSeen(epochMs: Long?): String {
    if (epochMs == null || epochMs <= 0L) return t("Never", "从未")
    return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, PhoneLanguage.locale).format(Date(epochMs))
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

private fun formatSpeed(bytesPerSec: Long): String = when {
    bytesPerSec >= 1024L * 1024L -> String.format("%.1f MB/s", bytesPerSec / (1024.0 * 1024.0))
    bytesPerSec >= 1024L -> "${bytesPerSec / 1024} KB/s"
    else -> "$bytesPerSec B/s"
}

/** Car-side recording controls remain hidden until that separate channel is verified. */
private const val REMOTE_CONTROLS_ENABLED = false

private const val PRODUCT_SETTINGS = "phone_product_settings"
private const val KEY_AUTO_START_SERVER = "auto_start_server"
