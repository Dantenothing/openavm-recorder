package com.dante.zeekrcapabilitylab.mirror

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import android.provider.Settings
import android.view.View
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dante.zeekrcapabilitylab.MainActivity
import com.dante.zeekrcapabilitylab.ZeekrApp
import com.dante.zeekrcapabilitylab.enhancement.CameraWorkCoordinator
import com.dante.zeekrcapabilitylab.product.ProductRecorderConfigFactory
import com.dante.zeekrcapabilitylab.product.SettingsStore
import com.dante.zeekrcapabilitylab.product.RecordingModeSwitchConfig
import com.dante.zeekrcapabilitylab.product.RecordingQualityStore
import com.dante.zeekrcapabilitylab.sentry.CanaryCameraInterlock
import com.dante.zeekrcapabilitylab.service.CameraRecordingService
import com.dante.zeekrcapabilitylab.service.RecordingModeOverlayMenu
import com.dante.zeekrcapabilitylab.service.recorder.*
import com.dante.zeekrcapabilitylab.util.Utils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class FloatingMirrorState(val active: Boolean = false, val busy: Boolean = false, val error: String? = null)

/** Visible controls with per-install opt-in return behaviour. Handoffs expire; this service owns no camera or encoder. */
class FloatingMirrorService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var preferences: MirrorPresentation
    private lateinit var returnSettings: MirrorReturnSettings
    private val returnGate = MirrorReturnGate()
    private var automaticReturnChoice: MirrorReturnChoice? = null
    private var automaticReturnMessage: String? = null
    private var screenWasUnavailable = false
    private var cycleReturnMode = MirrorReturnMode.LOGO
    private var suspendedRecordingSession: String? = null
    private val gate = MirrorHandoffGate()
    private var fallback: MirrorOverlayWindow? = null
    private var pendingConfig: RecorderConfig? = null
    private var profileWaiting = false
    private var configJob: Job? = null
    private var starting: MirrorHandoffGate.Target? = null
    private var startingToken: String? = null
    private var startDeadline = 0L
    private var stopConfirmed = false
    private var ending = false
    private var idleChoice = RecordingModeChoice(RecordingMode.NORMAL)
    private var surroundReturnConfig: RecorderConfig? = null
    private var pendingStorage: RecordingStorageIdentity? = null
    private var startingStorage: RecordingStorageIdentity? = null
    private var previewRole = RecordingSourceRole.SURROUND
    private val sleep = MirrorSleepRetention()
    private var pausedWindow: PausedMirrorWindow? = null
    private val controlsSession = java.util.UUID.randomUUID().toString()
    private var sleepCycle: String? = null
    private var lastSleepFacts: Map<String, String>? = null
    private var returnObserved = false
    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) suspendOrEnd("SCREEN_OFF")
        }
    }
    override fun onCreate() {
        super.onCreate(); instance = this
        preferences = MirrorPresentation(this)
        returnSettings = MirrorReturnSettings(this)
        selection.value = idleChoice
        CaptureCleanupRuntime.initialize(this); RecorderNotification.ensureChannel(this)
        ContextCompat.registerReceiver(this, screen, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (ending) { mutable.value = FloatingMirrorState(); stopSelf(); return START_NOT_STICKY }
        if (intent?.action !in setOf(OPEN, FOLLOW)) { end(); return START_NOT_STICKY }
        val close = PendingIntent.getService(this, 91, Intent(this, FloatingMirrorService::class.java).setAction(CLOSE), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 92, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, RecorderNotification.CHANNEL_ID).setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("OpenAVM").setContentText(Utils.t("Floating mirror controls", "悬浮后视镜控制"))
            .setContentIntent(open).setOngoing(true).addAction(0, Utils.t("Close window", "关闭悬浮窗"), close).build()
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(0x5E49, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(0x5E49, notification)
        } catch (_: Exception) { end(); return START_NOT_STICKY }
        mutable.value = FloatingMirrorState(active = true, busy = gate.pending != null || starting != null)
        if (!authorized()) { end(); return START_NOT_STICKY }
        if (!screenUsable()) suspendOrEnd("START_WITH_SCREEN_UNAVAILABLE")
        if (ending) return START_NOT_STICKY
        if (sleep.suspended) {
            // OPEN/FOLLOW may be delivered late, or by a recreated home screen. Neither
            // is a tap on the paused entry, so neither may reopen a camera after sleep.
            main.removeCallbacks(tick); main.post(tick)
            return START_NOT_STICKY
        }
        if (CameraRecordingService.isRunning() && gate.pending == null && starting == null) {
            CameraRecordingService.state.value.sourceRole?.takeIf { it == RecordingSourceRole.SURROUND || it == RecordingSourceRole.CABIN }
                ?.let { previewRole = it }
        }
        if (intent?.action == OPEN) {
            preferences.videoVisible = true; preferences.dock = 0
            MirrorPreviewRuntime.restoreDisplay()
            if (!CameraRecordingService.isRunning() && !StandaloneMirrorService.isRunning()) request(previewTarget())
        }
        main.removeCallbacks(tick); main.post(tick)
        return START_NOT_STICKY
    }
    private fun authorized(): Boolean = !ending && Settings.canDrawOverlays(this) && SettingsStore.get(this).mirrorPreviewEnabled
    private fun screenUsable(): Boolean = MirrorDisplayState.usable(this)
    private fun usable(): Boolean = authorized() && !sleep.suspended && screenUsable()

    private val tick = object : Runnable {
        override fun run() {
            main.removeCallbacks(this)
            if (ending) return
            if (!authorized()) { end(); return }
            if (!screenUsable()) suspendOrEnd("POWER_STATE_UNAVAILABLE")
            if (ending) return
            if (sleep.suspended) {
                if (!returnSettings.effective.retained) { end(); return }
                runCatching { observeSleep() }.onFailure { end() }
                // Handler uptime pauses with the CPU. No alarm, wake lock, or busy keep-alive.
                if (!ending) main.postDelayed(this, if (sleep.suspended) 500 else 150)
                return
            }
            // Background camera admission checks for a visible control window. Publish
            // the fallback BEFORE advancing a handoff whose live window has just closed.
            runCatching { updateWindow(); advance() }.onFailure { fail("CONTROLS_UNAVAILABLE"); end() }
            if (!ending) main.postDelayed(this, 150)
        }
    }
    private fun sleepResources() = MirrorSleepResources(
        recorderRunning = CameraRecordingService.isRunning(), previewRunning = StandaloneMirrorService.isRunning(),
        auxiliaryActive = CameraWorkCoordinator.state.value.active,
        cleanupOwners = CaptureCleanupRuntime.pendingOwners.value,
        nativeIdle = CanaryCameraInterlock.normalIdle(), glIdle = MirrorGlPreview.isIdle(),
        wakeLockHeld = CameraRecordingService.state.value.wakeLockHeld,
    )
    private fun suspendOrEnd(reason: String) {
        val screenAvailableNow = screenUsable()
        if (screenAvailableNow) { logSleep("STALE_POWER_HINT_IGNORED", reason); return }
        if (!returnSettings.effective.retained || !authorized()) { end(); return }
        val newlySuspended = sleep.suspend()
        if (!newlySuspended && screenWasUnavailable) return
        screenWasUnavailable = true
        suspendedRecordingSession = CameraRecordingService.state.value.recordingSessionId.takeIf { CameraRecordingService.isRunning() }
        if (automaticReturnChoice != null && starting == MirrorHandoffGate.Target.RECORD) CameraRecordingService.cancelFloatingStart()
        automaticReturnChoice = null; automaticReturnMessage = null; profileWaiting = false
        val choice = returnSettings.choice
        cycleReturnMode = choice.mode; returnGate.arm(choice, screenAvailableNow)
        sleepCycle = java.util.UUID.randomUUID().toString(); returnObserved = false; lastSleepFacts = null
        // Revoke every pending handoff before waiting for close callbacks. An old start
        // intent must not consume a pre-sleep permit when the head unit wakes.
        gate.cancel(); starting = null; startingToken = null; pendingConfig = null
        pendingStorage = null; startingStorage = null; surroundReturnConfig = null
        configJob?.cancel(); configJob = null
        mutable.value = FloatingMirrorState(active = true)
        hideFallback(); MirrorPreviewRuntime.dismissDisplay()
        // Recording keeps its existing departure/finalisation policy. This controls-only
        // service cannot declare a recording stopped or release its wake lock for it.
        logSleep("SUSPENDED", reason)
        StandaloneMirrorService.stopForHandoff()
        runCatching { observeSleep() }.onFailure { end() }
        main.removeCallbacks(tick); if (!ending) main.postDelayed(tick, 1_000)
    }
    private fun observeSleep() {
        val resources = sleepResources()
        sleep.observe(resources)
        if (pausedWindow == null) pausedWindow = PausedMirrorWindow(this, preferences,
            resume = ::resumeFromSleep,
            menu = { basicMenu(this, it, null) })
        pausedWindow?.render(resources.released, screenUsable(), automaticReturnMessage)
        pausedWindow?.show()
        (MirrorPreviewRuntime.homeHost() as? MirrorHomeHost)?.showMessage(
            Utils.t("Preview paused. Tap the floating entry to resume.", "预览已暂停，点击悬浮入口恢复。"))
        val facts = sleepFacts(resources)
        if (facts != lastSleepFacts) {
            lastSleepFacts = facts
            logSleep(if (resources.released) "CAPTURE_RELEASED" else "RELEASE_PENDING", facts = facts)
        }
        if (!returnObserved && screenUsable()) {
            returnObserved = true
            logSleep("RETURN_OBSERVED", facts = facts)
        }
        if (screenUsable()) screenWasUnavailable = false
        advanceAutomaticReturn(resources)
    }
    private fun advanceAutomaticReturn(resources: MirrorSleepResources) {
        val decision = returnGate.poll(SystemClock.uptimeMillis(), screenUsable(), pausedWindow?.attached == true,
            resources, returnSettings.choice, sameRecordingContinues())
        when (decision) {
            MirrorReturnGate.Decision.FOLLOW_EXISTING -> {
                if (authorized() && sleep.restoreExistingRecording(true, screenUsable(), pausedWindow?.attached == true, sameRecordingContinues())) {
                    logSleep("EXISTING_RESTORED", "OPTED_IN_RETURN")
                    restoreFromSleep(record = false, followExisting = true)
                    return
                }
                automaticReturnMessage = Utils.t("Tap to retry", "点击手动重试")
            }
            MirrorReturnGate.Decision.PREVIEW, MirrorReturnGate.Decision.RECORD -> {
                if (!authorized() || !sleep.suspended) return
                if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED ||
                    Build.VERSION.SDK_INT >= 34 && !ZeekrApp.isForeground.value) {
                    automaticReturnMessage = Utils.t("Open App to resume", "打开 App 恢复")
                    logSleep("AUTO_BLOCKED", "CAMERA_FOREGROUND_PERMISSION")
                } else if (sleep.resumeAutomatic(decision, screenUsable(), pausedWindow?.attached == true, sleepResources())) {
                    automaticReturnChoice = returnSettings.choice
                    logSleep("AUTO_REQUESTED", decision.name)
                    restoreFromSleep(record = decision == MirrorReturnGate.Decision.RECORD)
                    return
                } else {
                    automaticReturnMessage = Utils.t("Tap to retry", "点击手动重试")
                    logSleep("AUTO_BLOCKED", "RESOURCE_CHANGED")
                }
            }
            MirrorReturnGate.Decision.TIMED_OUT -> {
                automaticReturnMessage = Utils.t("Not ready · tap to retry", "尚未就绪 · 点击重试")
                logSleep("AUTO_BLOCKED", "RELEASE_TIMEOUT")
            }
            MirrorReturnGate.Decision.SETTINGS_CHANGED -> {
                automaticReturnMessage = Utils.t("Tap to restore", "点击恢复预览")
                logSleep("AUTO_CANCELLED", "SETTINGS_CHANGED")
            }
            MirrorReturnGate.Decision.WAIT -> if (screenUsable() && resources.released) {
                automaticReturnMessage = if (cycleReturnMode == MirrorReturnMode.RECORD)
                    Utils.t("Starting recording…", "即将自动录像") else Utils.t("Restoring preview…", "即将恢复预览")
            }
            MirrorReturnGate.Decision.NONE -> Unit
        }
        pausedWindow?.render(resources.released, screenUsable(), automaticReturnMessage)
    }
    private fun resumeFromSleep() {
        if (!authorized() || !sleep.suspended) return
        // A deliberate tap always means preview only, even while an automatic recording is counting down.
        returnGate.cancel(); automaticReturnMessage = null
        if (runCatching { observeSleep() }.isFailure) { end(); return }
        val resources = sleepResources()
        if (sleep.restoreExistingRecording(true, screenUsable(), pausedWindow?.attached == true, sameRecordingContinues())) {
            logSleep("EXISTING_RESTORED", "USER_TAP")
            restoreFromSleep(record = false, followExisting = true)
            return
        }
        if (!sleep.resume(true, screenUsable(), pausedWindow?.attached == true, resources)) {
            logSleep("RESUME_BLOCKED")
            Toast.makeText(this, Utils.t("Wait for the camera to be released before resuming.", "请等相机释放完成后再恢复。"), Toast.LENGTH_SHORT).show()
            return
        }
        logSleep("RESUME_TAPPED")
        restoreFromSleep(record = false)
    }
    private fun sameRecordingContinues(): Boolean = CameraRecordingService.state.value.let { current ->
        MirrorExistingRecording.canRestore(suspendedRecordingSession, current.recordingSessionId,
            CameraRecordingService.isRunning(), current.status == RecorderStatus.RECORDING,
            current.mirrorPreviewManaged, current.cleanupUnconfirmed, current.lastError != null)
    }
    private fun restoreFromSleep(record: Boolean, followExisting: Boolean = false) {
        pausedWindow?.close(); pausedWindow = null
        preferences.videoVisible = true; preferences.dock = 0; preferences.controlsVisible = true
        MirrorPreviewRuntime.restoreDisplay()
        if (followExisting) {
            CameraRecordingService.state.value.sourceRole?.let { previewRole = it }
            mutable.value = FloatingMirrorState(active = true)
            main.removeCallbacks(tick); main.post(tick)
            return
        }
        if (record) {
            previewRole = RecordingSourceRole.SURROUND
            idleChoice = RecordingModeChoice(RecordingMode.NORMAL); selection.value = idleChoice
        }
        // Reuse the existing profile/storage, native close transaction and bounded handoff.
        val accepted = if (record) request(MirrorHandoffGate.Target.RECORD, recordingRole = RecordingSourceRole.SURROUND, waitForProfile = true)
            else request(previewTarget(), waitForProfile = true)
        if (!accepted) fail("RESUME_REQUEST_REJECTED")
        main.removeCallbacks(tick); main.post(tick)
    }
    private fun sleepFacts(resources: MirrorSleepResources = sleepResources()) = mapOf(
        "controlsSession" to controlsSession, "sleepCycle" to (sleepCycle ?: "NONE"),
        "sleepPhase" to sleep.phase.name, "pid" to Process.myPid().toString(),
        "windowSession" to (pausedWindow?.identity ?: "NONE"),
        "overlayAttached" to (pausedWindow?.attached == true).toString(),
        "overlayShown" to (pausedWindow?.shown == true).toString(),
        "screenUsable" to screenUsable().toString(), "captureReleased" to resources.released.toString(),
        "recorderRunning" to resources.recorderRunning.toString(), "previewRunning" to resources.previewRunning.toString(),
        "auxiliaryActive" to resources.auxiliaryActive.toString(), "cleanupOwners" to resources.cleanupOwners.toString(),
        "nativeIdle" to resources.nativeIdle.toString(), "glIdle" to resources.glIdle.toString(),
        "wakeLock" to resources.wakeLockHeld.toString(),
        "returnMode" to cycleReturnMode.name,
    )
    private fun automaticReturnStillAllowed(): Boolean = automaticReturnChoice?.let { it == returnSettings.choice && it.confirmed } ?: true
    private fun applyReturnSettingsChange() {
        returnGate.cancel()
        if (automaticReturnChoice != null) {
            if (starting == MirrorHandoffGate.Target.RECORD) CameraRecordingService.cancelFloatingStart()
            gate.cancel(); starting = null; startingToken = null; pendingConfig = null
            configJob?.cancel(); configJob = null; automaticReturnChoice = null
            StandaloneMirrorService.stopForHandoff(); MirrorPreviewRuntime.dismissDisplay(); hideFallback()
            sleep.suspend(); mutable.value = FloatingMirrorState(active = true)
        }
        if (sleep.suspended) {
            if (!returnSettings.effective.retained) { end(); return }
            automaticReturnMessage = Utils.t("Tap to restore", "点击恢复预览")
            observeSleep()
        }
    }
    private fun logSleep(stage: String, reason: String = "STATE", facts: Map<String, String> = sleepFacts()) {
        if (!com.dante.zeekrcapabilitylab.BuildConfig.MIRROR_RETURN_ENABLED) return
        com.dante.zeekrcapabilitylab.event.EventLogger.logEvent(com.dante.zeekrcapabilitylab.data.Categories.SYSTEM,
            "MIRROR_SLEEP_$stage", payload = facts + ("reason" to reason))
    }
    private fun copySleepReport() {
        scope.launch {
            val report = withContext(Dispatchers.IO) {
                runCatching { com.dante.zeekrcapabilitylab.diagnostic.AwayJournal.report().toString() }.getOrNull()
            }
            if (report == null) {
                Toast.makeText(this@FloatingMirrorService, Utils.t("Report unavailable", "报告暂时不可用"), Toast.LENGTH_SHORT).show()
            } else {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("OpenAVM sleep trial", report))
                Toast.makeText(this@FloatingMirrorService, Utils.t("Sleep report copied", "已复制休眠检查报告"), Toast.LENGTH_SHORT).show()
            }
        }
    }
    private fun request(target: MirrorHandoffGate.Target, config: RecorderConfig? = null, stoppedSession: String? = null,
                        requiredStorage: RecordingStorageIdentity? = null,
                        recordingRole: RecordingSourceRole = config?.source?.sourceRole ?: RecordingSourceRole.SURROUND,
                        transfer: MirrorRecordingTransfer? = null, frozenSource: RecorderConfig? = null,
                        waitForProfile: Boolean = false): Boolean {
        if (!usable() || starting != null || gate.pending != null) return false
        val request = gate.begin(target, SystemClock.elapsedRealtime(), stoppedSession) ?: return false
        log("REQUESTED", request)
        pendingConfig = config; profileWaiting = false; pendingStorage = requiredStorage; stopConfirmed = stoppedSession == null
        mutable.value = FloatingMirrorState(active = true, busy = true)
        StandaloneMirrorService.stopForHandoff()
        previewRole = when (target) {
            MirrorHandoffGate.Target.RECORD -> recordingRole
            MirrorHandoffGate.Target.CABIN -> RecordingSourceRole.CABIN
            else -> RecordingSourceRole.SURROUND
        }
        if (config == null) configJob = scope.launch {
            val role = previewRole
            val choice = if (target == MirrorHandoffGate.Target.RECORD) idleChoice else RecordingModeChoice(RecordingMode.NORMAL)
            val result = withTimeoutOrNull(if (waitForProfile) 17_000 else 8_000) {
                MirrorProfilePreparation.prepare(role, waitForProfile, SystemClock::uptimeMillis,
                    allowed = { gate.pending?.token == request.token && usable() && automaticReturnStillAllowed() },
                    lookup = { withContext(Dispatchers.IO) {
                        val next = if (frozenSource != null) RecordingModeSwitchConfig.build(frozenSource, choice,
                            RecordingQualityStore(this@FloatingMirrorService).get(role), true,
                            normalSharedInput = frozenSource.sharedInputRecordingEnabled)
                        else ProductRecorderConfigFactory.create(this@FloatingMirrorService,
                            role, choice.mode, choice.multiplier)?.copy(mirrorPreviewEnabled = true)
                        next?.let { transfer?.configure(it) ?: it }
                    } },
                    onProbe = { attempt, elapsed, status ->
                        profileWaiting = status == "WAITING"
                        if (waitForProfile) logSleep("PROFILE_$status", role.name, sleepFacts() + mapOf(
                            "profileAttempt" to attempt.toString(), "profileWaitMs" to elapsed.toString(),
                            "profileStatus" to status, "requestedRole" to role.name))
                    })
            }
            if (gate.pending?.token == request.token) {
                profileWaiting = false
                when (result) {
                    is MirrorProfileResult.Ready -> pendingConfig = result.config
                    is MirrorProfileResult.Failed -> fail(result.reason)
                    MirrorProfileResult.Cancelled -> { gate.cancel(); mutable.value = FloatingMirrorState(active = true) }
                    null -> fail("${role}_PROFILE_LOOKUP_TIMEOUT")
                }
            }
        }
        return true
    }
    private fun advance() {
        if (!automaticReturnStillAllowed()) { applyReturnSettingsChange(); return }
        val request = gate.pending
        if (request != null) {
            val aux = CameraWorkCoordinator.state.value
            val allowed = usable() && (request.target == MirrorHandoffGate.Target.RECORD || preferences.videoVisible) &&
                aux.error == null && pendingConfig?.validate()?.isEmpty() != false
            when (gate.poll(request.token, SystemClock.elapsedRealtime(), allowed,
                recorderGone = !CameraRecordingService.isRunning(), previewGone = !StandaloneMirrorService.isRunning(),
                auxiliaryGone = !aux.active, nativeIdle = CanaryCameraInterlock.normalIdle(), glIdle = MirrorGlPreview.isIdle(),
                stopConfirmed = stopConfirmed && pendingConfig != null)) {
                MirrorHandoffGate.Decision.WAIT -> Unit
                MirrorHandoffGate.Decision.CANCEL -> fail(if (allowed) "CAMERA_RELEASE_UNCONFIRMED" else "HANDOFF_CANCELLED")
                MirrorHandoffGate.Decision.START -> {
                    log("RELEASE_CONFIRMED", request)
                    starting = request.target; startDeadline = SystemClock.elapsedRealtime() + 20_000
                    startingToken = java.util.UUID.randomUUID().toString()
                    startingStorage = pendingStorage; pendingStorage = null
                    val accepted = when (request.target) {
                        MirrorHandoffGate.Target.PREVIEW -> StandaloneMirrorService.startOwned(this, checkNotNull(startingToken), checkNotNull(pendingConfig))
                        MirrorHandoffGate.Target.CABIN -> StandaloneMirrorService.startOwned(this, checkNotNull(startingToken), checkNotNull(pendingConfig))
                        MirrorHandoffGate.Target.RECORD -> runCatching { CameraRecordingService.start(this, checkNotNull(pendingConfig), floatingHandoffToken = startingToken); true }.getOrDefault(false)
                    }
                    pendingConfig = null
                    if (!accepted) fail("OPEN_APP_TO_CONTINUE")
                }
            }
        }
        starting?.let { target ->
            val recorder = CameraRecordingService.state.value
            val ready = if (target != MirrorHandoffGate.Target.RECORD) StandaloneMirrorService.isPreviewing()
                else CameraRecordingService.isRunning() && recorder.status == RecorderStatus.RECORDING && recorder.sourceRole == previewRole
            if (ready) {
                if (automaticReturnChoice != null) logSleep("AUTO_STARTED", automaticReturnChoice!!.mode.name)
                automaticReturnChoice = null; starting = null; startingStorage = null; mutable.value = FloatingMirrorState(active = true)
            }
            else if (CameraWorkCoordinator.state.value.error != null || CameraRecordingService.isRunning() && recorder.lastError != null && target == MirrorHandoffGate.Target.RECORD) fail("CAMERA_START_FAILED")
            else if (SystemClock.elapsedRealtime() >= startDeadline) {
                fail(if (target != MirrorHandoffGate.Target.RECORD) "PREVIEW_FIRST_FRAME_TIMEOUT" else "CAMERA_START_TIMEOUT")
                if (target != MirrorHandoffGate.Target.RECORD) StandaloneMirrorService.stopForHandoff()
                else CameraRecordingService.cancelFloatingStart()
            }
        }
    }
    private fun fail(reason: String) {
        if (ending || mutable.value.error == reason) return
        if (automaticReturnChoice != null) {
            logSleep("AUTO_FAILED", reason)
            if (starting == MirrorHandoffGate.Target.RECORD) CameraRecordingService.cancelFloatingStart()
            automaticReturnChoice = null
        }
        gate.cancel(); starting = null; pendingConfig = null; profileWaiting = false; configJob?.cancel(); configJob = null
        surroundReturnConfig = null; pendingStorage = null; startingStorage = null
        mutable.value = FloatingMirrorState(active = true, error = reason)
        Toast.makeText(this, Utils.t("Mirror operation stopped. Open the App to check the camera before trying again.", "后视镜操作已停止，请回到 App 检查相机状态后重试。"), Toast.LENGTH_LONG).show()
        com.dante.zeekrcapabilitylab.event.EventLogger.logEvent(com.dante.zeekrcapabilitylab.data.Categories.SYSTEM,
            "RECORDER_MIRROR_HANDOFF_STOPPED", payload = mapOf("reason" to reason))
    }
    private fun log(stage: String, request: MirrorHandoffGate.Request) {
        com.dante.zeekrcapabilitylab.event.EventLogger.logEvent(com.dante.zeekrcapabilitylab.data.Categories.SYSTEM,
            "RECORDER_MIRROR_HANDOFF_$stage", payload = mapOf("target" to request.target.name,
                "token" to request.token.toString(), "stoppedSession" to (request.stoppedSession ?: "NONE")))
    }
    private fun updateWindow() {
        if (!StandaloneMirrorService.isRunning() && !CameraRecordingService.isRunning()) {
            (MirrorPreviewRuntime.homeHost() as? MirrorHomeHost)?.showMessage(when {
                mutable.value.error != null -> Utils.t("Preview unavailable. Reopen the mirror manually.", "预览不可用，请手动重新开启后视镜。")
                profileWaiting -> Utils.t("Waiting for the camera to wake…", "正在等待相机就绪…")
                !preferences.videoVisible -> Utils.t("Preview hidden", "预览已隐藏")
                else -> Utils.t("Preparing preview", "正在准备预览")
            })
        }
        val show = !ZeekrApp.isForeground.value && !MirrorPreviewRuntime.overlayVisible
        if (!show) { hideFallback(); return }
        if (fallback == null) fallback = MirrorOverlayWindow(this, preferences,
            onRecord = ::recordOrStop,
            onMode = ::chooseMode, onVideo = ::setVideo, onClose = ::end, onPhoto = {},
            onLane = { preferences.selectedLane = it; if (previewRole == RecordingSourceRole.CABIN) returnToSurround() }, onLens = { preferences.setLens(preferences.selectedLane, it) },
            onZoom = {}, onReset = {}, onMenu = { basicMenu(this, it, fallback) }, onCabin = ::enterCabin,
            onGrid = { if (previewRole == RecordingSourceRole.CABIN) returnToSurround() }).also { window ->
                window.videoHost.addView(TextView(this).apply {
                    setTextColor(android.graphics.Color.WHITE); gravity = android.view.Gravity.CENTER
                    text = Utils.t("Waiting for live preview", "等待实时预览")
                }, android.widget.FrameLayout.LayoutParams(-1, -1))
            }
        val s = CameraRecordingService.state.value
        (fallback?.videoHost?.getChildAt(0) as? TextView)?.text = when {
            mutable.value.error?.contains("PROFILE") == true -> Utils.t("Camera not ready. Try again shortly.", "相机尚未就绪，请稍后重试。")
            mutable.value.error != null -> Utils.t("Preview did not resume. Hide and show the picture to retry.", "预览未恢复，请关闭画面后重新打开重试。")
            profileWaiting -> Utils.t("Waiting for the camera to wake…", "正在等待相机就绪…")
            else -> Utils.t("Preparing live preview…", "正在恢复预览…")
        }
        val switching = CameraRecordingService.modeSwitchProgress.value
        val running = CameraRecordingService.isRunning()
        val waiting = running && s.status in setOf(RecorderStatus.WAITING_CAMERA, RecorderStatus.RESUMING)
        val busy = mutable.value.busy || switching != null || running && s.status != RecorderStatus.RECORDING
        val label = when {
            mutable.value.error != null -> Utils.t("Preview unavailable · reopen manually", "预览不可用 · 请手动重开")
            profileWaiting -> Utils.t("Waiting for camera…", "正在等待相机就绪…")
            waiting -> Utils.t("Camera in use · waiting to resume", "相机被占用 · 等待恢复")
            automaticReturnChoice?.mode == MirrorReturnMode.RECORD && busy -> Utils.t("Starting automatic recording…", "正在自动开始录像…")
            automaticReturnChoice?.mode == MirrorReturnMode.PREVIEW && busy -> Utils.t("Restoring preview…", "正在恢复预览…")
            busy -> Utils.t("Switching / saving…", "正在切换／保存…")
            running -> Utils.t("Recording", "录像中")
            !preferences.videoVisible -> Utils.t("Video hidden · not recording", "画面隐藏 · 未录像")
            else -> Utils.t("Preview only", "仅预览")
        }
        val choice = if (running) RecordingModeChoice(s.recordingMode, s.timeLapseMultiplier) else idleChoice
        val recordPending = recordingCommandPending()
        fallback?.render(MirrorOverlayState(label, running || recordPending, busy, mode = choice.mode,
            multiplier = if (choice.mode == RecordingMode.NORMAL) SettingsStore.get(this).timeLapseMultiplier else choice.multiplier,
            canChangeMode = !running || RecordingModeOverlayMenu.available(), canStopWhileBusy = waiting || recordPending,
            cabin = previewRole == RecordingSourceRole.CABIN,
            error = mutable.value.error != null,
            elapsedMs = s.sessionStartedAtEpochMs?.let { System.currentTimeMillis() - it } ?: 0))
        fallback?.show()
    }
    private fun chooseMode(choice: RecordingModeChoice) {
        if (!usable() || !choice.valid() || mutable.value.busy) return
        if (CameraRecordingService.isRunning()) {
            CameraRecordingService.state.value.recordingSessionId?.let { CameraRecordingService.switchMode(choice, it) }
        } else {
            idleChoice = choice
            selection.value = choice
            if (choice.mode == RecordingMode.TIME_LAPSE) SettingsStore.get(this).setTimeLapseMultiplier(choice.multiplier)
        }
    }
    private fun setVideo(show: Boolean) {
        if (sleep.suspended) return
        preferences.videoVisible = show
        if (!show && !CameraRecordingService.isRunning()) {
            surroundReturnConfig = null
            if (gate.pending?.target != MirrorHandoffGate.Target.RECORD) gate.cancel()
            if (starting != MirrorHandoffGate.Target.RECORD) starting = null
            StandaloneMirrorService.stopForHandoff()
            mutable.value = FloatingMirrorState(active = true, busy = gate.pending != null || starting != null)
        } else if (show && !CameraRecordingService.isRunning() && !StandaloneMirrorService.isPreviewing()) {
            request(previewTarget())
        }
        MirrorPreviewRuntime.refreshHome()
    }
    private fun enterCabin() {
        if (!usable() || mutable.value.busy || previewRole == RecordingSourceRole.CABIN || !preferences.videoVisible) return
        switchSource(RecordingSourceRole.CABIN)
    }
    private fun beginRecording(config: RecorderConfig? = null) {
        request(MirrorHandoffGate.Target.RECORD, config, recordingRole = config?.source?.sourceRole ?: previewRole)
    }
    private fun returnToSurround() {
        if (previewRole != RecordingSourceRole.CABIN || mutable.value.busy) return
        switchSource(RecordingSourceRole.SURROUND)
    }
    private fun previewTarget() = if (previewRole == RecordingSourceRole.CABIN) MirrorHandoffGate.Target.CABIN else MirrorHandoffGate.Target.PREVIEW
    private fun recordingCommandPending() = gate.pending?.target == MirrorHandoffGate.Target.RECORD || starting == MirrorHandoffGate.Target.RECORD
    private fun recordOrStop() {
        returnGate.cancel(); automaticReturnChoice = null
        if (sleep.suspended) return
        if (CameraRecordingService.isRunning()) CameraRecordingService.stop(this)
        else if (recordingCommandPending()) {
            // Revoke the command before a queued start Intent or config job can consume it.
            gate.cancel(); starting = null; startingToken = null; configJob?.cancel(); pendingConfig = null
            pendingStorage = null; startingStorage = null; surroundReturnConfig = null
            mutable.value = FloatingMirrorState(active = true)
            if (preferences.videoVisible) request(previewTarget())
        } else beginRecording()
    }
    private fun switchSource(role: RecordingSourceRole) {
        if (!usable()) return
        val running = CameraRecordingService.isRunning()
        val transfer = if (running) CameraRecordingService.mirrorRecordingTransfer() ?: return else null
        if (transfer == null) {
            surroundReturnConfig = null
            request(if (role == RecordingSourceRole.CABIN) MirrorHandoffGate.Target.CABIN else MirrorHandoffGate.Target.PREVIEW)
            return
        }
        idleChoice = RecordingModeChoice(transfer.config.recordingMode, transfer.config.timeLapseMultiplier)
        selection.value = idleChoice
        val frozen = surroundReturnConfig.takeIf { role == RecordingSourceRole.SURROUND }
        if (request(MirrorHandoffGate.Target.RECORD, stoppedSession = transfer.session, requiredStorage = transfer.storage,
                recordingRole = role, transfer = transfer, frozenSource = frozen)) {
            if (role == RecordingSourceRole.CABIN) surroundReturnConfig = transfer.config
            CameraRecordingService.stopForMirrorSourceSwitch(transfer.session)
        }
    }
    private fun hideFallback() { fallback?.close(); fallback = null }
    private fun end() {
        if (ending) return
        returnGate.cancel()
        if (automaticReturnChoice != null && starting == MirrorHandoffGate.Target.RECORD) CameraRecordingService.cancelFloatingStart()
        automaticReturnChoice = null
        if (sleep.suspended) logSleep("CONTROLS_CLOSED")
        sleep.close(); pausedWindow?.close(); pausedWindow = null
        ending = true; gate.cancel(); starting = null; surroundReturnConfig = null; pendingStorage = null; startingStorage = null; scope.cancel(); hideFallback()
        mutable.value = FloatingMirrorState()
        MirrorPreviewRuntime.dismissDisplay()
        StandaloneMirrorService.stopForHandoff()
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onDestroy() {
        end(); main.removeCallbacksAndMessages(null); runCatching { unregisterReceiver(screen) }
        if (instance === this) instance = null
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val OPEN = "openavm.mirror.OPEN"
        private const val FOLLOW = "openavm.mirror.FOLLOW"
        private const val CLOSE = "openavm.mirror.CLOSE"
        private var instance: FloatingMirrorService? = null
        private val mutable = MutableStateFlow(FloatingMirrorState())
        val state = mutable.asStateFlow()
        private val selection = MutableStateFlow(RecordingModeChoice(RecordingMode.NORMAL))
        val idleSelection = selection.asStateFlow()
        fun active() = mutable.value.active
        fun visible() = instance?.pausedWindow?.shown == true || instance?.fallback?.isVisible == true || MirrorPreviewRuntime.overlayVisible
        fun open(context: Context): Boolean = launch(context, OPEN)
        fun followRecording(context: Context) { if (!active()) launch(context, FOLLOW) }
        private fun launch(context: Context, action: String): Boolean {
            if (!Settings.canDrawOverlays(context)) return false
            if (SettingsStore.get(context).mirrorRearLane !in 1..4 || ContextCompat.checkSelfPermission(context,
                    android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) return false
            if (action == OPEN && !ZeekrApp.isForeground.value && !visible()) return false
            SettingsStore.get(context).setMirrorPreviewEnabled(true)
            mutable.value = FloatingMirrorState(active = true, busy = action == OPEN)
            return runCatching { ContextCompat.startForegroundService(context,
                Intent(context, FloatingMirrorService::class.java).setAction(action)); true }
                .getOrElse { mutable.value = FloatingMirrorState(); false }
        }
        fun close() { instance?.end() }
        fun returnSettingsChanged() { instance?.let { owner -> owner.main.post { if (instance === owner && !owner.ending) owner.applyReturnSettingsChange() } } }
        fun hideFallbackForLive() { instance?.hideFallback() }
        fun setVideoVisible(show: Boolean) { instance?.setVideo(show) }
        fun selectMode(choice: RecordingModeChoice) { instance?.chooseMode(choice) }
        fun idleMode() = instance?.idleChoice ?: RecordingModeChoice(RecordingMode.NORMAL)
        fun startRecording(config: RecorderConfig? = null) { instance?.beginRecording(config) }
        fun recordControl() { instance?.recordOrStop() }
        fun recordingTransferPending() = instance?.recordingCommandPending() == true
        fun selectCabin() { instance?.enterCabin() }
        fun selectSurround() { instance?.returnToSurround() }
        /** Home source buttons select preview only; existing recording transfers stay separate. */
        fun selectPreviewSource(role: RecordingSourceRole): Boolean {
            val owner = instance ?: return false
            if (role !in setOf(RecordingSourceRole.SURROUND, RecordingSourceRole.CABIN) ||
                CameraRecordingService.isRunning() || mutable.value.busy || !owner.usable()) return false
            owner.preferences.videoVisible = true
            if (owner.previewRole != role || !StandaloneMirrorService.isPreviewing()) owner.switchSource(role)
            return true
        }
        internal fun awaitingRecordingRelease(session: String) = instance?.gate?.pending?.let {
            it.target == MirrorHandoffGate.Target.RECORD && it.stoppedSession == session
        } == true
        internal fun recordingStartStorage(token: String): RecordingStorageIdentity? =
            instance?.takeIf { admitsRecordingStart(token) }?.startingStorage
        /** Re-check a queued service intent: closing the controls or locking revokes it. */
        fun admitsRecordingStart(token: String): Boolean = instance?.let {
            it.usable() && it.automaticReturnStillAllowed() && it.starting == MirrorHandoffGate.Target.RECORD && it.startingToken == token
        } == true
        fun admitsPreviewStart(token: String, role: RecordingSourceRole = RecordingSourceRole.SURROUND): Boolean = instance?.let {
            it.usable() && it.automaticReturnStillAllowed() && it.preferences.videoVisible && it.previewRole == role &&
                it.starting == (if (role == RecordingSourceRole.CABIN) MirrorHandoffGate.Target.CABIN else MirrorHandoffGate.Target.PREVIEW) && it.startingToken == token
        } == true
        fun onManualStop(current: RecorderState) {
            val owner = instance ?: return
            owner.returnGate.cancel(); owner.automaticReturnChoice = null
            if (owner.sleep.suspended) return
            owner.gate.cancel(); owner.starting = null; owner.configJob?.cancel(); owner.pendingConfig = null
            owner.pendingStorage = null; owner.startingStorage = null; owner.surroundReturnConfig = null
            owner.previewRole = current.sourceRole?.takeIf { it == RecordingSourceRole.CABIN } ?: RecordingSourceRole.SURROUND
            mutable.value = FloatingMirrorState(active = true)
            owner.idleChoice = RecordingModeChoice(current.recordingMode, current.timeLapseMultiplier)
            selection.value = owner.idleChoice
            if (owner.preferences.resident && owner.preferences.videoVisible && owner.usable() && current.status == RecorderStatus.RECORDING &&
                current.lastError == null && !current.cleanupUnconfirmed && current.recordingSessionId != null) {
                owner.request(owner.previewTarget(), stoppedSession = current.recordingSessionId)
            }
        }
        fun recorderStopped(current: RecorderState) {
            val owner = instance ?: return
            if (owner.sleep.suspended) { owner.main.post { if (!owner.ending) owner.observeSleep() }; return }
            if (!owner.preferences.resident && owner.gate.pending == null) { owner.end(); return }
            if (current.lastError != null || current.cleanupUnconfirmed) { owner.fail("RECORDING_STOP_NOT_CONFIRMED"); return }
            val request = owner.gate.pending ?: run {
                // Automatic completion (or a cancelled start) is not a new preview command.
                // Keep usable controls instead of indefinitely claiming a preview is preparing.
                owner.preferences.videoVisible = false
                owner.starting = null
                mutable.value = FloatingMirrorState(active = true)
                return
            }
            if (request.stoppedSession != null) {
                if (current.recordingSessionId == request.stoppedSession && current.lastError == null &&
                    !current.cleanupUnconfirmed && current.status == RecorderStatus.STOPPED) owner.stopConfirmed = true
                else owner.fail("RECORDING_STOP_NOT_CONFIRMED")
            }
        }
        fun previewFailed(reason: String, token: String? = null) {
            val owner = instance ?: return
            owner.main.post {
                if (instance === owner && !owner.sleep.suspended && (token == null || owner.startingToken == token)) owner.fail(reason)
            }
        }
        fun basicMenu(context: Context, anchor: View, window: MirrorOverlayWindow?, extra: (PopupMenu) -> Unit = {}) {
            val prefs = MirrorPresentation(context)
            val menu = PopupMenu(context, anchor)
            menu.menu.add(0, 100, 0, Utils.t("Keep window after stopping", "停止录像后保留窗口")).apply { isCheckable = true; isChecked = prefs.resident }
            menu.menu.add(0, 101, 1, Utils.t("Lock position and size", "锁定位置和大小")).apply { isCheckable = true; isChecked = prefs.locked }
            menu.menu.add(0, 102, 2, Utils.t("Camera directions", "方向对应"))
            menu.menu.add(0, 103, 3, "OpenAVM")
            menu.menu.add(0, 104, 4, Utils.t("Camera controls on right", "镜头控制放右侧")).apply { isCheckable = true; isChecked = prefs.controlsOnRight }
            if (com.dante.zeekrcapabilitylab.BuildConfig.MIRROR_RETURN_ENABLED) {
                menu.menu.add(0, 105, 5, Utils.t("Return behaviour settings", "回车行为设置"))
                if (SettingsStore.get(context).developerModeEnabled) {
                    menu.menu.add(0, 106, 6, Utils.t("Copy sleep check report", "复制休眠检查报告"))
                }
                menu.menu.add(0, 107, 7, Utils.t("Close floating window", "关闭悬浮窗"))
            }
            extra(menu)
            menu.setOnMenuItemClickListener {
                when (it.itemId) {
                    100 -> { prefs.resident = !prefs.resident; if (!prefs.resident && !CameraRecordingService.isRunning()) close() }
                    101 -> prefs.locked = !prefs.locked
                    102 -> window?.directionMenu(anchor)
                    103 -> context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                    104 -> prefs.controlsOnRight = !prefs.controlsOnRight
                    105 -> context.startActivity(Intent(context, MainActivity::class.java)
                        .putExtra("openavm.return_settings", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                    106 -> instance?.copySleepReport()
                    107 -> close()
                    else -> return@setOnMenuItemClickListener false
                }; true
            }
            menu.show()
        }
    }
}
