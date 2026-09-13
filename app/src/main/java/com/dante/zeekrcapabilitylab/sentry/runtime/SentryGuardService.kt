package com.dante.zeekrcapabilitylab.sentry.runtime

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dante.zeekrcapabilitylab.BuildConfig
import com.dante.zeekrcapabilitylab.ZeekrApp
import com.dante.zeekrcapabilitylab.product.ProductRecorderConfigFactory
import com.dante.zeekrcapabilitylab.service.CameraRecordingService
import com.dante.zeekrcapabilitylab.service.RemovableStorageMonitor
import com.dante.zeekrcapabilitylab.service.recorder.*
import com.dante.zeekrcapabilitylab.sentry.*
import com.dante.zeekrcapabilitylab.sentry.canary.SentryCanaryService
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** User-started runtime only. A persisted preference never recreates a run permit. */
class SentryGuardService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val savingReport = AtomicBoolean()
    private val reportDirty = AtomicBoolean()
    private val control = RecorderModeCoordinator(featureAvailable = true)
    private val presence = VehiclePresenceStateMachine(PresencePolicy.integrated())
    private val runId = UUID.randomUUID().toString()
    private val runConfiguration = GuardRunConfiguration(prepareCamera = { config ->
        if (parkingBehavior == ParkingBehavior.SENTRY) GuardCameraCapabilitiesReader.read(this, config.source) else null
    }) { recorderConfig(this) }
    private var normal: RecorderSession? = null
    private var capture: GuardCapturePipeline? = null
    private var owner: CameraLeaseTicket? = null
    private var normalClosing = false
    private var reserved = false
    private var destroyed = false
    private var began = false
    private var finishing = false
    private var terminalReportQueued = false
    private val testMode = GuardTestModePolicy()
    private var parkingBehavior = ParkingBehavior.SENTRY
    private var runDuration = GuardRunDuration.HOURS_12
    private var startedElapsedMs = 0L
    private var foreground = false
    private var wake: PowerManager.WakeLock? = null
    private var ticks = 0
    private lateinit var storageMonitor: RemovableStorageMonitor

    override fun onCreate() {
        super.onCreate()
        storageMonitor = RemovableStorageMonitor(this) { action, path -> main.post {
            if (!finishing) log("可移动存储事件：$action ${path.orEmpty().take(100)}")
            normal?.onRemovableStorageUnavailable(action, path)
        } }
        instance = this
        starting = false
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!BuildConfig.SENTRY_INTEGRATED_ENABLED || !BuildConfig.SENTRY_CAPTURE_ENABLED || intent == null) {
            stopSelf(); return START_NOT_STICKY
        }
        if (intent.action == START && !began) {
            parkingBehavior = ParkingBehavior.entries.firstOrNull { it.name == intent.getStringExtra(PARKING) } ?: ParkingBehavior.SENTRY
            runDuration = GuardRunDuration.fromStored(intent.getStringExtra(DURATION))
            begin()
            if (stopPending) stopRun(SentryStopReason.MANUAL_STOP)
        }
        else if (intent.getStringExtra(RUN) == runId && intent.action == STOP) stopRun(SentryStopReason.MANUAL_STOP)
        else if (!began) stopSelf() // A retired notification must not create an idle service instance.
        return START_NOT_STICKY
    }
    private fun begin() {
        began = true
        startedElapsedMs = SystemClock.elapsedRealtime()
        mutableState.value = GuardState(build = BuildConfig.VERSION_NAME, runId = runId, running = true,
            phase = "STARTING", startedAtEpochMs = System.currentTimeMillis(), message = "正在恢复事件记录并准备摄像头参数",
            preparationStage = "RUN_PREPARATION",
            parkingBehavior = parkingBehavior, runDuration = runDuration,
            runtime = GuardRuntimeEvidence(processId = processId, pid = Process.myPid(),
                runExpiresAtEpochMs = runDuration.durationMs?.let { System.currentTimeMillis() + it } ?: 0))
        io.execute { runCatching { GuardReportStore(applicationContext).preservePrevious(runId) } }
        CameraRecordingService.publishGuardState(RecorderState(status = RecorderStatus.STARTING, recordingSessionId = runId))
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "OpenAVM 哨兵", NotificationManager.IMPORTANCE_LOW))
            val notification = notification("正在开始本次运行")
            if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
            else startForeground(NOTIFICATION, notification)
            foreground = true
            check(!CameraRecordingService.isLegacyRunning() && !SentryCanaryService.state.value.running && CanaryCameraInterlock.normalIdle()) {
                "请先停止原有录像或 RAM 测试，等待相机释放"
            }
            wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:sentry-guard").apply {
                setReferenceCounted(false)
                runDuration.durationMs?.let { acquire(it + 60_000) } ?: acquire()
            }
            change { it.copy(runtime = it.runtime.copy(wakeAcquiredAtEpochMs = System.currentTimeMillis(),
                wakeLockHeld = true, serviceForeground = true)) }
            storageMonitor.start()
            io.execute {
                val recovered = runCatching {
                    val count = GuardEventStore(applicationContext).recover()
                    checkNotNull(runConfiguration.get()) { "NO_DECLARED_SURROUND_PROFILE" }
                    count
                }
                main.post {
                    if (finishing || destroyed) { finishIfIdle(); return@post }
                    recovered.exceptionOrNull()?.let { error ->
                        val preparation = error as? GuardCameraPreparationException
                        val stage = preparation?.stage ?: "RUN_PREPARATION"
                        change { it.copy(runSource = preparation?.source ?: runConfiguration.current?.source,
                            preparationStage = stage, preparationFailure = GuardExceptionSummary.describe(error)) }
                        fail("哨兵启动准备失败：$stage"); return@post
                    }
                    if (runDuration.remainingMs(startedElapsedMs, SystemClock.elapsedRealtime()) == 0L) {
                        stopRun(SentryStopReason.RUN_LIMIT); return@post
                    }
                    log("恢复 ${recovered.getOrDefault(0)} 个中断事件")
                    change { it.copy(runSource = runConfiguration.current?.source, runCamera = runConfiguration.camera,
                        preparationStage = "READY") }
                    runConfiguration.camera?.let { camera ->
                        log("开启时已校验相机参数：${camera.cameraId} ${camera.sourceWidth}x${camera.sourceHeight}；交接复用本次参数")
                    }
                    control.setPolicy(GuardPolicy.AUTO)
                    val effects = control.explicitStart(parkingBehavior); presence.begin(control.permit)
                    apply(effects); main.post(poll)
                    runDuration.remainingMs(startedElapsedMs, SystemClock.elapsedRealtime())?.let { main.postDelayed(runLimit, it) }
                }
            }
        } catch (t: Throwable) { fail(t.message ?: t.javaClass.simpleName) }
    }

    private val poll = object : Runnable {
        override fun run() {
            if (finishing || destroyed) return
            val power = VehiclePowerSnapshotReader.read(applicationContext)
            val now = SystemClock.elapsedRealtime()
            if (runDuration.remainingMs(startedElapsedMs, now) == 0L) {
                stopRun(SentryStopReason.RUN_LIMIT); return
            }
            val signal = PresenceSignals(if (power.appForeground) SignalValue.ON else SignalValue.OFF,
                if (power.interactive) SignalValue.ON else SignalValue.OFF,
                when (power.mainDisplayState) { "OFF" -> SignalValue.OFF; "ON", "VR" -> SignalValue.ON; else -> SignalValue.UNKNOWN }, now)
            val oldPresence = presence.phase
            val oldPower = mutableState.value.power
            presence.observe(control.permit.generation, signal, now)
            presence.deadline?.takeIf { it.atMs <= now }?.let { presence.onTimer(it, signal, now) }
            if (oldPresence != presence.phase) {
                log("在车判断 $oldPresence → ${presence.phase}")
                if (presence.phase == VehiclePresencePhase.OCCUPIED && oldPresence == VehiclePresencePhase.RETURN_PENDING)
                    change { it.copy(runtime = it.runtime.copy(returnConfirmedAtEpochMs = System.currentTimeMillis())) }
            }
            val hadTestOverride = testMode.override != null
            val desired = testMode.effective(presence.phase, power.appForeground)
            if (hadTestOverride && testMode.override == null) log("车内测试结束：应用已到后台，恢复 AUTO 自动切换")
            apply(control.presence(control.permit.generation, desired))
            val thermal = if (Build.VERSION.SDK_INT >= 29) getSystemService(PowerManager::class.java).currentThermalStatus else null
            change { it.copy(presence = presence.phase.name, power = "应用=${power.appForeground} 交互=${power.interactive} 主屏=${power.mainDisplayState}",
                pssKiB = if (++ticks % 5 == 0) Debug.getPss().toInt() else it.pssKiB, thermalStatus = thermal) }
            if (oldPower != mutableState.value.power) log("电源 ${mutableState.value.power}")
            sampleRuntime()
            val runtime = Runtime.getRuntime()
            if (runtime.totalMemory() - runtime.freeMemory() > runtime.maxMemory() * 0.85) fail("HEAP_PRESSURE")
            if (thermal != null && thermal >= PowerManager.THERMAL_STATUS_SEVERE) fail("THERMAL_SEVERE")
            if (ticks % 30 == 0) persist()
            if (!finishing) main.postDelayed(this, 1000)
        }
    }

    private val runLimit = Runnable { if (!finishing) stopRun(SentryStopReason.RUN_LIMIT) }
    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(this, 41, Intent(this, SentryGuardActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 42, Intent(this, SentryGuardService::class.java).setAction(STOP).putExtra(RUN, runId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(if (parkingBehavior == ParkingBehavior.AWAKE_IDLE) "OpenAVM 自动录像" else "OpenAVM 哨兵模式")
            .setContentText(message).setOngoing(true).setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause, "停止本次运行", stop).build()
    }
    private fun sampleRuntime(heartbeat: Boolean = true) {
        val power = getSystemService(PowerManager::class.java)
        val elapsed = SystemClock.elapsedRealtime()
        val uptime = SystemClock.uptimeMillis()
        change { state ->
            val old = state.runtime
            val gap = if (heartbeat && old.lastElapsedMs > 0) elapsed - old.lastElapsedMs else 0
            val suspendGap = if (gap > 0) (gap - (uptime - old.lastUptimeMs)).coerceAtLeast(0) else 0
            state.copy(runtime = old.copy(serviceForeground = foreground, wakeLockHeld = wake?.isHeld == true,
                heartbeat = old.heartbeat + if (heartbeat) 1 else 0,
                lastElapsedMs = if (heartbeat) elapsed else old.lastElapsedMs,
                lastUptimeMs = if (heartbeat) uptime else old.lastUptimeMs,
                maxHeartbeatGapMs = maxOf(old.maxHeartbeatGapMs, gap), maxSuspendGapMs = maxOf(old.maxSuspendGapMs, suspendGap),
                deviceIdleMode = power.isDeviceIdleMode, batteryOptimizationExempt = power.isIgnoringBatteryOptimizations(packageName),
                appForeground = ZeekrApp.isForeground.value, interactive = power.isInteractive,
                mainDisplay = if (heartbeat) VehiclePowerSnapshotReader.read(applicationContext).mainDisplayState else old.mainDisplay,
                appLastForegroundAtEpochMs = if (ZeekrApp.isForeground.value && !old.appForeground) System.currentTimeMillis() else old.appLastForegroundAtEpochMs,
                cameraLease = control.lease.snapshot?.let { "${it.ticket.mode}/${it.phase}/${it.ticket.id}" },
                normalSessionActive = normal != null, sentryPipelineActive = capture != null,
                resourcesReleased = owner == null && normal == null && capture == null && !reserved && CanaryCameraInterlock.normalIdle()))
        }
    }

    private fun apply(effects: List<ModeEffect>) {
        val oldPhase = mutableState.value.phase
        for (effect in effects) when (effect) {
            is ModeEffect.Acquire -> acquire(effect.ticket)
            is ModeEffect.Release -> release(effect.ticket)
            is ModeEffect.CloseStaleResource -> if (owner == effect.ticket) release(effect.ticket)
        }
        change { it.copy(phase = control.phase.name, mode = owner?.mode?.name,
            manualMode = testMode.override?.let { phase -> if (phase == VehiclePresencePhase.OCCUPIED) "NORMAL" else parkingBehavior.name }) }
        if (control.phase == RecorderModePhase.AWAKE_IDLE && !finishing) {
            if (oldPhase != control.phase.name) {
                log("停录完成：相机、编码和写入任务已释放；保留本次运行与 CPU 唤醒锁")
                change { it.copy(runtime = it.runtime.copy(idleEntries = it.runtime.idleEntries + 1,
                    idleEnteredAtEpochMs = System.currentTimeMillis())) }
            }
            change { it.copy(message = "已停录，保持运行，等待回车；相机和视频缓存已关闭",
                normalStatus = RecorderStatus.AWAKE_IDLE, capture = null, ai = GuardAiState(message = "自动录像等待期间不运行 AI")) }
            CameraRecordingService.publishGuardState(RecorderState(status = RecorderStatus.AWAKE_IDLE,
                recordingSessionId = runId, message = mutableState.value.message))
        }
        sampleRuntime(heartbeat = false)
        if (oldPhase != control.phase.name) {
            if (foreground) getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(
                when (control.phase) {
                    RecorderModePhase.AWAKE_IDLE -> "已停录，保持运行，等待回车"
                    RecorderModePhase.NORMAL_ACTIVE -> "普通录像中；离车后自动切换"
                    RecorderModePhase.SENTRY_ACTIVE -> "哨兵 RAM 监听中"
                    else -> "正在切换或释放录像资源"
                }))
            persist()
        }
        finishIfIdle()
    }
    private fun acquire(ticket: CameraLeaseTicket) {
        check(owner == null)
        owner = ticket
        log("申请 ${ticket.mode}，切换 ${ticket.stamp.transition}")
        if (ticket.mode == CaptureMode.NORMAL) {
            change { it.copy(runtime = it.runtime.copy(normalRequestedAtEpochMs = System.currentTimeMillis())) }
            val config = runConfiguration.current
            if (config == null) { fail("未找到环视相机配置"); releasedWithoutResource(ticket); return }
            if (mutableState.value.runSource == null) {
                change { it.copy(runSource = config.source) }
                log("本次运行固定环视配置：${config.cameraId} ${config.profile.key}")
            }
            normalClosing = false
            normalProfile = config.profile
            normal = RecorderSession(applicationContext, { value -> main.post {
                if (owner != ticket || normalClosing) return@post
                change { GuardNormalEvidencePolicy.observe(it, value, System.currentTimeMillis())
                    .copy(normalStatus = value.status, message = value.message ?: "普通录像中", error = value.lastError) }
                CameraRecordingService.publishGuardState(value)
                if (value.status == RecorderStatus.RECORDING) {
                    if (control.lease.snapshot?.phase == CameraLeasePhase.ACQUIRING) {
                        change { it.copy(runtime = it.runtime.copy(normalStartedAtEpochMs = System.currentTimeMillis(),
                            normalStartedInBackground = !ZeekrApp.isForeground.value)) }
                        log("普通录像已开始，应用前台=${ZeekrApp.isForeground.value}")
                    }
                    apply(control.acquired(ticket))
                }
                else if (value.status == RecorderStatus.ERROR) fail(value.lastError ?: "NORMAL_RECORDING_FAILED")
            } }, { main.post { normalStopped(ticket) } }, externallyManagedPresence = true).also { it.start(config) }
        } else {
            val source = runConfiguration.current?.source
            if (source == null) { fail("RUN_SURROUND_CONFIGURATION_MISSING"); releasedWithoutResource(ticket); return }
            val capabilities = runConfiguration.camera
            if (capabilities == null) { fail("RUN_CAMERA_CAPABILITIES_MISSING"); releasedWithoutResource(ticket); return }
            if (!CanaryCameraInterlock.reserveCanary(ticket)) {
                fail("相机尚未确认释放，已停止交接"); releasedWithoutResource(ticket); return
            }
            reserved = true
            capture = GuardCapturePipeline(applicationContext, runId, ticket.stamp, source, capabilities,
                { ai -> main.post { if (owner == ticket) change { it.copy(ai = ai) } } },
                { id, end -> main.post { if (owner == ticket) {
                    change { it.copy(eventId = id, eventDeadlineUs = end, message = "正在保存事件，重复触发会延长后录") }; log("事件触发 $id")
                } } },
                { event -> main.post { if (owner == ticket) {
                    change { it.copy(eventId = null, eventDeadlineUs = null, eventsRevision = it.eventsRevision + 1,
                        message = "事件 ${event.state}：${event.assets.size} 段") }
                    log("事件 ${event.id} ${event.state} ${event.reason.orEmpty()}")
                    if (event.state == "FAILED" && !finishing) fail(event.reason ?: "EVENT_SAVE_FAILED")
                } } },
                { value -> main.post { if (owner == ticket) {
                    change { it.copy(capture = value, message = when {
                        value.saving -> it.message
                        value.outputFrames == 0L -> "哨兵正在启动，尚未采集画面"
                        else -> "RAM 历史 %.1f / 180 秒".format(value.historySeconds)
                    }) }
                    if (!finishing && value.stopReason != null &&
                        control.lease.snapshot?.phase != CameraLeasePhase.RELEASING) recordFailure(value.stopReason)
                    val status = when {
                        value.phase == "STOPPING" || !value.running -> RecorderStatus.FINALIZING
                        value.outputFrames > 0L -> RecorderStatus.SENTRY_LISTENING
                        else -> RecorderStatus.STARTING
                    }
                    CameraRecordingService.publishGuardState(RecorderState(status = status,
                        recordingSessionId = runId, message = mutableState.value.message, profile = normalProfile))
                    if (value.outputFrames > 0 && control.lease.snapshot?.phase == CameraLeasePhase.ACQUIRING) apply(control.acquired(ticket))
                } } },
                { final -> main.post { if (owner == ticket) {
                    change { it.copy(capture = final, ai = it.ai.copy(status = "STOPPED", message = "分析已停止")) }
                    capture = null
                    if (reserved) { check(CanaryCameraInterlock.canaryClosed(ticket)); reserved = false }
                    if (!finishing && mutableState.value.firstFailure != null) fail(mutableState.value.firstFailure!!.reason)
                    else if (control.lease.snapshot?.phase != CameraLeasePhase.RELEASING) fail(final.stopReason ?: "SENTRY_CAPTURE_ENDED")
                    owner = null; apply(control.released(ticket))
                } } },
            ).also { it.start() }
        }
        persist()
    }
    private var normalProfile: com.dante.zeekrcapabilitylab.probe.camera.CameraFormatProfile? = null

    private fun normalStopped(ticket: CameraLeaseTicket) {
        if (owner != ticket || normalClosing) return
        if (control.lease.snapshot?.phase != CameraLeasePhase.RELEASING) fail(GuardNormalEvidencePolicy.stopReason(mutableState.value))
        normalClosing = true
        normal?.releaseForModeHandoff { main.post {
            if (owner != ticket) return@post
            normal = null; owner = null; normalClosing = false
            log("普通录像相机与写入任务已释放")
            apply(control.released(ticket))
        } }
    }
    private fun release(ticket: CameraLeaseTicket) {
        if (owner != ticket) return
        log("关闭 ${ticket.mode}，等待释放确认")
        if (ticket.mode == CaptureMode.NORMAL) normal?.stop() else capture?.stop(
            if (finishing) control.stopReason?.name ?: "MANUAL_STOP" else "RETURN_TO_NORMAL")
        main.postDelayed({
            if (owner == ticket && control.lease.snapshot?.phase == CameraLeasePhase.RELEASING) {
                change { it.copy(error = "释放等待超时；不会抢占相机。可复制 JSON。") }
                stopRun(SentryStopReason.RELEASE_TIMEOUT)
                wake?.let { if (it.isHeld) it.release() }; wake = null
                storageMonitor.stop()
                foreground = false
                change { it.copy(running = false, phase = "FAULT",
                    runtime = it.runtime.copy(wakeReleasedAtEpochMs = System.currentTimeMillis()),
                    message = "相机释放未确认；已停止等待并保留占用保护，请复制诊断") }
                persist()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }, 30_000)
    }
    private fun releasedWithoutResource(ticket: CameraLeaseTicket) { owner = null; apply(control.released(ticket)) }
    private fun stopRun(reason: SentryStopReason) {
        finishing = true; testMode.clear(); presence.end(); main.removeCallbacks(poll); main.removeCallbacks(runLimit)
        change { it.copy(runtime = it.runtime.copy(stopReason = reason.name)) }
        log("停止本次运行：$reason")
        apply(control.stop(reason)); persist(); finishIfIdle()
    }
    private fun recordFailure(reason: String) {
        if (mutableState.value.firstFailure != null) return
        change { GuardFailurePolicy.record(it, reason, System.currentTimeMillis(),
            SystemClock.elapsedRealtime(), ZeekrApp.isForeground.value) }
        log("首次异常：$reason；阶段=${mutableState.value.firstFailure?.phase}；应用前台=${ZeekrApp.isForeground.value}")
        persist()
    }
    private fun fail(reason: String) { recordFailure(reason); stopRun(SentryStopReason.FATAL_STOP) }
    private fun finishIfIdle() {
        if (!finishing || owner != null || capture != null || normal != null) return
        wake?.let { if (it.isHeld) it.release() }; wake = null; storageMonitor.stop()
        foreground = false
        change { it.copy(runtime = it.runtime.copy(wakeReleasedAtEpochMs = System.currentTimeMillis())) }
        sampleRuntime(heartbeat = false)
        change { it.copy(running = false, phase = if (it.error == null) "STOPPED" else "FAULT", mode = null,
            message = it.error ?: if (it.runtime.stopReason == SentryStopReason.RUN_LIMIT.name) "定时已结束，本次哨兵已停止"
                else "本次运行已停止；自动切换权限已撤销") }
        persist()
        val terminal = mutableState.value
        CameraRecordingService.publishGuardState(RecorderState(
            status = if (terminal.error == null) RecorderStatus.STOPPED else RecorderStatus.ERROR,
            lastError = terminal.error, message = terminal.message))
        if (!terminalReportQueued && !io.isShutdown) {
            terminalReportQueued = true
            io.execute { runCatching {
                writeReport(terminal)
                GuardReportStore(applicationContext).archive(terminal)
            } }
        }
        if (destroyed) { if (instance === this) instance = null; io.shutdown() }
        if (!destroyed) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    }
    private fun change(block: (GuardState) -> GuardState) {
        if (began && mutableState.value.runId != runId) return
        mutableState.value = GuardFailurePolicy.preserve(block(mutableState.value))
            .copy(updatedAtEpochMs = System.currentTimeMillis())
    }
    private fun log(value: String) { change { it.copy(journal = (it.journal + "${System.currentTimeMillis()} $value").takeLast(80)) } }
    private fun persist() {
        if (io.isShutdown) return
        reportDirty.set(true)
        if (!savingReport.compareAndSet(false, true)) return
        io.execute {
            try {
              while (reportDirty.getAndSet(false)) {
                writeReport(mutableState.value)
              }
            } catch (t: Throwable) { main.post { change { it.copy(error = "REPORT_WRITE_${t.javaClass.simpleName}") } } }
            finally { savingReport.set(false); if (reportDirty.get() && !io.isShutdown) persist() }
        }
    }
    private fun writeReport(value: GuardState) {
        if (value.runId != runId) return
        GuardReportStore(applicationContext).writeCurrent(value) { mutableState.value.runId == runId }
    }
    override fun onDestroy() {
        destroyed = true
        if (owner == null && instance === this) instance = null
        if (!finishing) stopRun(SentryStopReason.SERVICE_DESTROYED)
        storageMonitor.stop(); main.removeCallbacks(poll); main.removeCallbacks(runLimit)
        // Resource callbacks continue to own their close barrier, even after Service destruction.
        if (owner == null) { finishIfIdle(); io.shutdown() }
        super.onDestroy()
    }
    private fun mode(value: CaptureMode?) {
        if (value == CaptureMode.SENTRY && parkingBehavior != ParkingBehavior.SENTRY) return
        setPresenceOverride(value?.let { if (it == CaptureMode.SENTRY) VehiclePresencePhase.AWAY_CONFIRMED else VehiclePresencePhase.OCCUPIED })
    }
    private fun setPresenceOverride(value: VehiclePresencePhase?) {
        if (finishing || !control.permit.accepts(control.permit.generation)) return
        testMode.select(value?.let { if (it == VehiclePresencePhase.AWAY_CONFIRMED) CaptureMode.SENTRY else CaptureMode.NORMAL })
        log("切换控制：${testMode.override?.name ?: "AUTO"}")
        main.removeCallbacks(poll); main.post(poll)
    }

    companion object {
        private const val CHANNEL = "openavm_sentry_guard"
        private const val NOTIFICATION = 7402
        private const val START = "GUARD_START"
        private const val STOP = "GUARD_STOP"
        private const val RUN = "run"
        private const val PARKING = "parking"
        private const val DURATION = "duration"
        private val mutableState = MutableStateFlow(GuardState())
        val state = mutableState.asStateFlow()
        @Volatile private var instance: SentryGuardService? = null
        @Volatile private var starting = false
        @Volatile private var stopPending = false
        private val processId = UUID.randomUUID().toString()
        // In-process instrumentation seam; never persisted or exposed by an Intent.
        internal var recorderConfig: (Context) -> RecorderConfig? = {
            ProductRecorderConfigFactory.create(it, RecordingSourceRole.SURROUND)
        }
        fun isRunning() = instance != null || starting
        fun reportFile(context: Context) = File(context.filesDir, "sentry/guard-report.json")
        fun start(context: Context, parking: ParkingBehavior = ParkingBehavior.SENTRY,
                  duration: GuardRunDuration? = null) {
            if (!BuildConfig.SENTRY_INTEGRATED_ENABLED || !BuildConfig.SENTRY_CAPTURE_ENABLED || isRunning()) return
            val selectedDuration = duration ?: GuardSettings(context).runDuration
            starting = true; stopPending = false
            try {
                ContextCompat.startForegroundService(context, Intent(context, SentryGuardService::class.java).setAction(START)
                    .putExtra(PARKING, parking.name).putExtra(DURATION, selectedDuration.name))
            } catch (t: Throwable) { starting = false; throw t }
        }
        fun stop() {
            stopPending = true
            instance?.let { active -> active.main.post { active.stopRun(SentryStopReason.MANUAL_STOP) } }
        }
        fun parkNow() { instance?.let { active -> active.main.post { active.setPresenceOverride(VehiclePresencePhase.AWAY_CONFIRMED) } } }
        fun setMode(mode: CaptureMode?) { instance?.let { active -> active.main.post { active.mode(mode) } } }
        fun trigger() { instance?.let { active -> active.main.post {
            if (!active.finishing) { if (active.owner?.mode == CaptureMode.SENTRY) active.capture?.trigger() else active.normal?.bookmark() }
        } } }
        fun previewEnabled(enabled: Boolean) { instance?.normal?.setPreviewOutputEnabled(enabled) }
        fun previewSurface(surface: Surface) {
            val active = instance?.normal
            if (active == null) surface.release() else active.replacePreviewSurface(surface)
        }
        fun restore(context: Context) {
            if (instance != null || mutableState.value.runId.isNotEmpty()) return
            val original = mutableState.value
            val loaded = GuardReportStore(context).current() ?: return
            mutableState.compareAndSet(original, loaded.copy(running = false, phase = if (loaded.running) "INTERRUPTED" else loaded.phase,
                mode = null, runtime = loaded.runtime.copy(serviceForeground = false, wakeLockHeld = false),
                message = if (loaded.running) "上次运行中断，已停止；需要手动重新开始" else loaded.message))
        }
    }
}
