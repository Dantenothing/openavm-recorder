package com.dante.zeekrcapabilitylab.ui.product

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.dante.zeekrcapabilitylab.product.ProductHomeCameraPolicy
import com.dante.zeekrcapabilitylab.product.ProductRecorderConfigFactory
import com.dante.zeekrcapabilitylab.product.SettingsStore
import com.dante.zeekrcapabilitylab.product.AppLanguage
import com.dante.zeekrcapabilitylab.product.FourLaneLensMode
import com.dante.zeekrcapabilitylab.service.CameraRecordingService
import com.dante.zeekrcapabilitylab.service.recorder.RecorderCommandPolicy
import com.dante.zeekrcapabilitylab.service.recorder.RecorderConfig
import com.dante.zeekrcapabilitylab.service.recorder.RecorderStatus
import com.dante.zeekrcapabilitylab.util.Utils
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val CONFIG_TIMEOUT_MS = 8_000L
private const val DEFAULT_ESTIMATED_BITRATE_BPS = 28_000_000L
private const val FOUR_GRID_ASPECT_RATIO = 1f

/**
 * Recorder config is computed only for an explicit user start or the persisted
 * opt-in auto-start setting. It is never computed directly during composition.
 */
private sealed interface RecordConfigState {
    data object Idle : RecordConfigState
    data object Loading : RecordConfigState
    data class Ready(val config: RecorderConfig) : RecordConfigState
    data class Failed(val reason: String) : RecordConfigState
}

private data class DiskStats(val freeBytes: Long)

@Composable
fun RecordScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recorderState by CameraRecordingService.state.collectAsState()
    val languageMode by AppLanguage.mode.collectAsState()
    val settings = remember(languageMode) { SettingsStore.get(context) }
    val previewController = remember { SafeManualPreviewController(context.applicationContext) }
    val previewState by previewController.state.collectAsState()
    val segmentsDir = remember { File(context.filesDir, "recordings/segments") }
    val now by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }

    var cameraPermission by remember {
        mutableStateOf(
            context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var notificationsGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var confirmStop by remember { mutableStateOf(false) }
    var configState by remember { mutableStateOf<RecordConfigState>(RecordConfigState.Idle) }
    var previewEnabled by remember { mutableStateOf(false) }
    var startupPermissionPrompted by rememberSaveable { mutableStateOf(false) }
    var autoStartAttempted by rememberSaveable { mutableStateOf(false) }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        cameraPermission = granted
    }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationsGranted = granted
    }

    val recordingActive = RecorderCommandPolicy.isActive(recorderState.status)
    val serviceRunning = CameraRecordingService.isRunning()

    DisposableEffect(previewController) {
        previewController.onRecorderPreviewSurfaceDestroyed = {
            CameraRecordingService.setPreviewOutputEnabled(context, false)
        }
        onDispose {
            // The screen leaving composition tears the TextureView down, so the
            // recorder must stop targeting the handed-off Surface; harmless
            // no-op when the service is idle. Ordering with the texture
            // callback is not guaranteed, so both paths disable defensively.
            CameraRecordingService.setPreviewOutputEnabled(context, false)
            previewController.release()
        }
    }

    LaunchedEffect(
        cameraPermission,
        recordingActive,
        recorderState.previewRequested,
        recorderState.previewFallbackUsed,
    ) {
        when {
            recordingActive -> {
                val recorderPreviewVisible = recorderState.previewRequested &&
                    !recorderState.previewFallbackUsed
                previewEnabled = recorderPreviewVisible
                if (!recorderPreviewVisible) previewController.clearRecorderPreviewHandoff()
            }
            cameraPermission && !recordingActive &&
                ProductHomeCameraPolicy.shouldAutoStartPreview(0L) &&
                ProductHomeCameraPolicy.cameraAccessAllowed(
                    ProductHomeCameraPolicy.TRIGGER_AUTO_PREVIEW,
                ) -> {
                previewController.clearRecorderPreviewHandoff()
                previewEnabled = true
                previewController.startPreview()
            }
        }
    }

    // Directory scans and free-space probes run on IO, never on the UI thread.
    val diskStats by produceState(initialValue = DiskStats(-1L), recordingActive) {
        value = withContext(Dispatchers.IO) {
            runCatching { segmentsDir.mkdirs() }
            val free = runCatching { segmentsDir.usableSpace }.getOrDefault(-1L)
            DiskStats(free)
        }
    }

    val readyConfig = (configState as? RecordConfigState.Ready)?.config
    val canStart = RecorderCommandPolicy.canStart(recorderState.status, serviceRunning) &&
        configState !is RecordConfigState.Loading
    val canStop = RecorderCommandPolicy.canStop(recorderState.status, serviceRunning)
    val canBookmark = RecorderCommandPolicy.canBookmark(serviceRunning)
    val canRetry = RecorderCommandPolicy.canRetry(recorderState.status, serviceRunning)

    val estimatedBitrateBps = recorderState.profile?.bitrateBps
        ?.takeIf { it > 0 }
        ?.toLong()
        ?: readyConfig?.profile?.bitrateBps
            ?.takeIf { it > 0 }
            ?.toLong()
        ?: DEFAULT_ESTIMATED_BITRATE_BPS
    val estimatedMinutes = if (diskStats.freeBytes > 0) {
        diskStats.freeBytes * 8 / estimatedBitrateBps / 60
    } else {
        null
    }
    val recordingElapsed = recorderState.segmentStartedAtEpochMs?.let { now - it }
    val recorderStatusText = when (recorderState.status) {
        RecorderStatus.STARTING -> Utils.t("Preparing", "正在准备")
        RecorderStatus.RECORDING -> Utils.t("Recording ${Utils.formatDuration(recordingElapsed)}", "录像中 ${Utils.formatDuration(recordingElapsed)}")
        RecorderStatus.FINALIZING -> Utils.t("Saving", "正在保存")
        RecorderStatus.CAMERA_UNAVAILABLE -> Utils.t("Camera in use", "摄像头占用")
        RecorderStatus.ERROR -> Utils.t("Recording error", "录像异常")
        else -> Utils.t("Standby", "待机")
    }
    val recorderStatusColor = when (recorderState.status) {
        RecorderStatus.RECORDING -> Color(0xFFFF6E6E)
        RecorderStatus.STARTING,
        RecorderStatus.FINALIZING,
        RecorderStatus.CAMERA_UNAVAILABLE -> Color(0xFFFFB74D)
        RecorderStatus.ERROR -> MaterialTheme.colorScheme.error
        else -> Color(0xFF66BB6A)
    }

    val startRecording: (String) -> Unit = { trigger ->
        when {
            !cameraPermission -> cameraLauncher.launch(Manifest.permission.CAMERA)
            !ProductHomeCameraPolicy.cameraAccessAllowed(
                trigger,
            ) -> Unit
            configState is RecordConfigState.Loading -> Unit
            else -> {
                configState = RecordConfigState.Loading
                scope.launch {
                    if (previewEnabled) {
                        // Close only the old preview CameraCaptureSession. Keep
                        // ManualPreviewPanel composed so its proven TextureView
                        // and SurfaceTexture survive long enough to be handed to
                        // the recorder's preview + encoder session below.
                        previewController.stopAndAwait()
                    }
                    val outcome = withTimeoutOrNull(CONFIG_TIMEOUT_MS) {
                        withContext(Dispatchers.IO) {
                            ProductRecorderConfigFactory.create(context)
                        }
                    }
                    configState = when {
                        outcome == null -> RecordConfigState.Failed(Utils.t("Configuration timed out. Please retry.", "配置计算超时，请重试"))
                        outcome.validate().isEmpty() -> RecordConfigState.Ready(outcome)
                        else -> RecordConfigState.Failed(
                            Utils.t("No valid recording configuration: ", "无可用的录制配置：") +
                                (outcome.validate().firstOrNull() ?: "invalid"),
                        )
                    }
                    val ready = configState as? RecordConfigState.Ready
                    if (ready != null) {
                        val recorderPreviewSurface = if (settings.previewWhileRecordingEnabled) {
                            previewController.acquireRecorderPreviewSurface()
                        } else {
                            null
                        }
                        previewEnabled = recorderPreviewSurface != null
                        CameraRecordingService.start(context, ready.config, recorderPreviewSurface)
                    } else if (cameraPermission) {
                        previewEnabled = true
                        previewController.startPreview()
                    }
                }
            }
        }
    }

    LaunchedEffect(cameraPermission, canStart, autoStartAttempted) {
        if (!cameraPermission && !startupPermissionPrompted) {
            startupPermissionPrompted = true
            cameraLauncher.launch(Manifest.permission.CAMERA)
        } else if (
            cameraPermission &&
            canStart &&
            !autoStartAttempted &&
            settings.autoStartRecordingEnabled
        ) {
            autoStartAttempted = true
            startRecording(ProductHomeCameraPolicy.TRIGGER_AUTO_START_RECORDING)
        }
    }

    Row(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        HomePreviewPane(
            controller = previewController,
            state = previewState,
            previewEnabled = previewEnabled,
            recordingActive = recordingActive,
            recorderPreviewRequested = recorderState.previewRequested,
            recorderPreviewFallbackUsed = recorderState.previewFallbackUsed,
            settings = settings,
            modifier = Modifier
                .weight(1.75f)
                .fillMaxHeight(),
        )
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(end = 4.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    Utils.t("Dashcam", "行车记录"),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                StatusPill(recorderStatusText, recorderStatusColor)
            }

            Spacer(Modifier.height(14.dp))
            ProductStatusCard(
                freeSpace = if (diskStats.freeBytes >= 0) {
                    formatBytes(diskStats.freeBytes)
                } else {
                    Utils.t("Unknown", "未知")
                },
                estimatedMinutes = estimatedMinutes,
                segmentSeconds = settings.segmentSeconds,
                autoCleanupEnabled = settings.autoCleanupEnabled,
            )
            Spacer(Modifier.height(14.dp))

            Button(
                onClick = {
                    startRecording(ProductHomeCameraPolicy.TRIGGER_USER_START_RECORDING)
                },
                enabled = canStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
            ) {
                Text(
                    when {
                        configState is RecordConfigState.Loading -> Utils.t("Preparing…", "准备中…")
                        recordingActive -> Utils.t("Recording", "录像中")
                        else -> Utils.t("Start recording", "开始录像")
                    },
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(9.dp))
            OutlinedButton(
                onClick = { confirmStop = true },
                enabled = canStop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
            ) {
                Text(Utils.t("Stop", "停止"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            TextButton(
                onClick = { CameraRecordingService.bookmark(context) },
                enabled = canBookmark,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Text(Utils.t("Save clip", "保存片段"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }

            if (recordingActive) {
                Text(
                    Utils.t("Writing segment ${recorderState.segmentNumber}", "正在写入第 ${recorderState.segmentNumber} 段"),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 5.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            when (val cs = configState) {
                is RecordConfigState.Failed ->
                    Text(
                        Utils.t("Unable to start: ", "无法开始：") + cs.reason,
                        modifier = Modifier.padding(top = 5.dp),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                is RecordConfigState.Loading ->
                    Text(
                        Utils.t("Preparing camera…", "正在准备摄像头…"),
                        modifier = Modifier.padding(top = 5.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                else -> Unit
            }
            if (!cameraPermission) {
                Text(
                    Utils.t("Camera permission is required. Tap Start recording to grant it.", "需要摄像头权限，点击“开始录像”即可完成授权。"),
                    modifier = Modifier.padding(top = 5.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (recorderState.status == RecorderStatus.CAMERA_UNAVAILABLE ||
                recorderState.status == RecorderStatus.ERROR
            ) {
                Card(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                when (recorderState.status) {
                                    RecorderStatus.CAMERA_UNAVAILABLE ->
                                        Utils.t("Camera unavailable (OEM 360/reverse camera has priority)", "摄像头不可用（原厂 360/倒车已优先接管）")
                                    else -> Utils.t("Recording error", "录像异常")
                                },
                                color = Color(0xFFF9A825),
                                fontWeight = FontWeight.Bold,
                            )
                            recorderState.lastError?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (canRetry) {
                            OutlinedButton(onClick = { CameraRecordingService.retry(context) }) {
                                Text(Utils.t("Retry", "重试"))
                            }
                        }
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationsGranted) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        Utils.t("Notifications are disabled, so Save/Stop shortcuts will not appear in the notification bar.", "通知权限未开启：通知栏里的保存/停止快捷键不可见。"),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    OutlinedButton(
                        onClick = {
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        },
                    ) {
                        Text(Utils.t("Enable", "开启"))
                    }
                }
            }
        }
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text(Utils.t("Stop recording?", "停止录像？")) },
            text = { Text(Utils.t("The current segment will be finalized and the camera released. Existing recordings will be kept.", "停止后将结束当前分段并释放摄像头。已录制的内容会保留。")) },
            confirmButton = {
                TextButton(onClick = {
                    confirmStop = false
                    CameraRecordingService.stop(context)
                }) { Text(Utils.t("Stop", "停止")) }
            },
            dismissButton = {
                TextButton(onClick = { confirmStop = false }) { Text(Utils.t("Cancel", "取消")) }
            },
        )
    }
}

@Composable
private fun HomePreviewPane(
    controller: SafeManualPreviewController,
    state: ManualPreviewState,
    previewEnabled: Boolean,
    recordingActive: Boolean,
    recorderPreviewRequested: Boolean,
    recorderPreviewFallbackUsed: Boolean,
    settings: SettingsStore,
    modifier: Modifier = Modifier,
) {
    var lensMode by remember(settings) { mutableStateOf(settings.lensMode) }
    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        val previewSide = minOf(maxWidth, maxHeight)
        Box(Modifier.size(previewSide)) {
            val showLivePreview = previewEnabled && (
                !recordingActive || (recorderPreviewRequested && !recorderPreviewFallbackUsed)
                )
            if (showLivePreview) {
                ManualPreviewPanel(
                    controller = controller,
                    state = state,
                    lensMode = lensMode,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                StaticLaneGrid(modifier = Modifier.fillMaxSize())
            }
            FourLaneLensToggle(
                mode = lensMode,
                onModeChanged = { selected ->
                    lensMode = selected
                    settings.setLensMode(selected)
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
            )
        }
    }
}

@Composable
private fun ProductStatusCard(
    freeSpace: String,
    estimatedMinutes: Long?,
    segmentSeconds: Int,
    autoCleanupEnabled: Boolean,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                Utils.t("Status", "状态信息"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(10.dp))
            ProductInfoRow(Utils.t("Phone transfer", "手机传输"), Utils.t("In development", "开发中"))
            ProductInfoRow(Utils.t("Free space", "可用空间"), freeSpace)
            ProductInfoRow(Utils.t("Estimated recording", "预计可录"), formatEstimatedMinutes(estimatedMinutes))
            ProductInfoRow(Utils.t("Segment length", "分段时长"), formatSegmentDuration(segmentSeconds))
            ProductInfoRow(Utils.t("Automatic cleanup", "自动清理"), if (autoCleanupEnabled) Utils.t("On", "已开启") else Utils.t("Off", "已关闭"))
        }
    }
}

@Composable
private fun ProductInfoRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            value,
            fontWeight = FontWeight.Medium,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ManualPreviewPanel(
    controller: SafeManualPreviewController,
    state: ManualPreviewState,
    lensMode: FourLaneLensMode,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val correctionConfig = SettingsStore.get(context).fisheyeCorrection
    val previewView = remember(controller) { FourLaneTextureContainer(context) }
    var displayMode by remember(controller) { mutableStateOf(FourLaneDisplayMode.FOUR_GRID) }
    var zoom by remember(controller) { mutableStateOf(1f) }

    LaunchedEffect(state.active) {
        if (!state.active) {
            displayMode = FourLaneDisplayMode.FOUR_GRID
            zoom = previewView.resetViewport()
        }
    }

    BackHandler(enabled = displayMode.singleLane != null) {
        displayMode = FourLaneDisplayMode.FOUR_GRID
        zoom = previewView.resetViewport()
    }

    Card(
        modifier.aspectRatio(FOUR_GRID_ASPECT_RATIO),
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
                    it.displayMode = displayMode
                    it.lensMode = lensMode
                    it.correctionConfig = correctionConfig
                },
                modifier = Modifier.fillMaxSize(),
            )

            FourLaneDirectionOverlay(
                displayMode = displayMode,
                interactionEnabled = state.active && state.firstFrame,
                zoom = zoom,
                onLaneTapped = { lane ->
                    displayMode = displayMode.toggleLane(lane)
                    zoom = previewView.resetViewport()
                },
                onTransformGesture = { zoomChange, panX, panY ->
                    zoom = previewView.applyViewportGesture(zoomChange, panX, panY)
                },
            )

            if (state.error != null) {
                Text(
                    Utils.t("Preview unavailable", "预览暂不可用"),
                    color = Color.White,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .background(Color(0xC0000000), RoundedCornerShape(8.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun StaticLaneGrid(
    modifier: Modifier = Modifier,
) {
    Card(
        modifier.aspectRatio(FOUR_GRID_ASPECT_RATIO),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF121212)),
        ) {
            FourLaneDirectionOverlay()
        }
    }
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Box(
        Modifier
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            text,
            color = color,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    else -> "$bytes B"
}

private fun formatEstimatedMinutes(minutes: Long?): String = when {
    minutes == null -> Utils.t("Unknown", "未知")
    minutes >= 60L -> Utils.t("${minutes / 60} h ${minutes % 60} min", "${minutes / 60} 小时 ${minutes % 60} 分钟")
    else -> Utils.t("$minutes min", "$minutes 分钟")
}

private fun formatSegmentDuration(seconds: Int): String = when {
    seconds >= 60 && seconds % 60 == 0 -> Utils.t("${seconds / 60} min", "${seconds / 60} 分钟")
    else -> Utils.t("$seconds sec", "$seconds 秒")
}
