package com.dante.zeekrcapabilitylab.ui.product

import android.Manifest
import android.content.Intent
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
import androidx.compose.material3.Slider
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dante.zeekrcapabilitylab.product.ProductHomeCameraPolicy
import com.dante.zeekrcapabilitylab.product.HomePreviewRecoveryPolicy
import com.dante.zeekrcapabilitylab.product.HomePreviewRetryPolicy
import com.dante.zeekrcapabilitylab.product.SurroundPreviewLifecyclePolicy
import com.dante.zeekrcapabilitylab.product.ProductRecorderConfigFactory
import com.dante.zeekrcapabilitylab.product.SettingsStore
import com.dante.zeekrcapabilitylab.product.AppLanguage
import com.dante.zeekrcapabilitylab.product.RecordingCapacity
import com.dante.zeekrcapabilitylab.product.RecordingCapacityMath
import com.dante.zeekrcapabilitylab.product.RecordingCapacityReader
import com.dante.zeekrcapabilitylab.service.recorder.RecordingStorageKind
import com.dante.zeekrcapabilitylab.product.FourLaneLensMode
import com.dante.zeekrcapabilitylab.service.CameraRecordingService
import com.dante.zeekrcapabilitylab.service.recorder.RecorderCommandPolicy
import com.dante.zeekrcapabilitylab.service.recorder.CaptureCleanupRuntime
import com.dante.zeekrcapabilitylab.service.recorder.RecorderConfig
import com.dante.zeekrcapabilitylab.service.recorder.RecorderStatus
import com.dante.zeekrcapabilitylab.service.recorder.RecordingLayoutKind
import com.dante.zeekrcapabilitylab.service.recorder.RecordingMode
import com.dante.zeekrcapabilitylab.service.recorder.RecordingSourceRole
import com.dante.zeekrcapabilitylab.service.recorder.SessionSourceSnapshot
import com.dante.zeekrcapabilitylab.service.recorder.TimeLapsePolicy
import com.dante.zeekrcapabilitylab.util.Utils
import com.dante.zeekrcapabilitylab.ZeekrApp
import com.dante.zeekrcapabilitylab.BuildConfig
import com.dante.zeekrcapabilitylab.data.Categories
import com.dante.zeekrcapabilitylab.event.EventLogger
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

private const val CONFIG_TIMEOUT_MS = 8_000L
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

private data class ConfigLookup(val config: RecorderConfig?)

@Composable
fun RecordScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recorderState by CameraRecordingService.state.collectAsState()
    val cleanupOwners by CaptureCleanupRuntime.pendingOwners.collectAsState()
    val modeSwitchProgress by CameraRecordingService.modeSwitchProgress.collectAsState()
    val auxiliary by com.dante.zeekrcapabilitylab.enhancement.CameraWorkCoordinator.state.collectAsState()
    val floatingMirror by com.dante.zeekrcapabilitylab.mirror.FloatingMirrorService.state.collectAsState()
    val floatingChoice by com.dante.zeekrcapabilitylab.mirror.FloatingMirrorService.idleSelection.collectAsState()
    val mirrorHome by com.dante.zeekrcapabilitylab.mirror.MirrorPreviewRuntime.homeControls.collectAsState()
    val appForeground by ZeekrApp.isForeground.collectAsState()
    val languageMode by AppLanguage.mode.collectAsState()
    val settings = remember(languageMode) { SettingsStore.get(context) }
    val previewController = remember { SafeManualPreviewController(context.applicationContext) }
    val mirrorEntry = remember { com.dante.zeekrcapabilitylab.mirror.MirrorAppEntryGate() }
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
    var awaitingRecorderStart by remember { mutableStateOf(false) }
    var previewEnabled by remember { mutableStateOf(false) }
    var idlePreviewRequest by remember { mutableStateOf<SessionSourceSnapshot?>(null) }
    var sourceHandoffPending by remember { mutableStateOf(false) }
    var sourceHandoffBlocked by remember { mutableStateOf(false) }
    var sourceHandoffJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var startupPermissionPrompted by rememberSaveable { mutableStateOf(false) }
    // Deliberately not persisted: every fresh app process returns to 360°.
    var selectedSourceRole by remember { mutableStateOf(RecordingSourceRole.SURROUND) }
    // Deliberately not persisted: a fresh process always returns to normal recording.
    var selectedRecordingMode by remember { mutableStateOf(RecordingMode.NORMAL) }
    var selectedTimeLapseMultiplier by remember { mutableStateOf(settings.timeLapseMultiplier) }
    var pendingWarningRole by remember { mutableStateOf<RecordingSourceRole?>(null) }
    var surroundPreviewGeneration by remember { mutableStateOf(0) }
    var previousAppForeground by remember { mutableStateOf(appForeground) }

    val resolvedIdleSource by produceState<SessionSourceSnapshot?>(
        initialValue = null,
        key1 = selectedSourceRole,
        key2 = cameraPermission,
    ) {
        value = null // A previous source's metadata must not start a new preview during lookup.
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
    val independentMirrorActive = auxiliary.active && auxiliary.kind == "PREVIEW"
    val activeSourceRole = com.dante.zeekrcapabilitylab.product.HomePreviewSelection.displayedSource(
        selectedSourceRole, recordingActive, recorderState.sourceRole,
        independentMirrorActive || floatingMirror.active, mirrorHome.sourceRole)
    val activeRecordingMode = if (recordingActive) {
        recorderState.recordingMode
    } else if (floatingMirror.active) {
        floatingChoice.mode
    } else {
        selectedRecordingMode
    }
    val activeTimeLapseMultiplier = if (recordingActive) {
        recorderState.timeLapseMultiplier
    } else if (floatingMirror.active && floatingChoice.mode == RecordingMode.TIME_LAPSE) {
        floatingChoice.multiplier
    } else {
        selectedTimeLapseMultiplier
    }
    val serviceRunning = CameraRecordingService.isRunning()
    val latestRecorderState by rememberUpdatedState(recorderState)
    val latestAppForeground by rememberUpdatedState(appForeground)
    val previewAdmission by rememberUpdatedState(HomePreviewRecoveryPolicy.State(
        foreground = appForeground,
        permission = cameraPermission,
        sourceReady = resolvedIdleSource?.sourceRole == selectedSourceRole,
        recorderActive = recordingActive,
        recorderServiceRunning = serviceRunning,
        otherOwnerActive = auxiliary.active || floatingMirror.active,
        handoffPending = sourceHandoffPending || sourceHandoffBlocked || awaitingRecorderStart,
        cleanupPending = recorderState.cleanupPending,
        cleanupUnconfirmed = recorderState.cleanupUnconfirmed,
        cleanupOwners = cleanupOwners,
    ))

    val attachReplacementPreview: () -> Unit = {
        val current = latestRecorderState
        val profile = current.profile
        val foregroundAllowed = current.sourceRole != RecordingSourceRole.SURROUND || latestAppForeground
        if (!current.mirrorPreviewManaged && foregroundAllowed && current.status == RecorderStatus.RECORDING &&
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
                if (decision.disableRecorderPreview && !latestRecorderState.mirrorPreviewManaged) {
                    CameraRecordingService.setPreviewOutputEnabled(context, false)
                }
                if (decision.stopIdlePreview) previewController.stopPreview()
                previewController.clearRecorderPreviewHandoff()
                idlePreviewRequest = null
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
            if (!latestRecorderState.mirrorPreviewManaged) CameraRecordingService.setPreviewOutputEnabled(context, false)
        }
        onDispose {
            if (!latestRecorderState.mirrorPreviewManaged) CameraRecordingService.setPreviewOutputEnabled(context, false)
            previewController.release()
        }
    }

    LaunchedEffect(awaitingRecorderStart) {
        if (!awaitingRecorderStart) return@LaunchedEffect
        // FGS creation is asynchronous. A cleanup=0 emission must not admit
        // home preview between the explicit start command and service creation.
        val acknowledged = withTimeoutOrNull(CONFIG_TIMEOUT_MS) {
            while (!CameraRecordingService.isRunning() &&
                !RecorderCommandPolicy.isActive(CameraRecordingService.state.value.status)) kotlinx.coroutines.delay(50)
            true
        } == true
        awaitingRecorderStart = false
        if (!acknowledged) configState = RecordConfigState.Failed(
            Utils.t("Recording start is not confirmed. Check its status before retrying.", "尚未确认录像启动，请检查状态后重试。"))
    }

    LaunchedEffect(
        cameraPermission,
        recordingActive,
        recorderState.status,
        recorderState.cleanupPending,
        recorderState.cleanupUnconfirmed,
        serviceRunning,
        cleanupOwners,
        resolvedIdleSource,
        recorderState.previewRequested,
        recorderState.previewFallbackUsed,
        appForeground,
        activeSourceRole,
        auxiliary.active,
        floatingMirror.active,
        sourceHandoffPending,
        sourceHandoffBlocked,
        awaitingRecorderStart,
    ) {
        // A manual start owns its own close/config/surface handoff. Cleanup-flow
        // recomposition must not replace that intent with an idle-preview request.
        if (configState is RecordConfigState.Loading || awaitingRecorderStart) return@LaunchedEffect
        if (sourceHandoffPending || sourceHandoffBlocked) {
            idlePreviewRequest = null
            previewEnabled = false
            return@LaunchedEffect
        }
        if (mirrorEntry.request(
                foreground = appForeground, enabled = settings.mirrorPreviewEnabled,
                resident = com.dante.zeekrcapabilitylab.mirror.MirrorPresentation(context).resident,
                cameraPermission = cameraPermission, overlayPermission = android.provider.Settings.canDrawOverlays(context),
                calibratedRear = settings.mirrorRearLane in 1..4,
                surroundSelected = selectedSourceRole == RecordingSourceRole.SURROUND,
                recordingActive = recordingActive || serviceRunning,
                auxiliaryActive = auxiliary.active, controlsActive = floatingMirror.active)) {
            previewEnabled = false
            previewController.clearRecorderPreviewHandoff()
            val accepted = com.dante.zeekrcapabilitylab.mirror.FloatingMirrorService.open(context)
            EventLogger.logEvent(Categories.SYSTEM, "RECORDER_MIRROR_APP_ENTRY",
                payload = mapOf("reason" to if (accepted) "PREVIEW_REQUESTED" else "PREVIEW_REJECTED"))
            if (accepted) {
                // Arm visible controls while the Activity is foreground. The service's
                // handoff gate still waits for this producer's real close acknowledgement.
                idlePreviewRequest = null
                previewController.stopPreview()
                return@LaunchedEffect
            }
        }
        val admissionDecision = HomePreviewRecoveryPolicy.decide(previewAdmission.copy(
            ownPreviewRequested = idlePreviewRequest != null && idlePreviewRequest == resolvedIdleSource))
        when {
            auxiliary.active || floatingMirror.active -> {
                idlePreviewRequest = null
                previewEnabled = false
                previewController.stopPreview()
            }
            !appForeground -> {
                idlePreviewRequest = null
                previewEnabled = false
                if (!recordingActive) previewController.stopPreview()
            }
            recordingActive -> {
                idlePreviewRequest = null
                val recorderPreviewVisible = recorderState.previewRequested &&
                    !recorderState.previewFallbackUsed
                previewEnabled = recorderPreviewVisible
                if (!recorderPreviewVisible) previewController.clearRecorderPreviewHandoff()
            }
            admissionDecision == HomePreviewRecoveryPolicy.Decision.KEEP_OWN_PREVIEW -> Unit
            admissionDecision != HomePreviewRecoveryPolicy.Decision.STOP_OWN_PREVIEW &&
                ProductHomeCameraPolicy.shouldAutoStartPreview(0L) &&
                ProductHomeCameraPolicy.cameraAccessAllowed(
                    ProductHomeCameraPolicy.TRIGGER_AUTO_PREVIEW,
                ) -> {
                previewController.clearRecorderPreviewHandoff()
                val waitStarted = android.os.SystemClock.elapsedRealtime()
                val released = previewController.stopAndAwait(HomePreviewRecoveryPolicy.RELEASE_WAIT_MS)
                // The waiter may outlive a recorder stop, a source choice or a
                // permission change. Only current facts authorize a new preview.
                val currentRecorder = CameraRecordingService.state.value
                val currentAdmission = previewAdmission.copy(
                    foreground = ZeekrApp.isForeground.value,
                    permission = context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
                    sourceReady = resolvedIdleSource?.sourceRole == selectedSourceRole,
                    recorderActive = RecorderCommandPolicy.isActive(currentRecorder.status),
                    recorderServiceRunning = CameraRecordingService.isRunning(),
                    otherOwnerActive = com.dante.zeekrcapabilitylab.enhancement.CameraWorkCoordinator.state.value.active ||
                        com.dante.zeekrcapabilitylab.mirror.FloatingMirrorService.active(),
                    handoffPending = sourceHandoffPending || sourceHandoffBlocked || configState is RecordConfigState.Loading ||
                        awaitingRecorderStart,
                    cleanupPending = currentRecorder.cleanupPending,
                    cleanupUnconfirmed = currentRecorder.cleanupUnconfirmed,
                    cleanupOwners = CaptureCleanupRuntime.pendingOwners.value,
                    nativeIdle = com.dante.zeekrcapabilitylab.sentry.CanaryCameraInterlock.normalIdle(),
                )
                val waitedMs = android.os.SystemClock.elapsedRealtime() - waitStarted
                val decision = HomePreviewRecoveryPolicy.decide(currentAdmission, waitedMs)
                if (released && decision == HomePreviewRecoveryPolicy.Decision.RESTORE) {
                    idlePreviewRequest = resolvedIdleSource
                    previewEnabled = true
                    previewController.startPreview(resolvedIdleSource!!)
                    EventLogger.logEvent(Categories.SYSTEM, "PRODUCT_HOME_PREVIEW_RESTORED",
                        payload = mapOf("reason" to "RELEASE_CONFIRMED", "target" to "PREVIEW",
                            "waitedElapsedMs" to waitedMs.toString()))
                } else {
                    previewEnabled = false
                    if (!released && decision != HomePreviewRecoveryPolicy.Decision.STOP_OWN_PREVIEW)
                        EventLogger.logEvent(Categories.SYSTEM, "PRODUCT_HOME_PREVIEW_RELEASE_TIMEOUT",
                            payload = mapOf("reason" to "RELEASE_UNCONFIRMED", "target" to "PREVIEW",
                                "waitedElapsedMs" to waitedMs.toString()))
                }
            }
            !recordingActive -> {
                idlePreviewRequest = null
                previewEnabled = false
                previewController.stopPreview()
            }
        }
    }

    LaunchedEffect(recorderState.status, recorderState.profile, recorderState.previewActive, appForeground) {
        if (recorderState.status == RecorderStatus.RECORDING && !recorderState.previewActive) {
            attachReplacementPreview()
        }
    }

    LaunchedEffect(recorderState.recordingSessionId, recorderState.recordingMode, recorderState.timeLapseMultiplier) {
        if (RecorderCommandPolicy.isActive(recorderState.status)) {
            selectedRecordingMode = recorderState.recordingMode
            if (recorderState.recordingMode == RecordingMode.TIME_LAPSE) selectedTimeLapseMultiplier = recorderState.timeLapseMultiplier
        }
    }

    LaunchedEffect(mirrorHome.sourceRole, floatingMirror.active) {
        if (floatingMirror.active && !sourceHandoffPending) mirrorHome.sourceRole?.let { selectedSourceRole = it }
    }

    val selectSource: (RecordingSourceRole) -> Unit = select@{ role ->
        if (!com.dante.zeekrcapabilitylab.product.HomePreviewSelection.canSelect(
                RecorderCommandPolicy.isActive(CameraRecordingService.state.value.status), CameraRecordingService.isRunning(),
                com.dante.zeekrcapabilitylab.enhancement.CameraWorkCoordinator.state.value,
                com.dante.zeekrcapabilitylab.mirror.FloatingMirrorService.active(), floatingMirror.busy,
                sourceHandoffPending || modeSwitchProgress != null || configState is RecordConfigState.Loading || awaitingRecorderStart)) return@select
        if (role == activeSourceRole && !sourceHandoffBlocked) return@select
        idlePreviewRequest = null
        sourceHandoffBlocked = false
        selectedSourceRole = role
        configState = RecordConfigState.Idle
        if (floatingMirror.active && role != RecordingSourceRole.IR) {
            // Reuse the already-tested Cabin / surround owner and its release acknowledgements.
            if (!com.dante.zeekrcapabilitylab.mirror.FloatingMirrorService.selectPreviewSource(role)) {
                configState = RecordConfigState.Failed(Utils.t("Preview is busy. Please retry.", "预览正在切换，请稍后重试。"))
            }
        } else {
            // IR remains on the existing single-source home preview. Only an explicit click
            // can transfer ownership; a timeout never grants permission to open another camera.
            sourceHandoffPending = true
            com.dante.zeekrcapabilitylab.mirror.FloatingMirrorService.close()
            sourceHandoffJob = scope.launch {
                val gate = com.dante.zeekrcapabilitylab.mirror.MirrorHandoffGate()
                val request = checkNotNull(gate.begin(com.dante.zeekrcapabilitylab.mirror.MirrorHandoffGate.Target.PREVIEW,
                    android.os.SystemClock.elapsedRealtime()))
                val stopped = previewController.stopAndAwait()
                var decision = com.dante.zeekrcapabilitylab.mirror.MirrorHandoffGate.Decision.WAIT
                while (decision == com.dante.zeekrcapabilitylab.mirror.MirrorHandoffGate.Decision.WAIT) {
                    val work = com.dante.zeekrcapabilitylab.enhancement.CameraWorkCoordinator.state.value
                    decision = gate.poll(request.token, android.os.SystemClock.elapsedRealtime(),
                        allowed = stopped && latestAppForeground && work.error == null,
                        recorderGone = !CameraRecordingService.isRunning(),
                        previewGone = !com.dante.zeekrcapabilitylab.mirror.StandaloneMirrorService.isRunning(),
                        auxiliaryGone = !work.active,
                        nativeIdle = com.dante.zeekrcapabilitylab.sentry.CanaryCameraInterlock.normalIdle(),
                        glIdle = com.dante.zeekrcapabilitylab.mirror.MirrorGlPreview.isIdle())
                    if (decision == com.dante.zeekrcapabilitylab.mirror.MirrorHandoffGate.Decision.WAIT) kotlinx.coroutines.delay(50)
                }
                sourceHandoffBlocked = decision != com.dante.zeekrcapabilitylab.mirror.MirrorHandoffGate.Decision.START
                sourceHandoffPending = false
                if (sourceHandoffBlocked) configState = RecordConfigState.Failed(
                    Utils.t("Camera release is unconfirmed. Please retry after it finishes.", "相机释放尚未确认，请待释放完成后重试。"))
                else if (role == RecordingSourceRole.SURROUND && settings.mirrorPreviewEnabled &&
                    com.dante.zeekrcapabilitylab.mirror.MirrorPresentation(context).resident) {
                    com.dante.zeekrcapabilitylab.mirror.FloatingMirrorService.open(context)
                }
            }
        }
    }

    val selectedCabinCount = if (activeSourceRole == RecordingSourceRole.SURROUND && activeRecordingMode == RecordingMode.NORMAL)
        listOf(settings.recordFrontCabin, settings.recordRearCabin).count { it } else 0
    // Reuse the existing foreground/start/storage-change cadence. The second destination
    // is measured on the same IO job; no background capacity poller is added.
    val capacities by produceState(initialValue = listOf(RecordingCapacity()), recordingActive,
        recorderState.activeStorageUuid, recorderState.activeStorageKind, appForeground, selectedCabinCount) {
        if (appForeground) value = withContext(Dispatchers.IO) {
            val primary = runCatching { RecordingCapacityReader.read(context, recorderState, serviceRunning) }.getOrDefault(RecordingCapacity())
            buildList {
                add(primary)
                if (selectedCabinCount > 0 && primary.storage != RecordingStorageKind.INTERNAL) {
                    add(runCatching { RecordingCapacityReader.readInternal(context,
                        if (serviceRunning) recorderState.storageLimitBytes else settings.internalStorageLimitBytes) }
                        .getOrDefault(RecordingCapacity(RecordingStorageKind.INTERNAL)))
                }
            }
        }
    }
    val capacity = capacities.first()

    val readyConfig = (configState as? RecordConfigState.Ready)?.config
    val canStart = RecorderCommandPolicy.canStart(recorderState.status, serviceRunning) &&
        (!auxiliary.active || independentMirrorActive && floatingMirror.active) && !floatingMirror.busy && modeSwitchProgress == null &&
        configState !is RecordConfigState.Loading && !sourceHandoffPending && !awaitingRecorderStart
    val canStop = RecorderCommandPolicy.canStop(recorderState.status, serviceRunning)
    val canBookmark = RecorderCommandPolicy.canBookmark(serviceRunning) &&
        activeRecordingMode == RecordingMode.NORMAL && recorderState.status != RecorderStatus.AWAKE_IDLE

    val requestedProfile = if (serviceRunning) recorderState.profile else readyConfig?.profile ?: resolvedIdleSource?.profile
    val estimatedBitrateBps = requestedProfile?.bitrateBps?.takeIf { it > 0 }?.toLong()
    val estimatedCapacityMultiplier = if (activeRecordingMode == RecordingMode.TIME_LAPSE) {
        activeTimeLapseMultiplier.toLong()
    } else {
        1L
    }
    val storageStatus = capacities.map { destination -> RecordingStorageStatus(
        label = when (destination.storage) {
            RecordingStorageKind.USB_MEDIASTORE -> "USB"
            RecordingStorageKind.INTERNAL -> Utils.t("Head unit", "车机内部")
            null -> Utils.t("Unknown storage", "存储位置未知")
        },
        freeSpace = destination.freeBytes?.let(::formatBytes) ?: Utils.t("Unknown", "未知"),
        estimatedMinutes = RecordingCapacityMath.minutes(destination.availableBytes,
            RecordingCapacityMath.bitrateForStorage(capacity.storage, estimatedBitrateBps, destination.storage, selectedCabinCount),
            estimatedCapacityMultiplier.toInt()),
    ) }
    val recordingElapsed = (recorderState.sessionStartedAtEpochMs ?: recorderState.segmentStartedAtEpochMs)
        ?.let { now - it }
    val guardStoppedWithError = false
    val recorderStatusText = if (guardStoppedWithError) Utils.t("Sentry stopped", "哨兵已停止") else when (recorderState.status) {
        RecorderStatus.AWAKE_IDLE -> Utils.t("Awake · recording paused", "已停录 · 保持运行")
        RecorderStatus.SENTRY_LISTENING -> Utils.t("Sentry RAM listening", "哨兵 RAM 监听中")
        RecorderStatus.STARTING -> Utils.t("Preparing", "正在准备")
        RecorderStatus.RECORDING -> if (activeRecordingMode == RecordingMode.TIME_LAPSE) {
            Utils.t(
                "Time-lapse {0}× · {1}", "延时摄影 {0}× · {1}", activeTimeLapseMultiplier, Utils.formatDuration(recordingElapsed))
        } else {
            Utils.t("Recording {0}", "录像中 {0}", Utils.formatDuration(recordingElapsed))
        }
        RecorderStatus.FINALIZING -> Utils.t("Saving", "正在保存")
        RecorderStatus.WAITING_CAMERA -> Utils.t("Waiting for camera", "正在等待摄像头")
        RecorderStatus.RESUMING -> Utils.t("Recovering recording", "正在恢复录像")
        RecorderStatus.CAMERA_UNAVAILABLE -> Utils.t("Camera in use", "摄像头占用")
        RecorderStatus.ERROR -> Utils.t("Recording error", "录像异常")
        else -> Utils.t("Standby", "待机")
    }
    val recorderStatusColor = if (guardStoppedWithError) MaterialTheme.colorScheme.error else when (recorderState.status) {
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
            configState is RecordConfigState.Loading || awaitingRecorderStart -> Unit
            floatingMirror.active -> {
                com.dante.zeekrcapabilitylab.mirror.FloatingMirrorService.startRecording()
            }
            else -> {
                val requestedMode = selectedRecordingMode
                val requestedMultiplier = selectedTimeLapseMultiplier
                idlePreviewRequest = null
                configState = RecordConfigState.Loading
                scope.launch {
                    if (!previewController.stopAndAwait()) {
                        configState = RecordConfigState.Failed(Utils.t("Camera release is unconfirmed. A new recording was not started; copy diagnostics.", "相机释放尚未确认，未开始新录像；请复制诊断"))
                        return@launch
                    }
                    val lookup = withTimeoutOrNull(CONFIG_TIMEOUT_MS) {
                        withContext(Dispatchers.IO) {
                            ConfigLookup(
                                ProductRecorderConfigFactory.create(
                                    context = context,
                                    role = selectedSourceRole,
                                    recordingMode = requestedMode,
                                    timeLapseMultiplier = requestedMultiplier,
                                ),
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
                        val mirror = settings.mirrorPreviewEnabled && android.provider.Settings.canDrawOverlays(context) &&
                            com.dante.zeekrcapabilitylab.mirror.MirrorPreviewPolicy.supports(ready.config)
                        val recorderPreviewSurface = if (!mirror && settings.previewWhileRecordingEnabled) {
                            // Initial handoff keeps the buffer size already
                            // proven by idle preview (including its fallback).
                            previewController.acquireRecorderPreviewSurface()
                        } else {
                            null
                        }
                        previewEnabled = recorderPreviewSurface != null
                        awaitingRecorderStart = true
                        try {
                            CameraRecordingService.start(context, ready.config.copy(mirrorPreviewEnabled = mirror), recorderPreviewSurface)
                        } catch (failure: Exception) {
                            awaitingRecorderStart = false
                            runCatching { recorderPreviewSurface?.release() }
                            previewController.clearRecorderPreviewHandoff()
                            configState = RecordConfigState.Failed(Utils.t("Recording did not start. Please retry.", "录像未启动，请重试。"))
                            EventLogger.markError(Categories.SYSTEM, "RECORDER_START_DISPATCH_FAILED", "START_REJECTED", failure)
                        }
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

    fun previewRetryState(): HomePreviewRetryPolicy.State {
        val current = CameraRecordingService.state.value
        val mirror = com.dante.zeekrcapabilitylab.mirror.FloatingMirrorService.state.value
        val work = com.dante.zeekrcapabilitylab.enhancement.CameraWorkCoordinator.state.value
        return HomePreviewRetryPolicy.State(
            source = activeSourceRole,
            floatingActive = mirror.active, floatingFailed = mirror.error != null, floatingBusy = mirror.busy,
            foreground = ZeekrApp.isForeground.value,
            permission = context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
            recording = RecorderCommandPolicy.isActive(current.status), recorderRunning = CameraRecordingService.isRunning(),
            previewRunning = com.dante.zeekrcapabilitylab.mirror.StandaloneMirrorService.isRunning(),
            otherOwnerActive = work.active, otherOwnerFailed = work.error != null,
            transitionPending = sourceHandoffPending || awaitingRecorderStart || configState is RecordConfigState.Loading ||
                CameraRecordingService.modeSwitchProgress.value != null,
            cleanupPending = current.cleanupPending, cleanupUnconfirmed = current.cleanupUnconfirmed,
            cleanupOwners = CaptureCleanupRuntime.pendingOwners.value,
            nativeIdle = com.dante.zeekrcapabilitylab.sentry.CanaryCameraInterlock.normalIdle(),
            glIdle = com.dante.zeekrcapabilitylab.mirror.MirrorGlPreview.isIdle(),
        )
    }
    val retryState = previewRetryState()
    val showPreviewRetry = HomePreviewRetryPolicy.visible(retryState)
    // Native/GL ownership and Service.onDestroy have no observable flow. Their
    // final release can follow cleanup=0, so reuse the existing page clock to
    // resample only this visible failed-preview control. No retry is dispatched.
    val previewRetryEnabled = if (showPreviewRetry && appForeground) remember(now, retryState) {
        HomePreviewRetryPolicy.allowed(retryState)
    } else false

    Row(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalArrangement = DriverPaneArrangement(com.dante.zeekrcapabilitylab.mirror.MirrorPresentation(context).rightHandDrive),
    ) {
        HomePreviewPane(
            controller = previewController,
            state = previewState,
            previewEnabled = previewEnabled,
            recordingActive = recordingActive,
            settings = settings,
            layoutKind = when {
                recordingActive -> recorderState.layoutKind
                independentMirrorActive || floatingMirror.active -> if (activeSourceRole == RecordingSourceRole.SURROUND)
                    RecordingLayoutKind.FOUR_LANE_V1 else RecordingLayoutKind.SINGLE_V1
                else -> resolvedIdleSource?.layoutKind
            },
            sourceRole = activeSourceRole,
            appForeground = appForeground,
            surroundPreviewGeneration = surroundPreviewGeneration,
            mirrorManaged = (recorderState.mirrorPreviewManaged && recordingActive) || independentMirrorActive || (floatingMirror.active && !recordingActive),
            showRetry = showPreviewRetry,
            retryEnabled = previewRetryEnabled,
            onRetry = {
                val current = previewRetryState()
                if (HomePreviewRetryPolicy.allowed(current) &&
                    com.dante.zeekrcapabilitylab.mirror.FloatingMirrorService.selectPreviewSource(current.source)) {
                    sourceHandoffBlocked = false
                    configState = RecordConfigState.Idle
                }
            },
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
                StatusPill(if(auxiliary.active) {
                    if(auxiliary.kind == "MULTI") Utils.t("Two-camera test · 60 s", "两路录像测试 · 60 秒") else Utils.t("Preview only", "仅预览")
                } else recorderStatusText, if(auxiliary.active) MaterialTheme.colorScheme.tertiary else recorderStatusColor)
            }

            Spacer(Modifier.height(8.dp))
            QuickStartEntry()
            RecordingTelemetryLabel()
            RecordingVehicleTelemetryLabel()
            RecordingChannelStatus()
            Spacer(Modifier.height(8.dp))
            modeSwitchProgress?.let { progress ->
                Text(com.dante.zeekrcapabilitylab.service.RecordingModeOverlayMenu.progressLabel(progress))
                Spacer(Modifier.height(8.dp))
            }
            if (auxiliary.active) {
                Text(auxiliary.message.ifBlank { Utils.t("Preparing preview", "正在准备预览") })
                OutlinedButton(onClick = {
                    if (sourceHandoffPending) {
                        sourceHandoffJob?.cancel(); sourceHandoffPending = false; sourceHandoffBlocked = true
                    }
                    if(auxiliary.kind == "MULTI") com.dante.zeekrcapabilitylab.enhancement.ConcurrentRecordingService.stop()
                    else com.dante.zeekrcapabilitylab.mirror.StandaloneMirrorService.stop()
                }) {
                    Text(Utils.t("Stop", "停止"))
                }
            }
            if (sourceHandoffPending) Text(Utils.t("Switching preview…", "正在切换预览…"))
            RecordingModeCard(
                mode = activeRecordingMode,
                multiplier = activeTimeLapseMultiplier,
                enabled = (!auxiliary.active || independentMirrorActive) && !floatingMirror.busy && !sourceHandoffPending && modeSwitchProgress == null && !recordingActive && configState !is RecordConfigState.Loading,
                onModeChanged = { mode ->
                    selectedRecordingMode = mode
                    if (floatingMirror.active) com.dante.zeekrcapabilitylab.mirror.FloatingMirrorService.selectMode(
                        com.dante.zeekrcapabilitylab.service.recorder.RecordingModeChoice(mode, if (mode == RecordingMode.NORMAL) 1 else settings.timeLapseMultiplier))
                    configState = RecordConfigState.Idle
                },
                onMultiplierChanged = { multiplier ->
                    selectedTimeLapseMultiplier = multiplier
                    settings.setTimeLapseMultiplier(multiplier)
                    if (floatingMirror.active) com.dante.zeekrcapabilitylab.mirror.FloatingMirrorService.selectMode(
                        com.dante.zeekrcapabilitylab.service.recorder.RecordingModeChoice(RecordingMode.TIME_LAPSE, multiplier))
                    configState = RecordConfigState.Idle
                },
            )
            Spacer(Modifier.height(14.dp))
            RecordingSourceSelector(
                selected = activeSourceRole,
                enabled = com.dante.zeekrcapabilitylab.product.HomePreviewSelection.canSelect(
                    recordingActive, serviceRunning, auxiliary, floatingMirror.active, floatingMirror.busy,
                    sourceHandoffPending || modeSwitchProgress != null || configState is RecordConfigState.Loading || awaitingRecorderStart),
                onSelect = { role ->
                    if (
                        role != RecordingSourceRole.SURROUND &&
                        !settings.sourceConflictWarningAcknowledged
                    ) {
                        pendingWarningRole = role
                    } else {
                        selectSource(role)
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
            ProductInfoRow(Utils.t("Storage destination", "录像存储位置"), when (capacity.storage) {
                RecordingStorageKind.USB_MEDIASTORE -> "USB"
                RecordingStorageKind.INTERNAL -> Utils.t("Head unit", "车机")
                null -> Utils.t("Unknown", "未知")
            })
            Spacer(Modifier.height(8.dp))

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
                        configState is RecordConfigState.Loading || awaitingRecorderStart -> Utils.t("Preparing…", "准备中…")
                        recordingActive -> Utils.t("Recording", "录像中")
                        activeRecordingMode == RecordingMode.TIME_LAPSE ->
                            Utils.t("Start time-lapse", "开始延时摄影")
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
                Text(
                    if (activeRecordingMode == RecordingMode.TIME_LAPSE) {
                        Utils.t("Protect after stopping in Library", "停止后可在录像记录中保护")
                    } else {
                        Utils.t("Save emergency video", "保存紧急视频")
                    },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            ProductStatusCard(
                storageStatus = storageStatus,
                quality = requestedProfile?.label ?: Utils.t("Unknown", "未知"),
                segmentSeconds = if (activeRecordingMode == RecordingMode.TIME_LAPSE) {
                    TimeLapsePolicy.SAFETY_CHUNK_SECONDS
                } else {
                    settings.segmentSeconds
                },
                autoCleanupEnabled = settings.autoCleanupEnabled,
                timeLapse = activeRecordingMode == RecordingMode.TIME_LAPSE,
            )
            Spacer(Modifier.height(14.dp))

            if (recordingActive) {
                recorderState.incidentMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text(
                    when (recorderState.status) {
                        RecorderStatus.AWAKE_IDLE -> Utils.t("Camera closed; waiting for your return.", "相机已关闭，正在等待回车。")
                        RecorderStatus.SENTRY_LISTENING -> Utils.t("Keeping video in RAM; events are saved when triggered.", "正在保留 RAM 预录，触发后保存事件。")
                        RecorderStatus.STARTING, RecorderStatus.FINALIZING -> recorderState.message ?: recorderStatusText
                        RecorderStatus.WAITING_CAMERA -> Utils.t(
                            "The selected camera is busy. This Session will resume automatically when it is released.",
                            "所选摄像头正被占用，释放后本次 Session 会自动继续。",
                        )
                        RecorderStatus.RESUMING -> Utils.t(
                            "Reopening the same camera and starting a new segment…",
                            "正在重新打开同一摄像头并创建新分段…",
                        )
                        else -> Utils.t(
                            "Writing segment {0}", "正在写入第 {0} 段", recorderState.segmentNumber)
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
                    selectSource(role)
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
    mirrorManaged: Boolean,
    showRetry: Boolean,
    retryEnabled: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hudContext = LocalContext.current
    val hudPreferences = remember(hudContext) { hudContext.getSharedPreferences("drive-hud", 0) }
    var hudVisible by remember(hudPreferences) { mutableStateOf(hudPreferences.getBoolean("live-visible", true)) }
    DisposableEffect(hudPreferences) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { preferences, key ->
            if (key == "live-visible") hudVisible = preferences.getBoolean("live-visible", true)
        }
        hudPreferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { hudPreferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    var lensMode by remember(settings) { mutableStateOf(settings.lensMode) }
    val mirrorHome by com.dante.zeekrcapabilitylab.mirror.MirrorPreviewRuntime.homeControls.collectAsState()
    BackHandler(enabled = mirrorManaged && sourceRole == RecordingSourceRole.SURROUND && mirrorHome.displayMode.singleLane != null) {
        mirrorHome.displayMode.singleLane?.let(com.dante.zeekrcapabilitylab.mirror.MirrorPreviewRuntime::toggleHomeLane)
    }
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
            if (mirrorManaged) {
                val context = LocalContext.current
                val host = remember { com.dante.zeekrcapabilitylab.mirror.MirrorHomeHost(context) }
                DisposableEffect(host) { onDispose { com.dante.zeekrcapabilitylab.mirror.MirrorPreviewRuntime.detachHome(host) } }
                AndroidView(factory = { host }, modifier = Modifier.fillMaxSize())
                if (sourceRole == RecordingSourceRole.SURROUND) FourLaneDirectionOverlay(
                    labels = listOf("1", "2", "3", "4"),
                    displayMode = mirrorHome.displayMode,
                    interactionEnabled = mirrorHome.interactive,
                    zoom = mirrorHome.zoom,
                    onLaneTapped = com.dante.zeekrcapabilitylab.mirror.MirrorPreviewRuntime::toggleHomeLane,
                    onTransformGesture = com.dante.zeekrcapabilitylab.mirror.MirrorPreviewRuntime::transformHome,
                )
            } else if (showLivePreview && layoutKind == RecordingLayoutKind.SINGLE_V1) {
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
                        com.dante.zeekrcapabilitylab.mirror.MirrorPreviewRuntime.refreshHome()
                    },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                )
            }
            if (recordingActive && appForeground) LiveRecordingHud(hudVisible, {
                hudVisible = it; hudPreferences.edit().putBoolean("live-visible", it).apply()
            }, Modifier.align(Alignment.BottomStart).padding(8.dp).fillMaxWidth(if (previewSide < 450.dp) 1f else 0.72f),
                compact = previewSide < 450.dp)
            if (showRetry) HomePreviewRetryControl(retryEnabled, onRetry,
                Modifier.align(Alignment.BottomCenter).padding(16.dp))
        }
    }
}

@Composable
internal fun HomePreviewRetryControl(enabled: Boolean, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (enabled) Utils.t("Preview stopped. You can try again.", "预览已停止，可手动重试。")
                else Utils.t("Wait for recording and camera operations to finish before retrying.", "请等待录像与相机操作结束后再重试。"),
                style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onRetry, enabled = enabled, modifier = Modifier.testTag("home-preview-retry")) {
                Text(Utils.t("Retry preview", "重试预览"))
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
                    RecordingSourceRole.CABIN -> Utils.t("Cabin", "车内")
                    RecordingSourceRole.IR -> Utils.t("Infrared", "红外")
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
private fun RecordingModeCard(
    mode: RecordingMode,
    multiplier: Int,
    enabled: Boolean,
    onModeChanged: (RecordingMode) -> Unit,
    onMultiplierChanged: (Int) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                Utils.t("Recording mode", "录像模式"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(9.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RecordingMode.entries.forEach { option ->
                    OutlinedButton(
                        onClick = { onModeChanged(option) },
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    ) {
                        val label = when (option) {
                            RecordingMode.NORMAL -> Utils.t("Normal", "普通录像")
                            RecordingMode.TIME_LAPSE -> Utils.t("Time-lapse", "延时摄影")
                        }
                        Text(if (option == mode) "$label ✓" else label)
                    }
                }
            }
            if (mode == RecordingMode.TIME_LAPSE) {
                Spacer(Modifier.height(12.dp))
                val index = TimeLapsePolicy.MULTIPLIERS.indexOf(multiplier).coerceAtLeast(0)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(Utils.t("Speed", "倍率"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Text("${TimeLapsePolicy.MULTIPLIERS[index]}×", fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = index.toFloat(),
                    onValueChange = { value ->
                        val selected = value.roundToInt().coerceIn(TimeLapsePolicy.MULTIPLIERS.indices)
                        onMultiplierChanged(TimeLapsePolicy.MULTIPLIERS[selected])
                    },
                    valueRange = 0f..TimeLapsePolicy.MULTIPLIERS.lastIndex.toFloat(),
                    steps = TimeLapsePolicy.MULTIPLIERS.size - 2,
                    enabled = enabled,
                )
                Text(
                    Utils.t(
                        "One Start is one Session. A safety file is finalized every 5 minutes until Stop, vehicle-away termination, or an unrecoverable camera error.",
                        "一次开始就是一条 Session。底层每 5 分钟安全封存一次，直到停止、离车终止或摄像头发生不可恢复故障。",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
    storageStatus: List<RecordingStorageStatus>,
    quality: String,
    segmentSeconds: Int,
    autoCleanupEnabled: Boolean,
    timeLapse: Boolean,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                Utils.t("Status", "状态信息"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(10.dp))
            storageStatus.forEach { destination ->
                ProductInfoRow(Utils.t("Free space · {0}", "可用空间 · {0}", destination.label), destination.freeSpace)
                ProductInfoRow(Utils.t("Before cleanup · {0}", "清理前预计可录 · {0}", destination.label), formatEstimatedMinutes(destination.estimatedMinutes))
            }
            ProductInfoRow(Utils.t("Requested quality", "当前请求画质"), quality)
            Text(Utils.t("Each estimate includes the selected channels on that storage, its quota and reserved space. Space is sampled when opening this screen or changing recording state. Cleanup may extend recording; actual file size varies.", "分别按各存储上的所选通道总码率、配额及保留空间估算；打开此页或录像状态改变时读取容量。清理旧录像后可继续录制，实际文件大小会有变化。"),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ProductInfoRow(
                if (timeLapse) Utils.t("Safety chunk", "安全分段") else Utils.t("Segment length", "分段时长"),
                formatSegmentDuration(segmentSeconds),
            )
            ProductInfoRow(Utils.t("Automatic cleanup", "自动清理"), if (autoCleanupEnabled) Utils.t("On", "已开启") else Utils.t("Off", "已关闭"))
        }
    }
}

private data class RecordingStorageStatus(val label: String, val freeSpace: String, val estimatedMinutes: Long?)

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
    minutes >= 60L -> Utils.t("{0} h {1} min", "{0} 小时 {1} 分钟", minutes / 60, minutes % 60)
    else -> Utils.t("{0} min", "{0} 分钟", minutes)
}

private fun formatSegmentDuration(seconds: Int): String = when {
    seconds >= 60 && seconds % 60 == 0 -> Utils.t("{0} min", "{0} 分钟", seconds / 60)
    else -> Utils.t("{0} sec", "{0} 秒", seconds)
}
