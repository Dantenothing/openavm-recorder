package com.dante.zeekrcapabilitylab.ui.product

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

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
    val mirrorPresentation = remember { com.dante.zeekrcapabilitylab.mirror.MirrorPresentation(context) }
    var mirrorLeft by remember { mutableStateOf(mirrorPresentation.leftLane) }
    var mirrorRight by remember { mutableStateOf(mirrorPresentation.rightLane) }
    var rightHandDrive by remember { mutableStateOf(mirrorPresentation.rightHandDrive) }
    val languageMode by AppLanguage.mode.collectAsState()
    var showDiagnostics by remember { mutableStateOf(false) }
    var showGuide by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    if (showGuide) QuickStartGuide(onDismiss = { showGuide = false })

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
    var recordingOverlay by remember { mutableStateOf(settings.recordingOverlayEnabled) }
    var mirrorEnabled by remember { mutableStateOf(settings.mirrorPreviewEnabled) }
    var rearLane by remember { mutableStateOf(settings.mirrorRearLane) }
    var mirrorRotation by remember { mutableStateOf(settings.mirrorRotation) }
    var mirrorHorizontal by remember { mutableStateOf(settings.mirrorHorizontal) }
    var externalActionMessage by remember { mutableStateOf("") }
    val lifecycleOwner = LocalLifecycleOwner.current
    val overlayPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        recordingOverlay = Settings.canDrawOverlays(context)
        settings.setRecordingOverlayEnabled(recordingOverlay)
        if (!recordingOverlay) externalActionMessage = Utils.t("Overlay permission was not granted.", "尚未获得悬浮窗权限。")
    }
    val mirrorPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        mirrorEnabled = Settings.canDrawOverlays(context)
        settings.setMirrorPreviewEnabled(mirrorEnabled)
        if (!mirrorEnabled) externalActionMessage = Utils.t("Overlay permission was not granted.", "尚未获得悬浮窗权限。")
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && !Settings.canDrawOverlays(context)) {
                recordingOverlay = false
                settings.setRecordingOverlayEnabled(false)
                mirrorEnabled = false
                settings.setMirrorPreviewEnabled(false)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
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
        OutlinedButton(onClick = { showGuide = true }) { Text(Utils.t("Quick start & help", "新手教程与帮助")) }
        Spacer(Modifier.height(8.dp))
        MirrorReturnSettingsEntry()
        if (BuildConfig.MIRROR_RETURN_ENABLED) Spacer(Modifier.height(10.dp))

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
                        "“跟随系统”使用当前车机系统语言，切换后立即生效。",
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
                RecordingQualitySettings()
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
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(Utils.t("Floating recording status", "录像悬浮窗"))
                        Text(Utils.t("Show status, stop and return controls outside OpenAVM. Drag the title to move it.", "离开 OpenAVM 页面时显示状态、停止和返回按钮，可拖动标题移动。"), style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(recordingOverlay, { enable ->
                        externalActionMessage = ""
                        if (!enable || Settings.canDrawOverlays(context)) {
                            recordingOverlay = enable
                            settings.setRecordingOverlayEnabled(enable)
                        } else runCatching {
                            overlayPermission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                        }.onFailure { externalActionMessage = Utils.t("This head unit does not provide the permission page.", "此车机未提供该权限设置页面。") }
                    })
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(Utils.t("Floating mirror", "悬浮后视镜"))
                        Text(Utils.t("Live view with or without surround recording. Start and stop recording directly from the floating window.", "环视录像或不录像时均可看实时画面，也可直接从悬浮窗开始、停止录像。"), style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(mirrorEnabled, { enable ->
                        externalActionMessage = ""
                        if (!enable || Settings.canDrawOverlays(context)) {
                            mirrorEnabled = enable; settings.setMirrorPreviewEnabled(enable)
                            if (!enable) com.dante.zeekrcapabilitylab.mirror.FloatingMirrorService.close()
                        } else runCatching {
                            mirrorPermission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                        }.onFailure { externalActionMessage = Utils.t("This head unit does not provide the permission page.", "此车机未提供该权限设置页面。") }
                    })
                }
                if (mirrorEnabled) {
                    OutlinedButton(onClick = {
                        if (!com.dante.zeekrcapabilitylab.mirror.StandaloneMirrorService.start(context)) {
                            externalActionMessage = Utils.t("Select the rear view and grant camera/overlay permissions before opening the mirror.", "请先选择后方视角，并确认相机及悬浮窗权限，再开启后视镜。")
                        }
                    }) { Text(Utils.t("Open floating mirror", "开启悬浮后视镜")) }
                    OptionRow(Utils.t("Driver side", "驾驶位"), listOf(Utils.t("Left", "左侧"), Utils.t("Right", "右侧")), if (rightHandDrive) 1 else 0) {
                        rightHandDrive = it == 1; mirrorPresentation.rightHandDrive = rightHandDrive
                    }
                    OptionRow(Utils.t("Left camera view", "左方对应视角"), (1..4).map { it.toString() }, mirrorLeft - 1) {
                        mirrorLeft = it + 1; mirrorPresentation.leftLane = mirrorLeft
                    }
                    OptionRow(Utils.t("Right camera view", "右方对应视角"), (1..4).map { it.toString() }, mirrorRight - 1) {
                        mirrorRight = it + 1; mirrorPresentation.rightLane = mirrorRight
                    }
                    Text(Utils.t("The four-view button changes the preview layout only. Tap a view to enlarge it.", "四宫格按钮只改变预览布局，点击一个视角即可放大。"), style = MaterialTheme.typography.bodySmall)
                    Text(Utils.t("Use the car logo to select a view. Drag the header to move, pull the lower corner to resize, and use the arrow to hide the picture. Closing the window does not stop recording.", "点击车标选择视角，拖动标题移动，拉下角调整窗口大小，箭头可收起画面。关闭窗口不会停止录像。"), style = MaterialTheme.typography.bodySmall)
                    Text(Utils.t("While parked, match the camera directions to the real surroundings. These settings change the displayed view only.", "停车时对照真实环境确认摄像头方向。这些设置只改变显示视角。"), style = MaterialTheme.typography.bodySmall)
                    OptionRow(Utils.t("Rear camera view", "后方对应视角"), (1..4).map { it.toString() }, rearLane - 1) {
                        rearLane = it + 1; settings.setMirrorRearLane(rearLane)
                    }
                    OptionRow(Utils.t("Display rotation", "显示旋转"), listOf("0°", "90°", "180°", "270°"), mirrorRotation / 90) {
                        mirrorRotation = it * 90; settings.setMirrorRotation(mirrorRotation)
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(Utils.t("Mirror left and right", "左右镜像"), Modifier.weight(1f))
                        Switch(mirrorHorizontal, { mirrorHorizontal = it; settings.setMirrorHorizontal(it) })
                    }
                    PreviewPhotoGallery()
                    Text(Utils.t("Tap Normal or Time-lapse, then adjust the rate beside Time-lapse. Photos save the original view to USB Pictures/OpenAVM. Screen-off ends preview-only mode.", "点击普通或延时，在延时旁调整倍率。照片按原始视角保存到 USB Pictures/OpenAVM。息屏后结束纯预览。"), style = MaterialTheme.typography.bodySmall)
                }
                if (externalActionMessage.isNotEmpty()) Text(externalActionMessage, color = MaterialTheme.colorScheme.error)
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
                    var continuousRecording by remember { mutableStateOf(settings.sharedInputRecordingEnabled) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(Utils.t("Continuous recording", "连续录像"), Modifier.weight(1f))
                        Switch(continuousRecording, { continuousRecording = it; settings.setSharedInputRecordingEnabled(it) })
                    }
                    Text(Utils.t("On by default for supported surround cameras. Keeps recording and preview running between files. Turn off if compatibility problems occur; applies to the next recording start.",
                        "支持的环视摄像头默认开启，分段时保持录像和预览连续。出现兼容问题时可关闭，下次开始录像生效。"), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { showDiagnostics = true }) {
                        Text(Utils.t("Recording diagnostics", "录像诊断"))
                    }
                    if (BuildConfig.EXPERIMENTAL_TOOLS_ENABLED) OutlinedButton(onClick = {
                        context.startActivity(android.content.Intent(context, com.dante.zeekrcapabilitylab.runtime.ParkingDiagnosticsActivity::class.java))
                    }) { Text("离车与后台相机诊断 / Parking diagnostics") }
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
                if (BuildConfig.EXPERIMENTAL_TOOLS_ENABLED) Text(
                    Utils.t(
                        "Videos stay on the head unit or USB and transfer over your local network. Online diagnostic upload and remote lab sessions require separate activation in developer tools. Checking releases contacts GitHub.",
                        "视频保留在车机或 USB，通过本地网络传输。在线诊断上传和远程实验会话需在开发者工具中另行开启；检查版本会连接 GitHub。",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                ) else Text(
                    Utils.t("Videos transfer over your local network. Checking for updates contacts GitHub. This release does not upload diagnostic reports automatically.",
                        "视频通过本地网络传输；检查更新会连接 GitHub。本版本不会自动上传诊断报告。"),
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
                OutlinedButton(onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Dantenothing/openavm-recorder/releases/latest")))
                    }.onFailure { externalActionMessage = Utils.t("No browser is available to open this page.", "没有可用于打开此页面的浏览器。") }
                }) { Text(Utils.t("Official releases and updates", "官方发布与更新")) }
                ReleaseCheckSettings()
                Spacer(Modifier.height(12.dp))
                if (developerModeEnabled) {
                    if (BuildConfig.EXPERIMENTAL_TOOLS_ENABLED) ConcurrentRecordingSettings()
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
