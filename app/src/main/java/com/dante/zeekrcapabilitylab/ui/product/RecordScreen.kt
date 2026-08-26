package com.dante.zeekrcapabilitylab.ui.product

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.util.Size
import android.view.TextureView
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
import androidx.compose.runtime.rememberUpdatedState
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
import com.dante.zeekrcapabilitylab.product.SurroundPreviewLifecyclePolicy
import com.dante.zeekrcapabilitylab.product.ProductRecorderConfigFactory
import com.dante.zeekrcapabilitylab.product.SettingsStore
import com.dante.zeekrcapabilitylab.product.AppLanguage
import com.dante.zeekrcapabilitylab.product.FourLaneLensMode
import com.dante.zeekrcapabilitylab.service.CameraRecordingService
import com.dante.zeekrcapabilitylab.service.recorder.RecorderCommandPolicy
import com.dante.zeekrcapabilitylab.service.recorder.RecorderConfig
import com.dante.zeekrcapabilitylab.service.recorder.RecorderStatus
import com.dante.zeekrcapabilitylab.service.recorder.RecordingLayoutKind
import com.dante.zeekrcapabilitylab.service.recorder.RecordingSourceRole
import com.dante.zeekrcapabilitylab.service.recorder.SessionSourceSnapshot
import com.dante.zeekrcapabilitylab.util.Utils
import com.dante.zeekrcapabilitylab.ZeekrApp
import com.dante.zeekrcapabilitylab.data.Categories
import com.dante.zeekrcapabilitylab.event.EventLogger
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val CONFIG_TIMEOUT_MS = 8_000L
private const val DEFAULT_ESTIMATED_BITRATE_BPS = 28_000_000L
private const val FOUR_GRID_ASPECT_RATIO = 1f

/**
 * Recorder config is computed only for an explicit user start. It is never
 * computed directly during composition.
 */
private sealed interface RecordConfigState {
    data object Idle : RecordConfigState
    data object Loading : RecordConfigState
    data class Ready(val config: RecorderConfig) : RecordConfigState
    data class Failed(val reason: String) : RecordConfigState
}

private data class DiskStats(val freeBytes: Long)
private data class ConfigLookup(val config: RecorderConfig?)

@Composable
fun RecordScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recorderState by CameraRecordingService.state.collectAsState()
    val appForeground by ZeekrApp.isForeground.collectAsState()
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
    // Deliberately not persisted: every fresh app process returns to 360°.
    var selectedSourceRole by remember { mutableStateOf(RecordingSourceRole.SURROUND) }
    var pendingWarningRole by remember { mutableStateOf<RecordingSourceRole?>(null) }
    var surroundPreviewGeneration by remember { mutableStateOf(0) }
    var previousAppForeground by remember { mutableStateOf(appForeground) }

    val resolvedIdleSource by produceState<SessionSourceSnapshot?>(
        initialValue = null,
        key1 = selectedSourceRole,
        key2 = cameraPermission,
    ) {
        value = if (cameraPermission) {
            withContext(Dispatchers.IO) {
                ProductRecorderConfigFactory.resolveSource(context, selectedSourceRole)
            }
        } else {
            null
        }
    }

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
    val activeSourceRole = if (recordingActive) {
        recorderState.sourceRole ?: selectedSourceRole
    } else {
        selectedSourceRole
    }
    val serviceRunning = CameraRecordingService.isRunning()
    val latestRecorderState by rememberUpdatedState(recorderState)
    val latestAppForeground by rememberUpdatedState(appForeground)

    val attachReplacementPreview: () -> Unit = {
        val current = latestRecorderState
        val profile = current.profile
        val foregroundAllowed = current.sourceRole != RecordingSourceRole.SURROUND || latestAppForeground
        if (foregroundAllowed && current.status == RecorderStatus.RECORDING &&
            profile != null && settings.previewWhileRecordingEnabled
        ) {
            val surface = previewController.acquireRecorderPreviewSurface(
                Size(profile.size.width, profile.size.height),
            )
            if (surface != null) CameraRecordingService.replacePreviewSurface(surface)
        }
    }

    LaunchedEffect(appForeground) {
        if (previousAppForeground && !appForeground) {
            val decision = SurroundPreviewLifecyclePolicy.onAppBackground(
                sourceRole = activeSourceRole,
                recordingActive = recordingActive,
                currentGeneration = surroundPreviewGeneration,
            )
            if (decision.invalidateSurface) {
                if (decision.disableRecorderPreview) {
                    CameraRecordingService.setPreviewOutputEnabled(context, false)
                }
                if (decision.stopIdlePreview) previewController.stopAndAwait()
                previewController.clearRecorderPreviewHandoff()
                previewEnabled = false
                surroundPreviewGeneration = decision.nextGeneration
                EventLogger.logEvent(
                    Categories.LIFECYCLE,
                    "SURROUND_PREVIEW_BACKGROUND_STALE",
                    payload = mapOf(
                        "surfaceGeneration" to surroundPreviewGeneration.toString(),
                        "recorder" to recorderState.status,
                        "source" to activeSourceRole.name,
                    ),
                )
            }
        } else if (!previousAppForeground && appForeground && activeSourceRole == RecordingSourceRole.SURROUND) {
            EventLogger.logEvent(
                Categories.LIFECYCLE,
                "SURROUND_PREVIEW_FOREGROUND_REBUILD",
                payload = mapOf(
                    "surfaceGeneration" to surroundPreviewGeneration.toString(),
                    "recorder" to recorderState.status,
                    "profile" to (recorderState.profile?.key ?: "idle"),
                ),
            )
        }
        previousAppForeground = appForeground
    }

    DisposableEffect(previewController) {
        previewController.onRecorderPreviewSurfaceAvailable = attachReplacementPreview
        previewController.onRecorderPreviewSurfaceDestroyed = {
            CameraRecordingService.setPreviewOutputEnabled(context, false)
        }
        onDispose {
            CameraRecordingService.setPreviewOutputEnabled(context, false)
            previewController.release()
        }
    }

    LaunchedEffect(
        cameraPermission,
        recordingActive,
        resolvedIdleSource,
        recorderState.previewRequested,
        recorderState.previewFallbackUsed,
        appForeground,
        activeSourceRole,
    ) {
        when {
            !appForeground && activeSourceRole == RecordingSourceRole.SURROUND -> {
                previewEnabled = false
                if (!recordingActive) previewController.stopAndAwait()
            }
            recordingActive -> {
                val recorderPreviewVisible = recorderState.previewRequested &&
                    !recorderState.previewFallbackUsed
                previewEnabled = recorderPreviewVisible
                if (!recorderPreviewVisible) previewController.clearRecorderPreviewHandoff()
            }
            cameraPermission && !recordingActive &&
                resolvedIdleSource != null &&
                ProductHomeCameraPolicy.shouldAutoStartPreview(0L) &&
                ProductHomeCameraPolicy.cameraAccessAllowed(
                    ProductHomeCameraPolicy.TRIGGER_AUTO_PREVIEW,
                ) -> {
                previewController.clearRecorderPreviewHandoff()
                previewController.stopAndAwait()
                previewEnabled = true
                previewController.startPreview(resolvedIdleSource!!)
            }
            !recordingActive -> {
                previewEnabled = false
                previewController.stopAndAwait()
            }
        }
    }

    LaunchedEffect(recorderState.status, recorderState.profile, recorderState.previewActive, appForeground) {
        if (recorderState.status == RecorderStatus.RECORDING && !recorderState.previewActive) {
            attachReplacementPreview()
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
        RecorderStatus.WAITING_CAMERA -> Utils.t("Waiting for camera", "正在等待摄像头")
        RecorderStatus.RESUMING -> Utils.t("Recovering recording", "正在恢复录像")
        RecorderStatus.CAMERA_UNAVAILABLE -> Utils.t("Camera in use", "摄像头占用")
        RecorderStatus.ERROR -> Utils.t("Recording error", "录像异常")
        else -> Utils.t("Standby", "待机")
    }
    val recorderStatusColor = when (recorderState.status) {
        RecorderStatus.RECORDING -> Color(0xFFFF6E6E)
        RecorderStatus.STARTING,
        RecorderStatus.FINALIZING,
        RecorderStatus.WAITING_CAMERA,
        RecorderStatus.RESUMING,
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
                    val lookup = withTimeoutOrNull(CONFIG_TIMEOUT_MS) {
                        withContext(Dispatchers.IO) {
                            ConfigLookup(
                                ProductRecorderConfigFactory.create(context, selectedSourceRole),
                            )
                        }
                    }
                    val outcome = lookup?.config
                    configState = when {
                        lookup == null -> RecordConfigState.Failed(Utils.t("Configuration timed out. Please retry.", "配置计算超时，请重试"))
                        outcome == null -> RecordConfigState.Failed(
                            Utils.t(
                                "This source has no valid camera mapping. Check Camera mapping in Settings.",
                                "该录像源没有有效摄像头映射，请到设置中检查“摄像头映射”。",
                            ),
                        )
                        outcome.validate().isEmpty() -> RecordConfigState.Ready(outcome)
                        else -> RecordConfigState.Failed(
                            Utils.t("No valid recording configuration: ", "无可用的录制配置：") +
                                (outcome.validate().firstOrNull() ?: "invalid"),
                        )
                    }
                    val ready = configState as? RecordConfigState.Ready
                    if (ready != null) {
                        val recorderPreviewSurface = if (settings.previewWhileRecordingEnabled) {
                            // Initial handoff keeps the buffer size already
                            // proven by idle preview (including its fallback).
                            previewController.acquireRecorderPreviewSurface()
                        } else {
                            null
                        }
                        previewEnabled = recorderPreviewSurface != null
                        CameraRecordingService.start(context, ready.config, recorderPreviewSurface)
                    } else if (cameraPermission) {
                        resolvedIdleSource?.let { source ->
                            previewEnabled = true
                            previewController.startPreview(source)
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(cameraPermission) {
        if (!cameraPermission && !startupPermissionPrompted) {
            startupPermissionPrompted = true
            cameraLauncher.launch(Manifest.permission.CAMERA)
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
            settings = settings,
            layoutKind = if (recordingActive) recorderState.layoutKind else resolvedIdleSource?.layoutKind,
            sourceRole = activeSourceRole,
            appForeground = appForeground,
            surroundPreviewGeneration = surroundPreviewGeneration,
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
            RecordingSourceSelector(
                selected = if (recordingActive) {
                    recorderState.sourceRole ?: selectedSourceRole
                } else {
                    selectedSourceRole
                },
                enabled = !recordingActive && configState !is RecordConfigState.Loading,
                onSelect = { role ->
                    if (
                        role != RecordingSourceRole.SURROUND &&
                        !settings.sourceConflictWarningAcknowledged
                    ) {
                        pendingWarningRole = role
                    } else {
                        selectedSourceRole = role
                        configState = RecordConfigState.Idle
                    }
                },
            )
            if (!recordingActive && resolvedIdleSource == null && cameraPermission) {
                Text(
                    Utils.t(
                        "This source is not mapped to an available recording camera. Check Camera mapping in Settings.",
                        "该录像源没有映射到可用摄像头，请到设置中检查“摄像头映射”。",
                    ),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
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
                    when (recorderState.status) {
                        RecorderStatus.WAITING_CAMERA -> Utils.t(
                            "The selected camera is busy. This Session will resume automatically when it is released.",
                            "所选摄像头正被占用，释放后本次 Session 会自动继续。",
                        )
                        RecorderStatus.RESUMING -> Utils.t(
                            "Reopening the same camera and starting a new segment…",
                            "正在重新打开同一摄像头并创建新分段…",
                        )
                        else -> Utils.t(
                            "Writing segment ${recorderState.segmentNumber}",
                            "正在写入第 ${recorderState.segmentNumber} 段",
                        )
                    },
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

    pendingWarningRole?.let { role ->
        AlertDialog(
            onDismissRequest = { pendingWarningRole = null },
            title = { Text(Utils.t("Camera resource notice", "摄像头资源提示")) },
            text = {
                Text(
                    Utils.t(
                        "Cabin and IR recording may share camera resources with OEM cabin/rear-seat features. Those OEM features may be unavailable while recording. Stop this recording source before using them.",
                        "Cabin 与 IR 录像可能和原厂车内/后排摄像头共用资源。录像期间原厂相关功能可能不可用；使用原厂功能前请先停止录像。",
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    settings.acknowledgeSourceConflictWarning()
                    selectedSourceRole = role
                    configState = RecordConfigState.Idle
                    pendingWarningRole = null
                }) { Text(Utils.t("I understand", "我知道了")) }
            },
            dismissButton = {
                TextButton(onClick = { pendingWarningRole = null }) {
                    Text(Utils.t("Cancel", "取消"))
                }
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
    settings: SettingsStore,
    layoutKind: RecordingLayoutKind?,
    sourceRole: RecordingSourceRole,
    appForeground: Boolean,
    surroundPreviewGeneration: Int,
    modifier: Modifier = Modifier,
) {
    var lensMode by remember(settings) { mutableStateOf(settings.lensMode) }
    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        val previewSide = minOf(maxWidth, maxHeight)
        Box(Modifier.size(previewSide)) {
            // Keep a TextureView host composed while recording so returning to
            // this page can supply a replacement Surface. It may remain black
            // briefly while the camera thread safely rebuilds its session.
            val showLivePreview = if (recordingActive) {
                settings.previewWhileRecordingEnabled
            } else {
                previewEnabled
            } && (sourceRole != RecordingSourceRole.SURROUND || appForeground)
            if (showLivePreview && layoutKind == RecordingLayoutKind.SINGLE_V1) {
                SinglePreviewPanel(
                    controller = controller,
                    state = state,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (showLivePreview) {
                ManualPreviewPanel(
                    controller = controller,
                    state = state,
                    lensMode = lensMode,
                    surfaceGeneration = surroundPreviewGeneration,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (layoutKind == RecordingLayoutKind.SINGLE_V1) {
                StaticSinglePreview(modifier = Modifier.fillMaxSize())
            } else {
                StaticLaneGrid(modifier = Modifier.fillMaxSize())
            }
            if (layoutKind != RecordingLayoutKind.SINGLE_V1) {
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
}

@Composable
private fun RecordingSourceSelector(
    selected: RecordingSourceRole,
    enabled: Boolean,
    onSelect: (RecordingSourceRole) -> Unit,
) {
    Text(
        Utils.t("Recording source", "录像源"),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(7.dp))
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        RecordingSourceRole.entries.forEach { role ->
            OutlinedButton(
                onClick = { onSelect(role) },
                enabled = enabled,
                modifier = Modifier.weight(1f),
            ) {
                val label = when (role) {
                    RecordingSourceRole.SURROUND -> "360°"
                    RecordingSourceRole.CABIN -> "Cabin"
                    RecordingSourceRole.IR -> "IR"
                }
                Text(if (role == selected) "$label ✓" else label)
            }
        }
    }
    if (!enabled) {
        Text(
            Utils.t("Stop recording before changing the source.", "停止录像后才能切换录像源。"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SinglePreviewPanel(
    controller: SafeManualPreviewController,
    state: ManualPreviewState,
    modifier: Modifier = Modifier,
) {
    Card(modifier.aspectRatio(16f / 9f)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { TextureView(it).also(controller::attach) },
                modifier = Modifier.fillMaxSize(),
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
private fun StaticSinglePreview(modifier: Modifier = Modifier) {
    Card(modifier.aspectRatio(16f / 9f)) {
        Box(Modifier.fillMaxSize().background(Color(0xFF121212)))
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
    surfaceGeneration: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val correctionConfig = SettingsStore.get(context).fisheyeCorrection
    val previewView = remember(controller, surfaceGeneration) { FourLaneTextureContainer(context) }
    var displayMode by remember(controller, surfaceGeneration) { mutableStateOf(FourLaneDisplayMode.FOUR_GRID) }
    var zoom by remember(controller, surfaceGeneration) { mutableStateOf(1f) }

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
