package com.dante.zeekrcapabilitylab.ui.product

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import com.dante.zeekrcapabilitylab.BuildConfig
import com.dante.zeekrcapabilitylab.product.SettingsStore
import com.dante.zeekrcapabilitylab.product.FisheyeCorrectionConfig
import com.dante.zeekrcapabilitylab.product.AppLanguage
import com.dante.zeekrcapabilitylab.product.AppLanguageMode
import com.dante.zeekrcapabilitylab.util.Utils
import kotlinx.coroutines.flow.MutableStateFlow

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val settings = remember { SettingsStore.get(context) }
    val languageMode by AppLanguage.mode.collectAsState()

    var segment by remember { mutableStateOf(settings.segmentSeconds) }
    var storage by remember { mutableStateOf(settings.storageLimitBytes) }
    var safety by remember { mutableStateOf(settings.minFreeBytes) }
    var autoCleanup by remember { mutableStateOf(settings.autoCleanupEnabled) }
    var previewWhileRecording by remember { mutableStateOf(settings.previewWhileRecordingEnabled) }
    var correction by remember { mutableStateOf(settings.fisheyeCorrection) }
    var correctionTuningVisible by remember { mutableStateOf(false) }
    var versionTapCount by remember { mutableStateOf(0) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(Utils.t("Settings", "设置"), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(Utils.t("Language", "语言"), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                OptionRow(
                    label = Utils.t("App language", "应用语言"),
                    options = listOf(
                        Utils.t("Follow system", "跟随车机"),
                        Utils.t("Simplified Chinese", "简体中文"),
                        "English",
                    ),
                    selectedIndex = AppLanguageMode.entries.indexOf(languageMode),
                    onSelect = { index ->
                        AppLanguage.setMode(context, AppLanguageMode.entries[index])
                    },
                )
                Text(
                    Utils.t(
                        "Follow system uses the current head-unit language. The change takes effect immediately.",
                        "“跟随车机”使用当前车机系统语言，切换后立即生效。",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(Utils.t("Recording", "录像参数"), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                OptionRow(
                    label = Utils.t("Segment length", "分段长度"),
                    options = SettingsStore.SEGMENT_OPTIONS.map { Utils.t("${it / 60} min", "${it / 60} 分钟") },
                    selectedIndex = SettingsStore.SEGMENT_OPTIONS.indexOf(segment).coerceAtLeast(0),
                    onSelect = { index ->
                        segment = SettingsStore.SEGMENT_OPTIONS[index]
                        settings.setSegmentSeconds(segment)
                    },
                )
                OptionRow(
                    label = Utils.t("Storage limit", "最大存储"),
                    options = SettingsStore.STORAGE_OPTIONS.map { "${it / (1024L * 1024L * 1024L)} GB" },
                    selectedIndex = SettingsStore.STORAGE_OPTIONS.indexOf(storage).coerceAtLeast(0),
                    onSelect = { index ->
                        storage = SettingsStore.STORAGE_OPTIONS[index]
                        settings.setStorageLimitBytes(storage)
                    },
                )
                OptionRow(
                    label = Utils.t("Reserve free space", "预留可用空间"),
                    options = SettingsStore.RESERVE_OPTIONS.map {
                        "${it / (1024L * 1024L * 1024L)} GB"
                    },
                    selectedIndex = SettingsStore.RESERVE_OPTIONS.indexOf(safety).coerceAtLeast(0),
                    onSelect = { index ->
                        safety = SettingsStore.RESERVE_OPTIONS[index]
                        settings.setMinFreeBytes(safety)
                    },
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(Utils.t("Automatic cleanup", "自动清理"), modifier = Modifier.weight(1f))
                    Switch(
                        checked = autoCleanup,
                        onCheckedChange = {
                            autoCleanup = it
                            settings.setAutoCleanupEnabled(it)
                        },
                    )
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(Utils.t("Live preview while recording", "录像时实时预览"))
                        Text(
                            Utils.t(
                                "If the camera rejects preview + recorder, recording continues without preview.",
                                "若车机拒绝预览与录像同时输出，将自动退回仅录像。",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = previewWhileRecording,
                        onCheckedChange = {
                            previewWhileRecording = it
                            settings.setPreviewWhileRecordingEnabled(it)
                        },
                    )
                }
                Text(
                    Utils.t(
                        "Cleanup starts when recordings exceed the storage limit or free space falls below the reserve. It removes the oldest ordinary recordings first. Protected events, playback, transfers and the current recording are never selected.",
                        "录像超过容量上限或系统可用空间低于预留值时，将从最旧的普通录像开始清理。受保护事件、正在回放、正在传输和当前录像永远不会被选中。",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (correctionTuningVisible) {
            Spacer(Modifier.height(10.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        Utils.t("Correction tuning (vehicle test)", "标准修正调试（实车测试）"),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        Utils.t(
                            "These parameters affect display only. The original camera stream and recordings are unchanged.",
                            "这些参数只影响显示，原始摄像头视频流和录像文件不会改变。",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    CorrectionSliderRow(
                        label = "FOV",
                        valueText = "${correction.targetFovDegrees.toInt()}°",
                        value = correction.targetFovDegrees,
                        range = FisheyeCorrectionConfig.MIN_FOV_DEGREES..FisheyeCorrectionConfig.MAX_FOV_DEGREES,
                    ) { value ->
                        correction = correction.copy(targetFovDegrees = value).sanitized()
                        settings.setFisheyeCorrection(correction)
                    }
                    CorrectionSliderRow(
                        label = Utils.t("Crop zoom", "裁切缩放"),
                        valueText = "%.2f×".format(correction.cropZoom),
                        value = correction.cropZoom,
                        range = FisheyeCorrectionConfig.MIN_CROP_ZOOM..FisheyeCorrectionConfig.MAX_CROP_ZOOM,
                    ) { value ->
                        correction = correction.copy(cropZoom = value).sanitized()
                        settings.setFisheyeCorrection(correction)
                    }
                    CorrectionSliderRow(
                        label = "Center X",
                        valueText = "%.2f".format(correction.centerX),
                        value = correction.centerX,
                        range = FisheyeCorrectionConfig.MIN_CENTER..FisheyeCorrectionConfig.MAX_CENTER,
                    ) { value ->
                        correction = correction.copy(centerX = value).sanitized()
                        settings.setFisheyeCorrection(correction)
                    }
                    CorrectionSliderRow(
                        label = "Center Y",
                        valueText = "%.2f".format(correction.centerY),
                        value = correction.centerY,
                        range = FisheyeCorrectionConfig.MIN_CENTER..FisheyeCorrectionConfig.MAX_CENTER,
                    ) { value ->
                        correction = correction.copy(centerY = value).sanitized()
                        settings.setFisheyeCorrection(correction)
                    }
                    OutlinedButton(onClick = {
                        settings.resetFisheyeCorrection()
                        correction = settings.fisheyeCorrection
                    }) {
                        Text(Utils.t("Reset correction", "恢复修正默认值"))
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(Utils.t("About & privacy", "关于与隐私"), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    Utils.t(
                        "This app transfers data only between the head unit and a phone on the same hotspot. It does not access the internet or upload to the cloud.",
                        "本应用仅在车机本地与同一热点内的手机之间传输数据，不访问互联网、不上传云端。",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    Utils.t(
                        "Recordings remain on this device. Delete and cleanup apply only to recordings created by this app.",
                        "录像文件仅保存在本机；删除与清理只针对本应用生成的录像。",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    Utils.t("Version", "版本") + " ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    modifier = Modifier.clickable {
                        versionTapCount++
                        if (versionTapCount >= 5) correctionTuningVisible = true
                    },
                )
            }
        }
    }
}

@Composable
private fun CorrectionSliderRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.width(110.dp))
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier.weight(1f),
        )
        Text(valueText, modifier = Modifier.width(64.dp))
    }
}

@Composable
private fun OptionRow(
    label: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.width(130.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEachIndexed { index, option ->
                OutlinedButton(onClick = { onSelect(index) }) {
                    Text(
                        if (index == selectedIndex) "$option ✓" else option,
                    )
                }
            }
        }
    }
}
