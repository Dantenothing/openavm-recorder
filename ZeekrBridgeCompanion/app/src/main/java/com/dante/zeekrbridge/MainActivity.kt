package com.dante.zeekrbridge

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.dante.zeekrbridge.core.PairingManager
import com.dante.zeekrbridge.core.FilePreviewRules
import com.dante.zeekrbridge.core.OutboundOfferStore
import com.dante.zeekrbridge.core.OutboundOffer
import com.dante.zeekrbridge.core.ReceivedStore
import com.dante.zeekrbridge.core.ServerLog
import com.dante.zeekrbridge.core.WsType
import com.dante.zeekrbridge.server.BridgeServer
import com.dante.zeekrbridge.server.BluetoothServer
import com.dante.zeekrbridge.service.BridgeService
import com.dante.zeekrbridge.ui.ProductMainScreen
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                ProductMainScreen()
            }
        }
    }
}

@Composable
internal fun LabBridgeMainScreen() {
    val context = LocalContext.current
    var tab by remember { mutableStateOf(0) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                val items = listOf(
                    Triple(0, Icons.Default.Home, "Service"),
                    Triple(1, Icons.Default.Star, "Discovery"),
                    Triple(2, Icons.Default.Person, "Devices"),
                    Triple(3, Icons.Default.List, "Files"),
                    Triple(4, Icons.Default.Info, "Logs"),
                )
                items.forEach { (index, icon, label) ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp),
        ) {
            when (tab) {
                0 -> ServiceScreen(context)
                1 -> DiscoveryScreen(context)
                2 -> DevicesScreen(context)
                3 -> FilesScreen(context)
                else -> LogsScreen(context)
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun KV(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, modifier = Modifier.width(180.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value)
    }
}

@Composable
private fun ServiceScreen(context: android.content.Context) {
    val state by BridgeServer.state.collectAsState()
    val btState by BluetoothServer.state.collectAsState()
    val running by BridgeService.running.collectAsState()
    val code by PairingManager.code.collectAsState()
    val expiresAt by PairingManager.codeExpiresAt.collectAsState()
    val now by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { BridgeService.start(context) }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("Zeekr Bridge Companion", style = MaterialTheme.typography.headlineSmall)
        Section("Server") {
            KV("Status", if (running) "RUNNING" else "STOPPED")
            KV("IP", state.ip.ifEmpty { "-" })
            KV("Port", state.port.toString())
            KV("Pairing code", code.ifEmpty { "-" })
            KV(
                "Code expires",
                if (expiresAt > 0) "${((expiresAt - now) / 1000)}s" else "-",
            )
            KV("Connected cars", state.connectedCars.toString())
            KV("Requests", state.requestCount.toString())
            KV("Last client", state.lastClientIp ?: "-")
            KV("Last upload", state.lastUpload ?: "-")
            KV("Storage used", "${BridgeServer.storageUsed() / 1024} KB")
            KV("Bluetooth server", if (btState.running) "LISTENING" else "STOPPED")
            KV("BT connected cars", btState.connectedCars.toString())
            KV("BT last client", btState.lastClient ?: "-")
            KV("Wake lock", if (com.dante.zeekrbridge.service.BridgePowerLocks.isWakeHeld()) "HELD" else "RELEASED")
            KV("Wi-Fi lock", if (com.dante.zeekrbridge.service.BridgePowerLocks.isWifiHeld()) "HELD" else "RELEASED")
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = {
                    val permissions = mutableListOf<String>()
                    if (Build.VERSION.SDK_INT >= 33 &&
                        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        permissions += Manifest.permission.POST_NOTIFICATIONS
                    }
                    if (Build.VERSION.SDK_INT >= 31 &&
                        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
                    ) {
                        permissions += Manifest.permission.BLUETOOTH_CONNECT
                    }
                    if (permissions.isEmpty()) BridgeService.start(context) else permissionLauncher.launch(permissions.toTypedArray())
                }) { Text("Start server") }
                OutlinedButton(onClick = { BridgeService.stop(context) }) { Text("Stop server") }
                OutlinedButton(onClick = { PairingManager.newPairingCode() }) { Text("New pairing code") }
            }
        }
        Text(
            "Security: cleartext HTTP/WebSocket LAN bridge with Bearer auth and no TLS. " +
                "Use only on a trusted hotspot/LAN, never public/untrusted Wi-Fi. Tokens are never logged.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Test: open http://<this-ip>:${state.port}/health in the phone browser while the server is running.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "While the bridge service is running it keeps network/CPU awake and increases battery use; " +
                "Stop fully releases all power locks.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun DiscoveryScreen(context: android.content.Context) {
    val state by BridgeServer.state.collectAsState()
    val logLines by ServerLog.lines.collectAsState()
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("Discovery Diagnostics", style = MaterialTheme.typography.headlineSmall)
        Section("Listeners") {
            KV("UDP listener", if (state.running) "LISTENING" else "STOPPED")
            KV("NSD registration", if (state.running) "REGISTERED (on start)" else "STOPPED")
            KV("Last discovery request", state.lastDiscoveryRequest?.let { java.util.Date(it).toString() } ?: "-")
            KV("Last discovery reply", state.lastDiscoveryReply?.let { java.util.Date(it).toString() } ?: "-")
            KV("Total requests", state.requestCount.toString())
        }
        Section("Recent server log") {
            logLines.takeLast(40).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun DevicesScreen(context: android.content.Context) {
    val devices by PairingManager.devices.collectAsState()
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("Paired Devices", style = MaterialTheme.typography.headlineSmall)
        if (devices.isEmpty()) {
            Text("No paired cars yet.")
        }
        devices.forEach { device ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(device.name, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${device.carDeviceId.take(12)}... | paired ${java.util.Date(device.pairedAt)} | last seen ${java.util.Date(device.lastSeen)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = { PairingManager.revoke(device.carDeviceId) }) { Text("Revoke") }
            }
        }
    }
}

@Composable
private fun FilesScreen(context: android.content.Context) {
    val files by ReceivedStore.files.collectAsState()
    val scope = rememberCoroutineScope()
    var viewFile by remember { mutableStateOf<File?>(null) }
    var playFile by remember { mutableStateOf<File?>(null) }
    var hashText by remember { mutableStateOf("") }
    var dlText by remember { mutableStateOf("") }
    var offers by remember { mutableStateOf<List<OutboundOffer>>(emptyList()) }
    var offerText by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        offers = withContext(Dispatchers.IO) { OutboundOfferStore.offers() }
    }
    val pickWavLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                var message = ""
                val updated = withContext(Dispatchers.IO) {
                    runCatching { OutboundOfferStore.importUri(context, uri) }
                        .fold(
                            onSuccess = { offer ->
                                message = "Imported ${offer.fileName} (${offer.sizeBytes} B, ${offer.wav.sampleRate} Hz/${offer.wav.channels}ch)"
                                OutboundOfferStore.offers()
                            },
                            onFailure = { t ->
                                message = "Rejected: ${t.message ?: t.javaClass.simpleName}"
                                OutboundOfferStore.offers()
                            },
                        )
                }
                offers = updated
                offerText = message
            }
        }
    }

    fun reloadOffers() {
        scope.launch {
            offers = withContext(Dispatchers.IO) { OutboundOfferStore.offers() }
        }
    }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("Received Files", style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { ReceivedStore.refresh() }) { Text("Refresh") }
            OutlinedButton(onClick = {
                val total = ReceivedStore.files.value.sumOf { it.length() }
                hashText = "Files: ${ReceivedStore.files.value.size}, total: ${total / 1024} KB"
            }) { Text("Summary") }
        }
        Text(hashText, style = MaterialTheme.typography.bodySmall)
        Text(dlText, style = MaterialTheme.typography.bodySmall)
        if (files.isEmpty()) {
            Text("No files received yet.")
        }
        files.forEach { file ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(file.name, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${file.length()} B | ${file.absolutePath}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val ext = file.extension.lowercase()
                    if (FilePreviewRules.isPreviewable(file.name)) {
                        OutlinedButton(onClick = { viewFile = file }) { Text("View") }
                    }
                    if (ext == "mp4") {
                        OutlinedButton(onClick = { playFile = file }) { Text("Play") }
                    }
                    OutlinedButton(onClick = {
                        scope.launch {
                            hashText = "Hashing ${file.name}..."
                            val sha = withContext(Dispatchers.IO) { ReceivedStore.sha256(file) }
                            hashText = "${file.name}: $sha"
                        }
                    }) { Text("Hash") }
                    OutlinedButton(onClick = { shareFile(context, file) }) { Text("Share") }
                    OutlinedButton(onClick = {
                        scope.launch {
                            dlText = "Saving ${file.name}..."
                            val ok = withContext(Dispatchers.IO) { saveToDownloads(context, file) }
                            dlText = if (ok) "Saved ${file.name}" else "Save failed ${file.name}"
                        }
                    }) { Text("DL") }
                    OutlinedButton(onClick = { ReceivedStore.moveToTrash(file) }) { Text("Delete") }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Outbound Sounds", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Wi-Fi first version; Bluetooth reverse transfer is not implemented yet. " +
                "1 MiB / 44.1/48 kHz 16-bit PCM rules are community experience and still need real-car validation.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { pickWavLauncher.launch(arrayOf("audio/wav", "audio/x-wav", "audio/*")) }) {
                Text("Pick WAV")
            }
            OutlinedButton(onClick = { reloadOffers() }) { Text("Refresh offers") }
        }
        Text(offerText, style = MaterialTheme.typography.bodySmall)
        if (offers.isEmpty()) {
            Text("No outbound sounds yet.")
        }
        offers.forEach { offer ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(offer.fileName, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${offer.sizeBytes} B | ${offer.wav.sampleRate} Hz | ${offer.wav.channels}ch | " +
                            "${offer.wav.bitsPerSample}-bit | ${offer.sha256.take(12)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    OutlinedButton(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                BridgeServer.sendToCars(WsType.FILE_OFFER, OutboundOfferStore.metadataMap(offer))
                            }
                            offerText = "Broadcast ${offer.fileName} (${BridgeServer.connectedCars()} cars connected)"
                        }
                    }) { Text("Broadcast") }
                    OutlinedButton(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { OutboundOfferStore.delete(offer.offerId) }
                            reloadOffers()
                        }
                    }) { Text("Delete") }
                }
            }
        }
    }

    val currentView = viewFile
    if (currentView != null) {
        var previewText by remember(currentView) { mutableStateOf<String?>(null) }
        LaunchedEffect(currentView) {
            previewText = null
            previewText = withContext(Dispatchers.IO) { FilePreviewRules.readPreviewOrNull(currentView) }
        }
        AlertDialog(
            onDismissRequest = { viewFile = null },
            title = { Text(currentView.name) },
            text = {
                Text(
                    previewText ?: "Loading preview...",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(onClick = { viewFile = null }) { Text("Close") }
            },
        )
    }
    if (playFile != null) {
        PlayDialog(file = playFile!!, onDismiss = { playFile = null })
    }
}

@Composable
private fun PlayDialog(file: File, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(file.name) },
        text = {
            var player by remember { mutableStateOf<MediaPlayer?>(null) }
            AndroidView(
                factory = { ctx ->
                    SurfaceView(ctx).also { sv ->
                        try {
                            val p = MediaPlayer().apply {
                                setDataSource(file.absolutePath)
                                prepare()
                                setDisplay(sv.holder)
                                start()
                            }
                            player = p
                        } catch (t: Throwable) {
                            player = null
                        }
                    }
                },
                modifier = Modifier
                    .width(320.dp)
                    .height(200.dp),
            )
            Text("${file.name} | ${file.length()} B", style = MaterialTheme.typography.bodySmall)
            PlayerDisposer(player)
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        confirmButton = {},
    )
}

@Composable
private fun PlayerDisposer(player: android.media.MediaPlayer?) {
    DisposableEffect(player) {
        onDispose { player?.release() }
    }
}

private fun shareFile(context: android.content.Context, file: File): Boolean {
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = if (file.extension.lowercase() == "mp4") "video/mp4" else "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "Share ${file.name}").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (context.packageManager.resolveActivity(chooser, 0) != null) {
            context.startActivity(chooser)
            return true
        }
    } catch (t: Throwable) {
        ServerLog.log("SHARE_FAILED ${t.message}")
    }
    return false
}

private fun saveToDownloads(context: android.content.Context, file: File): Boolean {
    if (Build.VERSION.SDK_INT >= 29) {
        var uri: Uri? = null
        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, if (file.extension.lowercase() == "mp4") "video/mp4" else "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ZeekrBridge")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return false
            val out = context.contentResolver.openOutputStream(uri)
            if (out == null) {
                // No stream: never leave a half-inserted file or report success.
                context.contentResolver.delete(uri, null, null)
                return false
            }
            try {
                out.use { stream ->
                    file.inputStream().use { it.copyTo(out) }
                }
            } catch (t: Throwable) {
                context.contentResolver.delete(uri, null, null)
                ServerLog.log("SAVE_TO_DOWNLOADS_FAILED ${t.message}")
                return false
            }
            val done = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            context.contentResolver.update(uri, done, null, null)
            ServerLog.log("SAVED_TO_DOWNLOADS ${file.name}")
            return true
        } catch (t: Throwable) {
            ServerLog.log("SAVE_TO_DOWNLOADS_FAILED ${t.message}")
            uri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            return false
        }
    }
    return shareFile(context, file)
}

@Composable
private fun LogsScreen(context: android.content.Context) {
    val logLines by ServerLog.lines.collectAsState()
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("Server Log", style = MaterialTheme.typography.headlineSmall)
        OutlinedButton(onClick = { shareLog(context) }) { Text("Share log") }
        logLines.takeLast(300).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

private fun shareLog(context: android.content.Context) {
    try {
        val text = buildString {
            appendLine("=== Zeekr Bridge Companion log ===")
            appendLine("Time: ${java.util.Date()}")
            appendLine()
            appendLine("--- server log ---")
            appendLine(ServerLog.lines.value.joinToString("\n"))
            appendLine()
            appendLine("--- pairing ---")
            appendLine("code=${PairingManager.code.value} expires=${PairingManager.codeExpiresAt.value}")
            PairingManager.devices.value.forEach {
                appendLine("car=${it.carDeviceId} name=${it.name} paired=${it.pairedAt} lastSeen=${it.lastSeen}")
            }
            appendLine()
            appendLine("--- received files ---")
            ReceivedStore.files.value.forEach { appendLine("${it.absolutePath} ${it.length()}") }
            appendLine()
            appendLine("--- server state ---")
            val s = BridgeServer.state.value
            appendLine("running=${s.running} ip=${s.ip} port=${s.port} requests=${s.requestCount} lastClient=${s.lastClientIp}")
            val bt = BluetoothServer.state.value
            appendLine("btRunning=${bt.running} btCars=${bt.connectedCars} btLast=${bt.lastClient} btError=${bt.lastError}")
        }
        val file = File(context.filesDir, "bridge-log-export.txt")
        file.writeText(text)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "Share bridge log").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (context.packageManager.resolveActivity(chooser, 0) != null) {
            context.startActivity(chooser)
        }
    } catch (t: Throwable) {
        ServerLog.log("LOG_SHARE_FAILED ${t.message}")
    }
}
