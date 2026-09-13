package com.dante.zeekrcapabilitylab.ui.product

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.dante.zeekrcapabilitylab.BuildConfig
import com.dante.zeekrcapabilitylab.product.SettingsStore
import com.dante.zeekrcapabilitylab.product.ProductRecorderConfigFactory
import com.dante.zeekrcapabilitylab.product.RecordingSourcePolicy
import com.dante.zeekrcapabilitylab.product.FisheyeCorrectionConfig
import com.dante.zeekrcapabilitylab.product.AppLanguage
import com.dante.zeekrcapabilitylab.product.AppLanguageMode
import com.dante.zeekrcapabilitylab.util.Utils
import com.dante.zeekrcapabilitylab.service.recorder.RecordingSourceRole
import com.dante.zeekrcapabilitylab.service.recorder.RecordingStoragePreference
import com.dante.zeekrcapabilitylab.service.recorder.UsbRecordingQuotaPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(onOpenUsbManagement: () -> Unit = {}) {
    val context = LocalContext.current
    val settings = remember { SettingsStore.get(context) }
    val languageMode by AppLanguage.mode.collectAsState()
    var showDiagnostics by remember { mutableStateOf(false) }

    var segment by remember { mutableStateOf(settings.segmentSeconds) }
    var storage by remember { mutableStateOf(settings.internalStorageLimitBytes) }
    var storagePreference by remember { mutableStateOf(settings.recordingStoragePreference) }
    var usbQuota by remember { mutableStateOf(settings.usbQuotaBytes) }
    var customUsbQuota by remember { mutableStateOf(usbQuota !in SettingsStore.USB_QUOTA_PRESETS) }
    var customUsbQuotaText by remember {
        mutableStateOf((usbQuota / (1024L * 1024L * 1024L)).toString())
    }
    var safety by remember { mutableStateOf(settings.minFreeBytes) }
    var autoCleanup by remember { mutableStateOf(settings.autoCleanupEnabled) }
    var previewWhileRecording by remember { mutableStateOf(settings.previewWhileRecordingEnabled) }
    var correction by remember { mutableStateOf(settings.fisheyeCorrection) }
    var developerModeEnabled by remember { mutableStateOf(settings.developerModeEnabled) }
    if (developerModeEnabled && showDiagnostics) {
        RecorderDiagnosticsDialog(onDismiss = { showDiagnostics = false })
    }
    var developerModeMessage by remember { mutableStateOf("") }
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
                var languageMenu by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { languageMenu = true }) { Text(AppLanguage.label(languageMode) + " ▾") }
                    DropdownMenu(expanded = languageMenu, onDismissRequest = { languageMenu = false }) {
                        AppLanguageMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(AppLanguage.label(mode) + if (mode == languageMode) " ✓" else "") },
                                onClick = { AppLanguage.setMode(context, mode); languageMenu = false },
                            )
                        }
                    }
                }
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
                    options = SettingsStore.SEGMENT_OPTIONS.map { Utils.t("{0} min", "{0} 分钟", it / 60) },
                    selectedIndex = SettingsStore.SEGMENT_OPTIONS.indexOf(segment).coerceAtLeast(0),
                    onSelect = { index ->
                        segment = SettingsStore.SEGMENT_OPTIONS[index]
                        settings.setSegmentSeconds(segment)
                    },
                )
                OptionRow(
                    label = Utils.t("Recording storage", "录像存储"),
                    options = RecordingStoragePreference.entries.map { preference ->
                        when (preference) {
                            RecordingStoragePreference.USB_PREFERRED -> Utils.t("USB preferred", "USB 优先")
                            RecordingStoragePreference.INTERNAL_ONLY -> Utils.t("Internal only", "仅内部")
                        }
                    },
                    selectedIndex = RecordingStoragePreference.entries.indexOf(storagePreference),
                    onSelect = { index ->
                        storagePreference = RecordingStoragePreference.entries[index]
                        settings.setRecordingStoragePreference(storagePreference)
                    },
                )
                OptionRow(
                    label = Utils.t("Internal limit", "内部存储上限"),
                    options = SettingsStore.STORAGE_OPTIONS.map { "${it / (1024L * 1024L * 1024L)} GB" },
                    selectedIndex = SettingsStore.STORAGE_OPTIONS.indexOf(storage).coerceAtLeast(0),
                    onSelect = { index ->
                        storage = SettingsStore.STORAGE_OPTIONS[index]
                        settings.setInternalStorageLimitBytes(storage)
                    },
                )
                OptionRow(
                    label = Utils.t("USB OpenAVM limit", "USB OpenAVM 上限"),
                    options = SettingsStore.USB_QUOTA_PRESETS.map {
                        "${it / (1024L * 1024L * 1024L)} GB"
                    } + Utils.t("Custom", "自定义"),
                    selectedIndex = if (customUsbQuota) {
                        SettingsStore.USB_QUOTA_PRESETS.size
                    } else {
                        SettingsStore.USB_QUOTA_PRESETS.indexOf(usbQuota).coerceAtLeast(0)
                    },
                    onSelect = { index ->
                        if (index == SettingsStore.USB_QUOTA_PRESETS.size) {
                            customUsbQuota = true
                        } else {
                            customUsbQuota = false
                            usbQuota = SettingsStore.USB_QUOTA_PRESETS[index]
                            customUsbQuotaText = (usbQuota / (1024L * 1024L * 1024L)).toString()
                            settings.setUsbQuotaBytes(usbQuota)
                        }
                    },
                )
                if (customUsbQuota) {
                    val customGiB = customUsbQuotaText.toLongOrNull()
                    val customBytes = customGiB?.takeIf { it <= Long.MAX_VALUE / (1024L * 1024L * 1024L) }
                        ?.times(1024L * 1024L * 1024L)
                    val validCustom = customBytes?.let(UsbRecordingQuotaPolicy::isValidQuota) == true
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(Utils.t("Custom USB limit", "自定义 USB 上限"), modifier = Modifier.width(130.dp))
                        OutlinedTextField(
                            value = customUsbQuotaText,
                            onValueChange = { value ->
                                customUsbQuotaText = value.filter(Char::isDigit).take(4)
                                val gib = customUsbQuotaText.toLongOrNull()
                                val bytes = gib?.times(1024L * 1024L * 1024L)
                                if (bytes != null && UsbRecordingQuotaPolicy.isValidQuota(bytes)) {
                                    usbQuota = bytes
                                    settings.setUsbQuotaBytes(bytes)
                                }
                            },
                            suffix = { Text("GiB") },
                            singleLine = true,
                            isError = !validCustom,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(190.dp),
                        )
                    }
                }
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
                        "Head-unit and USB storage have separate limits. Automatic cleanup removes only this app's recordings and keeps factory Sentry videos.",
                        "车机与 USB 分别设置存储上限。自动清理只删除本应用的录像，保留原厂哨兵视频。",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (developerModeEnabled) {
            var mappings by remember {
                mutableStateOf(RecordingSourceRole.entries.associateWith(settings::cameraMapping))
            }
            val cameraIds by produceState(initialValue = emptyList<String>()) {
                value = withContext(Dispatchers.IO) {
                    ProductRecorderConfigFactory.listRecordingCameraCapabilities(context)
                        .map { it.cameraId }
                        .distinct()
                        .sorted()
                }
            }
            Spacer(Modifier.height(10.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(Utils.t("Developer tools", "开发者工具"), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { showDiagnostics = true }) {
                        Text(Utils.t("Recording diagnostics", "录像诊断"))
                    }
                }
            }
            Spacer(Modifier.height(10.dp))

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        Utils.t("Camera mapping (advanced)", "摄像头映射（高级）"),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        Utils.t(
                            "The home screen uses logical sources. Change physical Camera IDs only for another vehicle or after an OTA changes the mapping.",
                            "首页只显示逻辑录像源。仅在其他车型或 OTA 改变 Camera ID 时修改这里。",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    RecordingSourceRole.entries.forEach { role ->
                        val current = mappings.getValue(role)
                        val values = buildList {
                            if (role == RecordingSourceRole.SURROUND) add(RecordingSourcePolicy.AUTO)
                            addAll(cameraIds)
                            if (current !in this) add(current)
                        }
                        OptionRow(
                            label = sourceRoleLabel(role),
                            options = values.map { value ->
                                if (value == RecordingSourcePolicy.AUTO) {
                                    Utils.t("Auto", "自动")
                                } else {
                                    "Camera $value"
                                }
                            },
                            selectedIndex = values.indexOf(current).coerceAtLeast(0),
                            onSelect = { index ->
                                val selected = values[index]
                                settings.setCameraMapping(role, selected)
                                mappings = mappings + (role to selected)
                            },
                        )
                    }
                    Text(
                        Utils.t(
                            "Current verified ZEEKR 7X defaults: 360° Auto/Camera 2, Cabin Camera 1, IR Camera 0. Auto accepts only a four-lane composite camera.",
                            "当前已验证的极氪 7X 默认值：360° 自动/Camera 2、Cabin Camera 1、IR Camera 0。自动模式只接受四路合成摄像头。",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        Utils.t("USB storage management", "USB 存储管理"),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        Utils.t(
                            "USB management: inspect removable capacity, keep factory SentryMode read-only, and manage verified OpenAVM content under the configured quota.",
                            "USB 管理：查看可移动存储容量、保持原厂 SentryMode 只读，并按已配置配额管理已验证的 OpenAVM 内容。",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onOpenUsbManagement) {
                        Text(Utils.t("Open USB management", "打开 USB 管理"))
                    }
                }
            }
        }

        if (developerModeEnabled) {
            Spacer(Modifier.height(10.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        Utils.t("Developer display tuning", "开发者显示调试"),
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
                        "Recordings are stored on the head unit or USB. Deletion and cleanup apply only to this app's recordings.",
                        "录像保存在车机或 USB 中；删除与清理只针对本应用的录像。",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    Utils.t("Version", "版本") +
                        " ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})" +
                        if (developerModeEnabled) " · ${BuildConfig.GIT_SHA}" else "",
                    modifier = Modifier.clickable {
                        if (!developerModeEnabled) {
                            versionTapCount++
                            if (versionTapCount >= 7) {
                                developerModeEnabled = true
                                settings.setDeveloperModeEnabled(true)
                                developerModeMessage = Utils.t(
                                    "Developer tools enabled.",
                                    "开发者工具已启用。",
                                )
                                versionTapCount = 0
                            }
                        }
                    },
                )
                if (developerModeEnabled) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        developerModeMessage.ifBlank {
                            Utils.t("Developer tools enabled.", "开发者工具已启用。")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        Utils.t(
                            "Recording diagnostics, USB tools and camera tuning are visible only in developer mode.",
                            "录像诊断、USB 工具与摄像头调试仅在开发者模式下显示。",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = {
                        settings.setDeveloperModeEnabled(false)
                        developerModeEnabled = false
                        showDiagnostics = false
                        developerModeMessage = ""
                        versionTapCount = 0
                    }) {
                        Text(Utils.t("Disable developer mode", "关闭开发者模式"))
                    }
                }
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
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
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

private fun sourceRoleLabel(role: RecordingSourceRole): String = when (role) {
    RecordingSourceRole.SURROUND -> "360°"
    RecordingSourceRole.CABIN -> Utils.t("Cabin", "车内")
    RecordingSourceRole.IR -> Utils.t("Infrared", "红外")
}
