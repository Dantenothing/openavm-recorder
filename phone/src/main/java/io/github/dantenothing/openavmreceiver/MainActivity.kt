package io.github.dantenothing.openavmreceiver

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File
import kotlin.math.max

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { ReceiverScreen() } }
    }
}

@Composable
private fun ReceiverScreen() {
    val context = LocalContext.current
    val running by ReceiverService.running.collectAsState()
    val server by ReceiverServer.state.collectAsState()
    val code by PairingStore.code.collectAsState()
    val expiresAt by PairingStore.expiresAt.collectAsState()
    val files by PhoneUploadStore.received.collectAsState()
    val now by produceState(System.currentTimeMillis()) {
        while (true) { value = System.currentTimeMillis(); kotlinx.coroutines.delay(1_000) }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        ReceiverService.start(context)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("AVM Receiver", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Receives recordings from OpenAVM Recorder over your trusted phone hotspot or private LAN.")

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (running) "Receiver running" else "Receiver stopped", fontWeight = FontWeight.SemiBold)
                Text("Address: ${server.addresses.joinToString().ifBlank { "Connect Wi-Fi or enable hotspot" }}:${io.github.dantenothing.avmtransfer.protocol.TransferProtocol.PORT}")
                Text("Pairing code: ${code.ifBlank { "—" }}")
                if (code.isNotBlank()) Text("Expires in ${max(0, (expiresAt - now) / 1000)} seconds")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else ReceiverService.start(context)
                    }) { Text("Start receiver") }
                    OutlinedButton(onClick = { ReceiverService.stop(context) }, enabled = running) { Text("Stop") }
                    OutlinedButton(onClick = { PairingStore.newCode() }, enabled = running) { Text("New code") }
                }
            }
        }

        Text("Received recordings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        if (files.isEmpty()) Text("No recordings received yet.")
        files.forEach { item -> ReceivedCard(item.file) }

        Card(Modifier.fillMaxWidth()) {
            Text(
                "Alpha security note: transfer uses authenticated cleartext HTTP. Use only a trusted phone hotspot or private LAN. Keep this receiver running while transferring; Android may show a persistent notification.",
                Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ReceivedCard(file: File) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(file.name, fontWeight = FontWeight.SemiBold)
            Text("${file.length() / (1024 * 1024)} MB", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                    context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                }) { Text("Play") }
                OutlinedButton(onClick = {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "video/mp4"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "Share recording"))
                }) { Text("Share") }
            }
        }
    }
}
