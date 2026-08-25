package com.dante.zeekrcapabilitylab.ui.product

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.dante.zeekrcapabilitylab.BuildConfig
import com.dante.zeekrcapabilitylab.product.SettingsStore
import com.dante.zeekrcapabilitylab.product.CameraRuntime
import com.dante.zeekrcapabilitylab.product.RuntimeCameraSource
import com.dante.zeekrcapabilitylab.product.FisheyeCorrectionConfig
import com.dante.zeekrcapabilitylab.product.FourLaneLensMode
import com.dante.zeekrcapabilitylab.product.AppLanguage
import com.dante.zeekrcapabilitylab.product.AppLanguageMode
import com.dante.zeekrcapabilitylab.probe.camera.CameraProfileCatalog
import com.dante.zeekrcapabilitylab.probe.camera.ProfileSize
import com.dante.zeekrcapabilitylab.service.CameraRecordingService
import com.dante.zeekrcapabilitylab.service.recorder.FrontCalibrationConfirmationPolicy
import com.dante.zeekrcapabilitylab.service.recorder.RecordingMode
import com.dante.zeekrcapabilitylab.service.recorder.RecordingSourceKind
import com.dante.zeekrcapabilitylab.util.Utils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val settings = remember { SettingsStore.get(context) }
    val scope = rememberCoroutineScope()
    val languageMode by AppLanguage.mode.collectAsState()
    val calibrationPreviewController = remember {
        SafeManualPreviewController(context.applicationContext)
    }
    val calibrationPreviewState by calibrationPreviewController.state.collectAsState()

    var segment by remember { mutableStateOf(settings.segmentSeconds) }
    var storage by remember { mutableStateOf(settings.storageLimitBytes) }
    var safety by remember { mutableStateOf(settings.minFreeBytes) }
    var retentionHours by remember { mutableStateOf(settings.retentionHours) }
    var autoCleanup by remember { mutableStateOf(settings.autoCleanupEnabled) }
    var recordingMode by remember { mutableStateOf(settings.recordingMode) }
    var sources by remember { mutableStateOf<List<RuntimeCameraSource>>(emptyList()) }
    var sourceMessage by remember { mutableStateOf<String?>(null) }
    var frontLane by remember { mutableStateOf(settings.frontCalibration?.frontLane ?: 1) }
    var frontRotation by remember { mutableStateOf(settings.frontCalibration?.rotationDegrees ?: 0) }
    var calibrationSourceSize by remember {
        mutableStateOf(
            settings.frontCalibration?.let { ProfileSize(it.sourceWidth, it.sourceHeight) },
        )
    }
    var calibrationSourceFingerprint by remember {
        mutableStateOf(settings.frontCalibration?.sourceFingerprint)
    }
    var correction by remember { mutableStateOf(settings.fisheyeCorrection) }
    var correctionTuningVisible by remember { mutableStateOf(false) }
    var versionTapCount by remember { mutableStateOf(0) }

    val calibrationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            calibrationSourceSize?.let(calibrationPreviewController::startPreview)
        } else {
            sourceMessage = Utils.t(
                "Camera permission is required for parked visual calibration.",
                "停车可视校准需要摄像头权限。",
            )
        }
    }

    DisposableEffect(calibrationPreviewController) {
        onDispose { calibrationPreviewController.release() }
    }

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
                    label = Utils.t("Recording mode", "录像模式"),
                    options = listOf(Utils.t("Front only", "仅前方"), "360°"),
                    selectedIndex = when (recordingMode) {
                        RecordingMode.FRONT_ONLY -> 0
                        RecordingMode.SURROUND_360 -> 1
                        null -> -1
                    },
                    onSelect = { index ->
                        val selected = if (index == 0) RecordingMode.FRONT_ONLY else RecordingMode.SURROUND_360
                        if (recordingMode != selected) {
                            calibrationPreviewController.stopPreview()
                            recordingMode = selected
                            settings.setRecordingMode(selected)
                            sources = emptyList()
                            calibrationSourceSize = null
                            calibrationSourceFingerprint = null
                            sourceMessage = Utils.t(
                                "Confirm the camera source for this mode.",
                                "请为此模式确认摄像头来源。",
                            )
                        }
                    },
                )
                Text(
                    Utils.t(
                        "Front only is an experimental vehicle-test path. It requires parked visual calibration; 360 uses substantially more storage and may use more shared resources.",
                        "“仅前方”是尚待实车验证的实验路径，必须先停车完成可视校准；360° 会占用更多存储空间，也可能使用更多共享资源。",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            sources = withContext(Dispatchers.IO) {
                                val kind = if (recordingMode == RecordingMode.FRONT_ONLY) {
                                    RecordingSourceKind.COMPOSITE_CROP
                                } else {
                                    RecordingSourceKind.COMPOSITE
                                }
                                CameraRuntime.sourceCatalog(context).filter { source ->
                                    source.sizesFor(kind).any(CameraProfileCatalog::isFourLaneComposite)
                                }
                            }
                            sourceMessage = if (sources.isEmpty()) {
                                Utils.t("No composite source was positively identified.", "未能明确识别合成摄像头来源。")
                            } else {
                                Utils.t("Select the observed composite source below.", "请在下方选择已观察到的合成来源。")
                            }
                        }
                    },
                ) {
                    Text(Utils.t("Discover camera sources", "检测摄像头来源"))
                }
                sources.forEach { source ->
                    val kind = if (recordingMode == RecordingMode.FRONT_ONLY) {
                        RecordingSourceKind.COMPOSITE_CROP
                    } else {
                        RecordingSourceKind.COMPOSITE
                    }
                    val composite = preferredCompositeSize(source.sizesFor(kind))
                    OutlinedButton(
                        onClick = {
                            calibrationPreviewController.stopPreview()
                            settings.confirmSource(source.cameraId, source.fingerprint, kind)
                            calibrationSourceSize = composite
                            calibrationSourceFingerprint = source.fingerprint
                            sourceMessage = Utils.t(
                                "Source ${source.cameraId} confirmed (${composite ?: "unknown"}).",
                                "已确认来源 ${source.cameraId}（${composite ?: "未知"}）。",
                            )
                        },
                    ) {
                        Text("Camera ${source.cameraId} · ${composite ?: "?"}")
                    }
                }
                sourceMessage?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }
                if (recordingMode == RecordingMode.FRONT_ONLY) {
                    val sourceSize = calibrationSourceSize
                    if (sourceSize != null && calibrationSourceFingerprint == settings.sourceFingerprint) {
                        Text(
                            Utils.t(
                                "Park, start the live preview, then select the lane and rotation that visibly show the forward road.",
                                "请先停车并启动实时预览，再选择确实显示前方道路的画面与旋转方向。",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FrontCalibrationPreview(
                            controller = calibrationPreviewController,
                            state = calibrationPreviewState,
                            sourceSize = sourceSize,
                            lane = frontLane,
                            rotationDegrees = frontRotation,
                        )
                        OutlinedButton(
                            onClick = {
                                if (calibrationPreviewState.active) {
                                    calibrationPreviewController.stopPreview()
                                } else if (CameraRecordingService.isRunning()) {
                                    sourceMessage = Utils.t(
                                        "Stop recording before calibration.",
                                        "请先停止录像，再进行校准。",
                                    )
                                } else if (context.checkSelfPermission(Manifest.permission.CAMERA) ==
                                    PackageManager.PERMISSION_GRANTED
                                ) {
                                    calibrationPreviewController.startPreview(sourceSize)
                                } else {
                                    calibrationPermissionLauncher.launch(Manifest.permission.CAMERA)
                                }
                            },
                        ) {
                            Text(
                                if (calibrationPreviewState.active) {
                                    Utils.t("Stop calibration preview", "停止校准预览")
                                } else {
                                    Utils.t("I am parked — start live calibration", "车辆已停稳——启动实时校准")
                                },
                            )
                        }
                    } else {
                        Text(
                            Utils.t(
                                "Discover and confirm a compatible source before calibration.",
                                "请先检测并确认兼容的摄像头来源，再进行校准。",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OptionRow(
                        label = Utils.t("Actual front lane", "实际前方画面"),
                        options = (1..4).map { Utils.t("View $it", "视角$it") },
                        selectedIndex = frontLane - 1,
                        onSelect = {
                            val selected = it + 1
                            if (frontLane != selected) settings.clearFrontCalibration()
                            frontLane = selected
                        },
                    )
                    OptionRow(
                        label = Utils.t("Record rotation", "录像旋转"),
                        options = listOf("0°", "90°", "180°", "270°"),
                        selectedIndex = listOf(0, 90, 180, 270).indexOf(frontRotation),
                        onSelect = {
                            val selected = listOf(0, 90, 180, 270)[it]
                            if (frontRotation != selected) settings.clearFrontCalibration()
                            frontRotation = selected
                        },
                    )
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val expectedFingerprint = calibrationSourceFingerprint
                                val sourceUnchanged = !expectedFingerprint.isNullOrBlank() &&
                                    expectedFingerprint == settings.sourceFingerprint &&
                                    settings.selectedCameraId != null
                                val previewMatchesSource = calibrationPreviewState.forcedBufferSize?.let {
                                    it.width == calibrationSourceSize?.width &&
                                        it.height == calibrationSourceSize?.height
                                } == true && !calibrationPreviewState.fallbackUsed
                                val confirmed = FrontCalibrationConfirmationPolicy.canSave(
                                    previewActive = calibrationPreviewState.active,
                                    firstFrameVisible = calibrationPreviewState.firstFrame,
                                    sourceUnchanged = sourceUnchanged,
                                    previewMatchesSource = previewMatchesSource,
                                )
                                val size = calibrationSourceSize
                                val calibrated = confirmed && size != null && expectedFingerprint != null &&
                                    withContext(Dispatchers.IO) {
                                        settings.saveFrontCalibration(
                                            sourceSize = size,
                                            lane = frontLane,
                                            rotationDegrees = frontRotation,
                                            visuallyConfirmed = confirmed,
                                            expectedSourceFingerprint = expectedFingerprint,
                                        )
                                    }
                                sourceMessage = if (calibrated) {
                                    Utils.t("Front calibration saved for this source.", "已为此来源保存前方校准。")
                                } else {
                                    Utils.t(
                                        "Calibration blocked: keep the vehicle parked and confirm the selected live crop after its first frame appears.",
                                        "校准已阻止：请保持停车，并在实时画面首帧出现后确认所选裁切画面。",
                                    )
                                }
                            }
                        },
                    ) {
                        Text(Utils.t("Save the visible front crop", "保存当前可见前方画面"))
                    }
                }
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
                    label = Utils.t("Keep ordinary recordings", "普通录像保留时长"),
                    options = SettingsStore.RETENTION_HOURS_OPTIONS.map {
                        Utils.t("$it hours", "$it 小时")
                    },
                    selectedIndex = SettingsStore.RETENTION_HOURS_OPTIONS
                        .indexOf(retentionHours)
                        .coerceAtLeast(0),
                    onSelect = { index ->
                        retentionHours = SettingsStore.RETENTION_HOURS_OPTIONS[index]
                        settings.setRetentionHours(retentionHours)
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
                Text(
                    Utils.t(
                        "Recording preview is disabled. The service-owned recording path never depends on an activity surface.",
                        "录像期间预览已关闭；服务自有的录像路径不依赖 Activity 表面。",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    Utils.t(
                        "Automatic cleanup removes ordinary recordings older than the selected retention window and may keep less history when the storage limit or free-space reserve requires it. Protected events, playback, transfers and the current recording are never selected.",
                        "自动清理会删除超过所选保留时长的普通录像；当达到容量上限或可用空间预留要求时，实际保留时长可能更短。受保护事件、正在回放、正在传输和当前录像永远不会被选中。",
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
private fun FrontCalibrationPreview(
    controller: SafeManualPreviewController,
    state: ManualPreviewState,
    sourceSize: ProfileSize,
    lane: Int,
    rotationDegrees: Int,
) {
    val context = LocalContext.current
    val previewView = remember(controller) { FourLaneTextureContainer(context) }
    Card(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            AndroidView(
                factory = {
                    previewView.also { controller.attach(it.textureView) }
                },
                update = {
                    it.sourceWidth = sourceSize.width
                    it.sourceHeight = sourceSize.height
                    it.displayMode = FourLaneDisplayMode.forLane(lane)
                    it.previewRotationDegrees = rotationDegrees
                    it.lensMode = FourLaneLensMode.FISHEYE
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (!state.firstFrame) {
                Text(
                    text = state.error?.let { Utils.t("Preview unavailable", "预览暂不可用") }
                        ?: Utils.t("Start the parked live preview", "请启动停车实时预览"),
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .background(Color(0xC0000000))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            Text(
                Utils.t("Selected raw view $lane · $rotationDegrees°", "已选原始视角$lane · $rotationDegrees°"),
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .background(Color(0xC0000000))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

private fun preferredCompositeSize(sizes: List<ProfileSize>): ProfileSize? = sizes
    .filter(CameraProfileCatalog::isFourLaneComposite)
    .sortedWith(
        compareBy<ProfileSize> { if (it.height > it.width) 0 else 1 }
            .thenByDescending { it.totalPixels },
    )
    .firstOrNull()

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
