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
                        statusText = "已导入 ${offer.fileName}（${offer.sizeBytes} B，" +
                            "${offer.wav.sampleRate} Hz/${offer.wav.channels}ch）"
                    },
                    onFailure = { t ->
                        statusText = "导入失败：${t.message ?: t.javaClass.simpleName}"
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
            title = "Zeekr USB 助手",
            status = "未开放（手机端）",
            description = "车机端“USB 助手”已可在车机实验室使用（SAF/USB OTG 授权、扫描、浏览、复制、清理原厂录像）。" +
                "手机端远程访问车机 USB 尚未开放，不会提供无响应的假按钮。",
        )
        ToolCard(
            title = "USB 健康检查",
            status = "未开放（车机端实验室可用）",
            description = "容量、可写状态、零字节文件、损坏视频与目录完整性检查保留在车机端实验室；" +
                "手机端远程健康检查尚未开放。",
        )
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(t("Sound maker (local audio editor)", "声音制作器（本地音频编辑）"), fontWeight = FontWeight.SemiBold)
                    Text(
                        "实验性",
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "导入 MP3/M4A/AAC/WAV/FLAC/OGG，波形裁切、试听、音量、标准化、淡入淡出、Mono/Stereo，" +
                        "输出 44.1 kHz / 16-bit PCM WAV，可保存到手机或 SAF 安全写入 USB。" +
                        "整个处理只在手机本地完成。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { showEditor = true }) { Text(t("Open sound maker", "打开声音制作器")) }
                }
                Text(
                    "实验性说明：App 可以生成符合参数的 WAV，但无法保证当前 Zeekr 固件一定识别该文件、文件名或音频长度。",
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
                        "实验性",
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "保留原有能力：选择手机上的 WAV（44.1/48 kHz、16-bit PCM、≤1 MiB），校验后通过局域网广播给车机。",
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
                        "暂无已导入的声音。",
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
                            statusText = "已向 ${BridgeServer.connectedCars()} 台车机广播 ${offer.fileName}"
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
