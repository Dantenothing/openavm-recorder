package com.dante.zeekrbridge.ui

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun ToolboxScreen() {
    var showEditor by remember { mutableStateOf(false) }
    var showUsbSentry by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(showEditor || showUsbSentry) { showEditor = false; showUsbSentry = false }

    if (showUsbSentry) {
        UsbSentryScreen(onBack = { showUsbSentry = false })
        return
    }

    if (showEditor) {
        SoundEditorScreen(onBack = { showEditor = false })
        return
    }

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
                        "Connect the vehicle USB to browse and play factory four-view 360° Sentry recordings. Save the original or export individual views; direction names appear only for verified layouts.",
                        "连接车辆 USB 后可浏览和播放原厂四画面 360° 哨兵录像；支持保存原片或分别导出画面，只有已验证布局才显示方向名称。",
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
    }
}
