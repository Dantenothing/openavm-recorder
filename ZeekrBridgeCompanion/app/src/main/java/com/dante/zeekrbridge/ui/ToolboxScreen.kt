package com.dante.zeekrbridge.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dante.zeekrbridge.core.OutboundOfferStore
import com.dante.zeekrbridge.core.OutboundOffer
import com.dante.zeekrbridge.core.WsType
import com.dante.zeekrbridge.server.BridgeServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ToolboxScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var offers by remember { mutableStateOf<List<OutboundOffer>>(emptyList()) }
    var statusText by remember { mutableStateOf("") }
    var showEditor by remember { mutableStateOf(false) }

    if (showEditor) {
        SoundEditorScreen(onBack = { showEditor = false })
        return
    }

    fun reloadOffers() {
        scope.launch {
            offers = withContext(Dispatchers.IO) { OutboundOfferStore.offers() }
        }
    }

    val pickWavLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching { OutboundOfferStore.importUri(context, uri) }
                }
                result.fold(
                    onSuccess = { offer ->
                        statusText = t(
                            "Imported ${offer.fileName} (${offer.sizeBytes} B, ${offer.wav.sampleRate} Hz/${offer.wav.channels}ch)",
                            "已导入 ${offer.fileName}（${offer.sizeBytes} B，${offer.wav.sampleRate} Hz/${offer.wav.channels}ch）",
                        )
                    },
                    onFailure = { t ->
                        statusText = com.dante.zeekrbridge.ui.t(
                            "Import failed: ${t.message ?: t.javaClass.simpleName}",
                            "导入失败：${t.message ?: t.javaClass.simpleName}",
                        )
                    },
                )
                reloadOffers()
            }
        }
    }

    LaunchedEffect(Unit) { reloadOffers() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
    ) {
        Text(t("Toolbox", "工具箱"), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))

        ToolCard(
            title = t("Zeekr USB helper", "Zeekr USB 助手"),
            status = t("Available in the car Lab", "车机实验室可用"),
            description = t(
                "USB authorization, scanning, browsing, copying and factory-recording cleanup remain in the car Lab. Phone-side remote USB access will arrive with the authenticated control channel.",
                "USB 授权、扫描、浏览、复制和原厂录像清理保留在车机实验室。手机远程访问将在双向控制通道完成后开放。",
            ),
        )
        ToolCard(
            title = t("USB health check", "USB 健康检查"),
            status = t("Available in the car Lab", "车机实验室可用"),
            description = t(
                "Capacity, write access, empty files, damaged videos and directory integrity are checked locally in the car Lab.",
                "容量、可写状态、零字节文件、损坏视频和目录完整性检查保留在车机实验室。",
            ),
        )
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(t("Sound maker (local audio editor)", "声音制作器（本地音频编辑）"), fontWeight = FontWeight.SemiBold)
                    Text(
                        t("Experimental", "实验性"),
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    t(
                        "Import MP3, M4A, AAC, WAV, FLAC or OGG; trim by waveform, preview, adjust volume, normalize, fade and select Mono/Stereo. Export 44.1 kHz / 16-bit PCM WAV to the phone or an authorized USB folder. Processing stays on this phone.",
                        "导入 MP3/M4A/AAC/WAV/FLAC/OGG，支持波形裁切、试听、音量、标准化、淡入淡出和 Mono/Stereo；输出 44.1 kHz / 16-bit PCM WAV，可保存到手机或安全写入已授权 USB。全部处理仅在本机完成。",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { showEditor = true }) { Text(t("Open sound maker", "打开声音制作器")) }
                }
                Text(
                    t(
                        "Experimental: the app generates a standards-compliant WAV, but a specific Zeekr firmware may still reject its file name or duration.",
                        "实验性说明：App 可以生成符合参数的 WAV，但特定 Zeekr 固件仍可能不识别文件名或音频长度。",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(t("Car Lab transfer (≤1 MiB WAV)", "车机实验入口（≤1 MiB WAV 广播）"), fontWeight = FontWeight.SemiBold)
                    Text(
                        t("Experimental", "实验性"),
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    t(
                        "Choose a WAV (44.1/48 kHz, 16-bit PCM, no larger than 1 MiB), validate it, then offer it to a connected car over the local network.",
                        "选择 WAV（44.1/48 kHz、16-bit PCM、≤1 MiB），校验后通过局域网发送给已连接车机。",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        pickWavLauncher.launch(arrayOf("audio/wav", "audio/x-wav", "audio/*"))
                    }) { Text(t("Choose WAV", "选择 WAV 文件")) }
                    OutlinedButton(onClick = { reloadOffers() }) { Text(t("Refresh", "刷新")) }
                }
                if (statusText.isNotBlank()) {
                    Text(statusText, style = MaterialTheme.typography.bodySmall)
                }
                if (offers.isEmpty()) {
                    Text(
                        t("No imported sounds", "暂无已导入的声音。"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                offers.forEach { offer ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(offer.fileName, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${offer.sizeBytes} B | ${offer.wav.sampleRate} Hz | " +
                                    "${offer.wav.channels}ch | ${offer.wav.bitsPerSample}-bit | " +
                                    "SHA ${offer.sha256.take(12)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(onClick = {
                            BridgeServer.sendToCars(
                                WsType.FILE_OFFER,
                                OutboundOfferStore.metadataMap(offer),
                            )
                            statusText = t(
                                "Offered ${offer.fileName} to ${BridgeServer.connectedCars()} connected cars",
                                "已向 ${BridgeServer.connectedCars()} 台车机广播 ${offer.fileName}",
                            )
                        }) { Text(t("Send to car", "发送到车机")) }
                        OutlinedButton(onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    OutboundOfferStore.delete(offer.offerId)
                                }
                                reloadOffers()
                            }
                        }) { Text(t("Delete", "删除")) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolCard(
    title: String,
    status: String,
    description: String,
) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(
                    status,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
    }
}
