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
    var showUsbSentry by remember { mutableStateOf(false) }

    if (showUsbSentry) {
        UsbSentryScreen(onBack = { showUsbSentry = false })
        return
    }

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

        Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Column(Modifier.padding(14.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(t("Zeekr Sentry USB", "Zeekr 哨兵 USB"), fontWeight = FontWeight.SemiBold)
                    Text(
                        t("Local USB", "手机直读"),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    t(
                        "Connect the vehicle USB to this phone to browse and play factory four-lane 360° Sentry recordings. Save the original or export a time range as Front, Rear, Left or Right.",
                        "将车辆 USB 连接到手机后，可浏览和播放原厂四路 360° 哨兵录像；支持保存原片，或按时间段导出前、后、左、右方向。",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { showUsbSentry = true }) { Text(t("Open Sentry USB", "打开哨兵 USB")) }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(t("Zeekr Lock / Unlock Sound Maker", "极氪解闭锁音效制作器"), fontWeight = FontWeight.SemiBold)
                    Text(
                        t("Zeekr 7X preset", "Zeekr 7X 预设"),
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    t(
                        "Import common audio, trim by waveform, adjust volume and fades, then create 48 kHz / 16-bit PCM WAV files for Zeekr 7X AU/NZ OS 2.1+ or Legacy 2.0. Generic WAV remains available.",
                        "导入常见音频，使用波形裁切、音量及淡入淡出后，生成适配 Zeekr 7X 澳洲/NZ OS 2.1+ 或 Legacy 2.0 的 48 kHz / 16-bit PCM WAV；也可选择通用 WAV。",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { showEditor = true }) { Text(t("Make lock / unlock sound", "制作解闭锁音效")) }
                }
                Text(
                    t(
                        "Recommended length: 5 seconds or less. Zeekr-compatible files must remain under 1 MB. The USB writer only manages the selected Zeekr sound folder.",
                        "建议长度不超过 5 秒；Zeekr 兼容文件必须小于 1 MB。USB 写入器只管理所选的 Zeekr 音效目录。",
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
