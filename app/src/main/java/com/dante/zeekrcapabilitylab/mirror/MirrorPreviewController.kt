package com.dante.zeekrcapabilitylab.mirror

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.*
import android.widget.*
import androidx.core.content.ContextCompat
import com.dante.zeekrcapabilitylab.MainActivity
import com.dante.zeekrcapabilitylab.ZeekrApp
import com.dante.zeekrcapabilitylab.data.Categories
import com.dante.zeekrcapabilitylab.event.EventLogger
import com.dante.zeekrcapabilitylab.probe.camera.ProfileSize
import com.dante.zeekrcapabilitylab.product.FourLaneLensMode
import com.dante.zeekrcapabilitylab.product.SettingsStore
import com.dante.zeekrcapabilitylab.service.CameraRecordingService
import com.dante.zeekrcapabilitylab.service.recorder.*
import com.dante.zeekrcapabilitylab.ui.product.FourLaneDisplayMode
import com.dante.zeekrcapabilitylab.ui.product.FourLaneTextureContainer
import com.dante.zeekrcapabilitylab.util.Utils
import java.lang.ref.WeakReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MirrorHomeControls(val displayMode: FourLaneDisplayMode = FourLaneDisplayMode.FOUR_GRID,
    val zoom: Float = 1f, val interactive: Boolean = false, val sourceRole: RecordingSourceRole? = null)

/** UI hosts cannot open cameras or obtain a recording permit. Main thread only. */
object MirrorPreviewRuntime {
    private var home = WeakReference<FrameLayout>(null)
    private var controller = WeakReference<MirrorPreviewController>(null)
    private var dismissedSession: String? = null
    private val _homeControls = MutableStateFlow(MirrorHomeControls())
    val homeControls = _homeControls.asStateFlow()
    internal fun homeControls(value: MirrorHomeControls) { _homeControls.value = value }
    fun toggleHomeLane(lane: Int) { controller.get()?.toggleHomeLane(lane) }
    fun transformHome(zoom: Float, x: Float, y: Float) { controller.get()?.transformHome(zoom, x, y) }
    fun refreshHome() { controller.get()?.refreshSoon() }
    internal fun wasDismissed(session: String) = dismissedSession == session
    internal fun dismiss(session: String) { dismissedSession = session }
    fun restore() { FloatingMirrorService.open(ZeekrApp.appContext) }
    internal fun restoreDisplay() { dismissedSession = null; controller.get()?.restore() }
    internal fun dismissDisplay() { controller.get()?.dismiss() }
    var overlayVisible: Boolean = false; internal set
    @Volatile var evidence = MirrorEvidence(); internal set
    internal fun homeHost(): FrameLayout? = home.get()?.takeIf { it.isAttachedToWindow }
    internal fun install(value: MirrorPreviewController) { check(controller.get() == null); controller = WeakReference(value) }
    internal fun remove(value: MirrorPreviewController) {
        if (controller.get() === value) { controller.clear(); _homeControls.value = MirrorHomeControls() }
    }
    fun attachHome(view: FrameLayout) { home = WeakReference(view); controller.get()?.refreshSoon() }
    fun detachHome(view: FrameLayout) { if (home.get() === view) { home.clear(); controller.get()?.refreshSoon() } }
}

class MirrorHomeHost(context: Context) : FrameLayout(context) {
    private val information = TextView(context).apply {
        setTextColor(Color.WHITE); gravity = Gravity.CENTER
        setPadding(16, 16, 16, 16)
        text = Utils.t("Recording preview is preparing. If unavailable, stop recording before trying again.", "正在准备录像预览；若不可用，请先停止录像再重新尝试。")
    }
    init {
        setBackgroundColor(Color.BLACK)
        addView(information, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }
    fun showMessage(value: String) { information.text = value }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); MirrorPreviewRuntime.attachHome(this) }
    override fun onDetachedFromWindow() { MirrorPreviewRuntime.detachHome(this); super.onDetachedFromWindow() }
}

/**
 * Camera input belongs to an offscreen GL consumer for the entire manual surround run.
 * HOME/OVERLAY host display-only TextureViews; replacing one never replaces the camera output.
 * The recorder's output-release acknowledgement still owns the input texture lifetime.
 */
class MirrorPreviewController(private val context: Context, private val config: RecorderConfig,
    private val source: MirrorCameraSource, private val runId: String, private val cameraGeneration: Long,
    private val declaredSizeLookup: (suspend () -> ProfileSize?)? = null) {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val settings = SettingsStore.get(context)
    private val preferences = MirrorPresentation(context)
    private val freshness = MirrorFrameFreshness()
    private val displayRecovery = MirrorDisplayRecoveryBudget()
    private var glPreview: MirrorGlPreview? = null
    private var closed = false
    private var failed = false
    private var dismissed = MirrorPreviewRuntime.wasDismissed(runId)
    private var failure = ""
    private var bufferSize: ProfileSize? = null
    private var texture: SurfaceTexture? = null
    private var lease: RetainedPreviewLease? = null
    private var submitted = false
    private var textureAttached = false
    private var targetEnabled: Boolean? = null
    private var destination = MirrorDestination.NONE
    private var overlayWindow: MirrorOverlayWindow? = null
    private var renderer: FourLaneTextureContainer? = null
    private var presentation: FrameLayout? = null
    private var curtain: TextView? = null
    private var homeDisplayMode = FourLaneDisplayMode.FOUR_GRID
    private var homeViewport = MirrorViewport()
    private var viewport = preferences.viewport(preferences.selectedLane)
    private var viewportRearLane = preferences.selectedLane
    private var previousReason = ""
    private var lastIncidentMessage: String? = null
    private var photoBusy = false
    private var receiverRegistered = false
    private val cabin = config.source.sourceRole == RecordingSourceRole.CABIN
    private val displayReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // SCREEN_OFF may be delivered after SCREEN_ON or to a newly restored controller.
            // Never latch the old event: update() reconciles the current system snapshot.
            refreshSoon()
        }
    }
    private val tick = object : Runnable {
        override fun run() {
            if (closed) return
            runCatching { update() }.onFailure {
                EventLogger.markError(Categories.SYSTEM, "RECORDER_MIRROR_PRESENTATION_FAILED", "PRESENTATION_FAILED", it)
                fail("PRESENTATION_FAILED")
            }
            if (!closed) { handler.removeCallbacks(this); handler.postDelayed(this, 200) }
        }
    }
    init {
        check(config.mirrorPreviewEnabled && (MirrorPreviewPolicy.supports(config) || source.previewOnly && MirrorPreviewPolicy.supportsPreview(config)))
        MirrorPreviewRuntime.install(this)
        MirrorPreviewRuntime.homeControls(MirrorHomeControls(sourceRole = config.source.sourceRole))
        receiverRegistered = runCatching {
            ContextCompat.registerReceiver(context, displayReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
        }.isSuccess
        // Metadata only. Keep the declared composite detail instead of splitting
        // a small ordinary preview into four blurred strips.
        scope.launch {
            val chosen = withContext(Dispatchers.IO) { if (declaredSizeLookup != null) declaredSizeLookup.invoke() else runCatching {
                val map = context.getSystemService(CameraManager::class.java)
                    .getCameraCharacteristics(config.cameraId)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val sizes = map?.getOutputSizes(SurfaceTexture::class.java)?.map { ProfileSize(it.width, it.height) }.orEmpty()
                MirrorPreviewPolicy.previewSize(sizes, config.profile.size) ?: if (cabin) sizes
                    .filter { it.width > 0 && it.height > 0 && it.width.toLong() * it.height <= 9_000_000 }
                    .maxByOrNull { it.width.toLong() * it.height } else null
            }.getOrNull() }
            if (!closed) {
                bufferSize = chosen
                if (chosen == null) fail("NO_DECLARED_PREVIEW_SIZE") else {
                    log("BUFFER_SELECTED", "DECLARED_COMPOSITE")
                    glPreview = MirrorGlPreview.create(chosen.width, chosen.height, ready = { input ->
                        if (closed || failed) glPreview?.closeAfterProducer() else {
                            texture = input
                            val engine = checkNotNull(glPreview)
                            lease = RetainedPreviewLease { engine.closeAfterProducer() }
                            refreshSoon()
                        }
                    }, failed = { reason -> if (!closed && !failed) fail(reason) })
                    if (glPreview == null) fail("GL_PREVIOUS_CLEANUP_PENDING")
                }
            }
        }
        handler.post(tick)
    }

    fun refreshSoon() { if (!closed) { handler.removeCallbacks(tick); handler.post(tick) } }
    fun restore() { if (!closed && !failed) { dismissed = false; preferences.dock = 0; rebuildOverlay(); refreshSoon() } }
    fun dismiss() { dismissed = true; MirrorPreviewRuntime.dismiss(runId); refreshSoon() }

    fun toggleHomeLane(lane: Int) {
        // Back can still leave a selected lane while the display is unavailable.
        if (closed || cabin || lane !in 1..4) return
        homeDisplayMode = homeDisplayMode.toggleLane(lane)
        homeViewport = MirrorViewport()
        refreshSoon()
    }

    fun transformHome(zoom: Float, x: Float, y: Float) {
        if (closed || failed || destination != MirrorDestination.HOME || homeDisplayMode.singleLane == null) return
        renderer?.let { render ->
            render.applyViewportGesture(zoom, x, y)
            homeViewport = render.mirrorViewport()
            MirrorPreviewRuntime.homeControls(MirrorHomeControls(homeDisplayMode, homeViewport.zoom,
                freshness.isFresh(SystemClock.elapsedRealtime()), config.source.sourceRole))
        }
    }

    private fun update() {
        val state = source.state
        val now = SystemClock.elapsedRealtime()
        val recording = state.status in setOf(RecorderStatus.RECORDING, RecorderStatus.PREVIEWING)
        freshness.recorderProgress(state.segmentNumber, recording, now,
            continuousInput = state.recordingBackend == "SHARED_INPUT_CONTINUOUS_CODEC")
        if (!settings.mirrorPreviewEnabled && !failed) fail("DISABLED")
        if (!Settings.canDrawOverlays(context) && !failed) fail("OVERLAY_PERMISSION_LOST")
        val sameRun = MirrorPreviewPolicy.commandMatches(runId, cameraGeneration, state) && state.mirrorPreviewManaged
        val alive = sameRun && state.status in setOf(RecorderStatus.STARTING, RecorderStatus.RECORDING, RecorderStatus.PREVIEWING, RecorderStatus.FINALIZING)
        if (!alive && !failed) fail("RECORDING_ENDED")
        if (sameRun && submitted && state.previewFallbackUsed && !failed) fail("PREVIEW_CONFIG_REJECTED")
        val displayUsable = MirrorDisplayState.usable(context)
        val desired = MirrorPreviewPolicy.destination(alive && !failed && texture != null,
            ZeekrApp.isForeground.value, MirrorPreviewRuntime.homeHost() != null,
            Settings.canDrawOverlays(context), dismissed, displayUsable)
        if (desired != destination || (desired == MirrorDestination.HOME && presentation?.parent !== MirrorPreviewRuntime.homeHost())) moveTo(desired)
        if (desired != MirrorDestination.NONE) ensureSubmitted(state)
        val rear = preferences.selectedLane
        if (rear != viewportRearLane) { viewportRearLane = rear; viewport = preferences.viewport(rear) }
        val visible = desired != MirrorDestination.NONE && !failed && textureAttached &&
            presentation?.isShown == true && presentation?.windowVisibility == View.VISIBLE &&
            (desired == MirrorDestination.HOME || rear in 1..4)
        // Window changes do not gate the camera input. The offscreen consumer keeps draining.
        // Screen-off / lock still disables preview requests under the existing display policy.
        val expected = !failed && texture != null && displayUsable
        glPreview?.setInputPollingEnabled(expected)
        if (submitted && targetEnabled != expected) {
            targetEnabled = expected
            source.enable(expected, runId, cameraGeneration)
            freshness.presentationChanged(now)
        }
        val inputEvidence = glPreview?.evidence(now)
        if (!failed && submitted && expected && recording) {
            when (displayRecovery.evaluate(inputEvidence?.inputHeartbeatAgeMs, inputEvidence?.inputFrameAgeMs,
                sameRun && visible && freshness.outputTimedOut(now))) {
                MirrorDisplayRecoveryBudget.Action.REBUILD_DISPLAY -> {
                    rebuildDisplay()
                    log("DISPLAY_RECOVERY", "REPLACE_DISPLAY_ONLY")
                }
                MirrorDisplayRecoveryBudget.Action.SOURCE_RETURNED -> {
                    // Allow a new display timestamp to arrive before spending a recovery attempt.
                    if (!freshness.isFresh(now)) freshness.presentationChanged(now)
                    log("INPUT_RESUMED", "SOURCE_FRAMES_RETURNED")
                }
                MirrorDisplayRecoveryBudget.Action.INPUT_THREAD_FAILED -> fail("GL_INPUT_THREAD_STALLED")
                MirrorDisplayRecoveryBudget.Action.DISPLAY_FAILED -> fail("DISPLAY_RECOVERY_EXHAUSTED")
                else -> Unit
            }
        }
        val reason = when {
            failed -> failure
            !displayUsable -> "DISPLAY_HIDDEN"
            desired == MirrorDestination.NONE -> "HIDDEN"
            desired == MirrorDestination.OVERLAY && rear !in 1..4 -> "SELECT_REAR_VIEW"
            !recording -> "RECORDER_TRANSITION"
            displayRecovery.waitingForSource -> "WAITING_SOURCE_FRAMES"
            !freshness.isFresh(SystemClock.elapsedRealtime()) -> "NO_FRESH_FRAME"
            else -> "LIVE"
        }
        renderer?.apply {
            (if (cabin) bufferSize else state.profile?.size)?.let { setCompositeSize(it.width, it.height) }
            singleSource = cabin
            displayMode = if (destination == MirrorDestination.OVERLAY && preferences.grid && !cabin) FourLaneDisplayMode.FOUR_GRID
                else if (destination == MirrorDestination.OVERLAY && rear in 1..4) FourLaneDisplayMode.forLane(rear) else homeDisplayMode
            fitSingleLane = destination == MirrorDestination.OVERLAY
            lensMode = if (fitSingleLane) preferences.lens(rear) else settings.lensMode
            correctionConfig = if (fitSingleLane) settings.fisheyeCorrection.copy(targetFovDegrees = preferences.fov(rear)) else settings.fisheyeCorrection
            val panel = preferences.panel(rear)
            singleLaneRotation = panel.rotation
            mirrorSingleLane = panel.mirrored
            mirrorPanels = when {
                cabin || !fitSingleLane -> emptyList()
                preferences.grid -> MirrorLayoutPolicy.grid(preferences.directionLanes())?.map { preferences.panel(it).copy(viewport = MirrorViewport()) }.orEmpty()
                preferences.triple -> preferences.lanes()?.map(preferences::panel).orEmpty()
                else -> emptyList()
            }
            setMirrorViewport(if (fitSingleLane) viewport else homeViewport)
        }
        updateOverlayControls()
        if (state.incidentMessage != null && state.incidentMessage != lastIncidentMessage) {
            lastIncidentMessage = state.incidentMessage; toast(state.incidentMessage)
        }
        (MirrorPreviewRuntime.homeHost() as? MirrorHomeHost)?.showMessage(message(reason))
        curtain?.apply { visibility = if (reason == "LIVE") View.GONE else View.VISIBLE; text = message(reason) }
        MirrorPreviewRuntime.homeControls(MirrorHomeControls(homeDisplayMode, homeViewport.zoom,
            destination == MirrorDestination.HOME && reason == "LIVE", config.source.sourceRole))
        MirrorPreviewRuntime.evidence = MirrorEvidence(alive && !failed, destination.name, reason, freshness.frames,
            freshness.age(SystemClock.elapsedRealtime()), rear, settings.mirrorRotation, settings.mirrorHorizontal,
            config.profile.size.width, config.profile.size.height, bufferSize?.width, bufferSize?.height,
            renderer?.textureView?.width, renderer?.textureView?.height, renderer?.lensMode?.name, viewport.zoom, cameraGeneration)
            .let(::withDisplayTelemetry)
        if (reason != previousReason) { previousReason = reason; log("STATE", reason) }
    }

    private fun rebuildDisplay() {
        val previous = destination
        moveTo(MirrorDestination.NONE)
        renderer = null; presentation = null; curtain = null
        moveTo(previous)
    }

    private fun createPresentation() {
        if (presentation != null) return
        val render = FourLaneTextureContainer(context).apply {
            val declared = checkNotNull(bufferSize)
            // This is a GL display buffer, not a camera buffer. Preserve full composite detail.
            fixedInputViewSize = android.util.Size(declared.width, declared.height)
            setBackgroundColor(Color.BLACK)
            setCompositeSize(config.profile.size.width, config.profile.size.height)
            singleSource = cabin
            lensMode = FourLaneLensMode.FISHEYE
        }
        val body = FrameLayout(context)
        val mask = TextView(context).apply {
            setBackgroundColor(Color.BLACK); setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8)); text = message("NO_FRESH_FRAME")
        }
        body.addView(render, FrameLayout.LayoutParams(-1, -1))
        body.addView(mask, FrameLayout.LayoutParams(-1, -1))
        renderer = render; presentation = body; curtain = mask
        render.textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(available: SurfaceTexture, width: Int, height: Int) {
                if (closed || failed || render !== renderer) return
                try {
                    val size = checkNotNull(bufferSize)
                    available.setDefaultBufferSize(size.width, size.height)
                    checkNotNull(glPreview).attachDisplay(available)
                    textureAttached = true
                    freshness.presentationChanged(SystemClock.elapsedRealtime()); refreshSoon()
                } catch (_: Exception) { fail("TEXTURE_ATTACH_FAILED") }
            }
            override fun onSurfaceTextureDestroyed(destroyed: SurfaceTexture): Boolean {
                if (render === renderer) textureAttached = false
                refreshSoon()
                // The GL window producer may still be inside swap. Its acknowledgement owns release.
                return glPreview?.destroyDisplay(destroyed) ?: true
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                if (!closed && !failed && render === renderer) {
                    val before = freshness.frames
                    freshness.frame(surface.timestamp, SystemClock.elapsedRealtime())
                    if (freshness.frames > before) {
                        source.displayedFrame(runId, cameraGeneration)
                    }
                }
            }
        }
    }

    private fun ensureSubmitted(state: RecorderState) {
        if (submitted || failed || closed || state.status !in setOf(RecorderStatus.RECORDING, RecorderStatus.PREVIEWING)) return
        val retained = texture ?: return
        val currentLease = lease ?: return
        val surface = Surface(retained)
        currentLease.acquireProducer()
        submitted = true; targetEnabled = true
        freshness.presentationChanged(SystemClock.elapsedRealtime())
        // One initial output insertion. All later page/window switches keep this Surface.
        source.replace(surface, runId, cameraGeneration) {
            handler.post {
                currentLease.producerReleased()
                if (!closed && !failed) fail("OUTPUT_RELEASED")
            }
        }
        log("OUTPUT_SUBMITTED", "FIRST_OUTPUT")
    }

    private fun moveTo(next: MirrorDestination) {
        if (next != MirrorDestination.NONE) createPresentation()
        // The independent camera consumer survives this window operation without request/session changes.
        (presentation?.parent as? ViewGroup)?.removeView(presentation)
        removeOverlay()
        destination = next
        freshness.presentationChanged(SystemClock.elapsedRealtime())
        when (next) {
            MirrorDestination.HOME -> MirrorPreviewRuntime.homeHost()?.addView(presentation, FrameLayout.LayoutParams(-1, -1))
            MirrorDestination.OVERLAY -> addOverlay()
            MirrorDestination.NONE -> Unit
        }
        log("DESTINATION", next.name)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addOverlay() {
        FloatingMirrorService.hideFallbackForLive()
        val window = MirrorOverlayWindow(context, preferences,
            onRecord = { if (source.previewOnly) FloatingMirrorService.recordControl() else source.stop() },
            onMode = { FloatingMirrorService.selectMode(it) },
            onVideo = { FloatingMirrorService.setVideoVisible(it); refreshSoon() },
            onClose = { FloatingMirrorService.close(); dismiss(); if (source.previewOnly) source.stop() },
            onPhoto = ::savePhoto, onLane = ::selectLane, onCabin = { preferences.grid = false; FloatingMirrorService.selectCabin() },
            onEmergency = ::saveEmergencyVideo,
            onGrid = { if (cabin) FloatingMirrorService.selectSurround(); refreshSoon() },
            onLens = { preferences.triple = false; preferences.setLens(viewportRearLane, it); refreshSoon() },
            onZoom = { adjustFraming(it, 0f, 0f); preferences.save(viewportRearLane, viewport) },
            onReset = ::resetFraming,
            onMenu = { anchor -> FloatingMirrorService.basicMenu(context, anchor, overlayWindow) { menu ->
                menu.menu.add(Utils.t("Save emergency video", "保存紧急视频")).apply {
                    isEnabled = !closed && !failed && MirrorEmergencyAction.allowed(source.previewOnly,
                        source.state.status, source.state.recordingMode,
                        FloatingMirrorService.state.value.busy || CameraRecordingService.modeSwitchProgress.value != null)
                    setOnMenuItemClickListener { saveEmergencyVideo(); true }
                }
                menu.menu.add(Utils.t("View presets and saved clips", "视角预设与保存片段"))
                    .setOnMenuItemClickListener { quickMenu(anchor); true }
            } })
        overlayWindow = window
        val host = window.videoHost
        host.addView(presentation, FrameLayout.LayoutParams(-1, -1))
        val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (!cabin && !preferences.triple && !preferences.grid) adjustFraming(detector.scaleFactor, 0f, 0f)
                return true
            }
        })
        val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent) = true
            override fun onScroll(first: MotionEvent?, event: MotionEvent, dx: Float, dy: Float): Boolean {
                if (!cabin && !preferences.triple && !preferences.grid && !scale.isInProgress && event.pointerCount == 1) adjustFraming(1f, -dx, -dy)
                return true
            }
            override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
                if (preferences.grid) MirrorLayoutPolicy.grid(preferences.directionLanes())?.getOrNull(
                    MirrorLayoutPolicy.gridAt(event.x / host.width.coerceAtLeast(1), event.y / host.height.coerceAtLeast(1)))?.let(::selectLane)
                else if (preferences.triple) preferences.lanes()?.getOrNull(MirrorLayoutPolicy.panelAt(event.x / host.width.coerceAtLeast(1)))?.let(::selectLane)
                return true
            }
            override fun onDoubleTap(event: MotionEvent): Boolean { if (!cabin && !preferences.triple && !preferences.grid) resetFraming(); return true }
        })
        host.setOnTouchListener { _, event ->
            if (failed) false else {
                scale.onTouchEvent(event); gestures.onTouchEvent(event)
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    preferences.save(viewportRearLane, viewport)
                    if (event.actionMasked == MotionEvent.ACTION_UP) host.performClick()
                }
                true
            }
        }
        updateOverlayControls()
        window.show()
        MirrorPreviewRuntime.overlayVisible = true
    }

    private fun updateOverlayControls() {
        val current = source.state
        val choice = if (source.previewOnly) FloatingMirrorService.idleMode() else RecordingModeChoice(current.recordingMode, current.timeLapseMultiplier)
        val busy = FloatingMirrorService.state.value.busy || CameraRecordingService.modeSwitchProgress.value != null ||
            !source.previewOnly && current.status != RecorderStatus.RECORDING
        val label = when {
            source.previewOnly && busy -> Utils.t("Preparing live preview…", "正在恢复预览…")
            busy -> Utils.t("Switching / saving…", "正在切换／保存…")
             cabin -> if (source.previewOnly) Utils.t("Cabin preview · not recording", "车内预览 · 未录像") else Utils.t("Recording cabin", "正在录制车内")
             source.previewOnly -> Utils.t("Live preview · not recording", "实时预览 · 未录像")
            else -> com.dante.zeekrcapabilitylab.service.RecordingModeOverlayMenu.label(choice.mode, choice.multiplier)
        }
        overlayWindow?.render(MirrorOverlayState(label, recording = !source.previewOnly, busy = busy,
            canStopWhileBusy = FloatingMirrorService.recordingTransferPending() ||
                current.status in setOf(RecorderStatus.WAITING_CAMERA, RecorderStatus.RESUMING),
            canChangeMode = source.previewOnly || com.dante.zeekrcapabilitylab.service.RecordingModeOverlayMenu.available(),
            canPhoto = !cabin && !preferences.grid && !photoBusy && !failed && freshness.isFresh(SystemClock.elapsedRealtime()),
            mode = choice.mode, multiplier = if (choice.mode == RecordingMode.NORMAL) settings.timeLapseMultiplier else choice.multiplier,
            zoom = viewport.zoom, cabin = cabin,
            canSaveEmergency = !closed && !failed && MirrorEmergencyAction.allowed(source.previewOnly, current.status, current.recordingMode, busy),
            elapsedMs = current.sessionStartedAtEpochMs?.let { System.currentTimeMillis() - it } ?: 0))
    }

    private fun saveEmergencyVideo() {
        val current = source.state
        val busy = FloatingMirrorService.state.value.busy || CameraRecordingService.modeSwitchProgress.value != null
        if (closed || failed || !MirrorEmergencyAction.allowed(source.previewOnly, current.status, current.recordingMode, busy)) return
        source.bookmark()
        // Receipt is not a claim that current/future video files are already finalized.
        toast(Utils.t("Marking emergency video…", "正在标记紧急视频…"))
    }

    private fun selectLane(lane: Int) {
        preferences.save(viewportRearLane, viewport)
        preferences.selectedLane = lane; preferences.triple = false; preferences.grid = false
        viewportRearLane = lane; viewport = preferences.viewport(lane); refreshSoon()
        if (cabin) FloatingMirrorService.selectSurround()
    }

    private fun quickMenu(anchor: View) {
        val menu = PopupMenu(context, anchor)
        val actions = mutableMapOf<Int, () -> Unit>()
        fun item(label: String, enabled: Boolean = true, action: () -> Unit) {
            val id = actions.size + 1; actions[id] = action; menu.menu.add(0, id, id, label).isEnabled = enabled
        }
        if (preferences.dock != 0) item(Utils.t("Restore mirror", "展开后视镜")) { preferences.dock = 0; rebuildOverlay() }
        if (!source.previewOnly) item(Utils.t("Save emergency video", "保存紧急视频"),
            MirrorEmergencyAction.allowed(source.previewOnly, source.state.status, source.state.recordingMode,
                FloatingMirrorService.state.value.busy || CameraRecordingService.modeSwitchProgress.value != null)) { saveEmergencyVideo() }
        item(Utils.t("3 views", "三视图"), !cabin && preferences.lanes() != null) { preferences.grid = false; preferences.triple = !preferences.triple; refreshSoon() }
        item(Utils.t("Standard view", "标准视角")) { preferences.triple = false; preferences.setLens(viewportRearLane, FourLaneLensMode.STANDARD); preferences.setWide(viewportRearLane, false); resetFraming() }
        item(Utils.t("Wide view", "宽视野")) { preferences.triple = false; preferences.setLens(viewportRearLane, FourLaneLensMode.STANDARD); preferences.setWide(viewportRearLane, true); viewport = MirrorViewport(); preferences.save(viewportRearLane, viewport); refreshSoon() }
        item(Utils.t("Save original view frame to USB", "保存当前视角原始帧到 USB"), !cabin && !preferences.grid && !photoBusy && !failed && destination != MirrorDestination.NONE && preferences.dock == 0) { savePhoto() }
        (1..2).forEach { slot ->
            item(Utils.t("Load preset {0}", "读取预设 {0}", slot), preferences.hasPreset(viewportRearLane, slot)) {
                preferences.triple = false; viewport = preferences.viewport(viewportRearLane, slot)
                preferences.setWide(viewportRearLane, preferences.wide(viewportRearLane, slot))
                preferences.save(viewportRearLane, viewport); refreshSoon()
            }
            item(Utils.t("Save preset {0}", "保存预设 {0}", slot), viewportRearLane in 1..4 && !preferences.triple) {
                preferences.save(viewportRearLane, viewport, slot)
                preferences.setWide(viewportRearLane, preferences.wide(viewportRearLane), slot)
                toast(Utils.t("Preset saved", "预设已保存"))
            }
        }
        item(if (preferences.locked) Utils.t("Unlock position", "解锁位置") else Utils.t("Lock position", "锁定位置")) { preferences.locked = !preferences.locked }
        item("OpenAVM") { context.startActivity(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
        item(Utils.t("Stop", "停止")) { source.stop() }
        menu.setOnMenuItemClickListener { actions[it.itemId]?.invoke(); true }
        menu.show()
    }

    private fun toast(value: String) { Toast.makeText(context, value, Toast.LENGTH_SHORT).show() }
    private fun savePhoto() {
        if (closed || failed || cabin || preferences.grid || photoBusy || !preferences.videoVisible || curtain?.visibility != View.GONE || !freshness.isFresh(SystemClock.elapsedRealtime())) return
        val lane = if (preferences.triple) settings.mirrorRearLane else viewportRearLane
        val size = bufferSize ?: return
        val view = renderer?.textureView ?: return
        if (lane !in 1..4 || size.width.toLong() * size.height > 9_000_000 || !view.isAvailable) return
        photoBusy = true
        val panel = preferences.panel(lane)
        val crop = com.dante.zeekrcapabilitylab.player.FourLaneTextureLayout.windowForLane(size.width, size.height, lane)
        // Read the full input buffer once; save one original lane. Display dewarp/pan are intentionally not baked in.
        val raw = runCatching { view.getBitmap(size.width, size.height) }.getOrNull()
        if (raw == null) { photoBusy = false; toast(Utils.t("No fresh preview frame", "暂无可用预览帧")); return }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val result = withContext(Dispatchers.IO) { runCatching {
                    val matrix = android.graphics.Matrix().apply { postRotate(panel.rotation.toFloat()); if (panel.mirrored) postScale(-1f, 1f) }
                    val cropped = android.graphics.Bitmap.createBitmap(raw, crop.sourceLeftPx.toInt(), crop.sourceTopPx.toInt(),
                        crop.sourceWidthPx.toInt(), crop.sourceHeightPx.toInt(), matrix, true)
                    try { PreviewPhotos.save(context, cropped, lane) } finally { if (cropped !== raw) cropped.recycle() }
                } }
                result.fold({ toast(Utils.t("Frame saved to USB Pictures/OpenAVM", "画面已保存到 USB Pictures/OpenAVM")) },
                    { toast(Utils.t("Could not save frame. Connect one writable USB drive.", "保存画面失败，请连接一个可写入的 USB。")) })
            } finally { raw.recycle(); photoBusy = false }
        }
    }

    private fun rebuildOverlay() {
        if (destination == MirrorDestination.OVERLAY) {
            moveTo(MirrorDestination.NONE); moveTo(MirrorDestination.OVERLAY)
        }
        refreshSoon()
    }

    private fun adjustFraming(scale: Float, x: Float, y: Float) {
        val render = renderer ?: return
        if (preferences.triple || !render.fitSingleLane || render.displayMode.singleLane == null) return
        render.applyViewportGesture(scale, x, y)
        viewport = render.mirrorViewport(); updateOverlayControls()
    }

    private fun resetFraming() {
        viewport = MirrorViewport()
        renderer?.setMirrorViewport(viewport)
        preferences.save(viewportRearLane, viewport); updateOverlayControls(); refreshSoon()
    }

    private fun removeOverlay() {
        overlayWindow?.close(); overlayWindow = null
        MirrorPreviewRuntime.overlayVisible = false
    }

    private fun withDisplayTelemetry(evidence: MirrorEvidence): MirrorEvidence {
        val now = SystemClock.elapsedRealtime()
        val view = renderer?.textureView
        return evidence.copy(
            textureCallbacks = freshness.callbacks,
            duplicateTimestampCallbacks = freshness.duplicateTimestampCallbacks,
            invalidTimestampCallbacks = freshness.invalidTimestampCallbacks,
            lastTextureCallbackAgeMs = freshness.callbackAge(now),
            lastTextureTimestampNs = freshness.lastTextureTimestampNs,
            drawPasses = renderer?.diagnosticDrawPasses ?: 0,
            lastDrawAgeMs = renderer?.diagnosticLastDrawElapsedMs?.takeIf { it <= now }?.let { now - it },
            textureAttached = textureAttached, textureAvailable = view?.isAvailable,
            textureShown = view?.isShown, hardwareAccelerated = view?.isHardwareAccelerated,
            displayRecoveryAttempts = displayRecovery.attempts,
            gl = glPreview?.evidence(now) ?: MirrorGlEvidence(),
        )
    }

    private fun fail(reason: String) {
        if (failed || closed) return
        failed = true; failure = reason
        glPreview?.setInputPollingEnabled(false)
        // Capture the failing display together with the independent recording
        // callback evidence BEFORE detaching changes the visible destination.
        MirrorPreviewRuntime.evidence = MirrorPreviewRuntime.evidence.copy(active = false, reason = reason,
            frames = freshness.frames, lastFrameAgeMs = freshness.age(SystemClock.elapsedRealtime()))
            .let(::withDisplayTelemetry)
        log("UNAVAILABLE", reason)
        if (com.dante.zeekrcapabilitylab.diagnostic.ShortRecorderReport.shouldRecordPreviewFault(
                reason, source.state.status, !source.state.lastError.isNullOrBlank())) {
            com.dante.zeekrcapabilitylab.diagnostic.ShortRecorderDiagnostics.recordPreviewFault(context, source.state)
        }
        runCatching { moveTo(MirrorDestination.NONE) }
        if (submitted && targetEnabled != false) { source.enable(false, runId, cameraGeneration); targetEnabled = false }
        lease?.retire()
        if (lease == null) glPreview?.closeAfterProducer()
        MirrorPreviewRuntime.evidence = MirrorPreviewRuntime.evidence.copy(destination = "NONE", reason = reason)
        if (source.previewOnly) { FloatingMirrorService.previewFailed(reason); source.stop() }
    }

    fun close() {
        if (closed) return
        closed = true
        glPreview?.setInputPollingEnabled(false)
        handler.removeCallbacks(tick); scope.cancel()
        if (receiverRegistered) { runCatching { context.unregisterReceiver(displayReceiver) }; receiverRegistered = false }
        runCatching { moveTo(MirrorDestination.NONE) }
        if (submitted && targetEnabled != false) { source.enable(false, runId, cameraGeneration); targetEnabled = false }
        lease?.retire()
        if (lease == null) glPreview?.closeAfterProducer()
        MirrorPreviewRuntime.evidence = MirrorPreviewRuntime.evidence.copy(active = false, destination = "NONE", reason = "CLOSED")
        MirrorPreviewRuntime.remove(this)
        // No timeout releases the texture. The recorder callback may arrive after Service.onDestroy.
    }

    /** A diagnostic observer may wait for native cleanup; a timeout never implies success. */
    fun cleanupConfirmed(): Boolean = closed && (glPreview?.cleanupConfirmed() ?: true)

    private fun message(reason: String) = when (reason) {
        "NO_DECLARED_PREVIEW_SIZE", "PREVIEW_CONFIG_REJECTED" -> Utils.t("High-resolution preview unavailable. Recording can continue; check diagnostics.", "高清预览不可用；录像可继续，请查看诊断。")
        failure.takeIf { failed } -> Utils.t("Preview stopped. Check recording status; stop and start manually to retry.", "预览已停止，请检查录像状态；手动停止并重新开始可重试。")
        "SELECT_REAR_VIEW" -> Utils.t("Select the rear camera view in Settings first.", "请先在设置中选择后方对应的视角。")
        "WAITING_SOURCE_FRAMES" -> Utils.t("Camera picture interrupted · waiting for new frames", "相机画面暂时中断 · 等待新画面恢复")
        "RECORDER_TRANSITION" -> Utils.t("Recording is changing segment. Waiting for live view…", "录像正在切换分段，等待实时画面…")
        else -> Utils.t("Live view unavailable · waiting for a new frame", "实时画面暂不可用 · 等待新画面")
    }
    private fun log(event: String, reason: String) = EventLogger.logEvent(Categories.SYSTEM, "RECORDER_MIRROR_$event",
        payload = mapOf("reason" to reason, "frames" to freshness.frames.toString(),
            "segmentNumber" to source.state.segmentNumber.toString(),
            "status" to source.state.status,
            "previewActive" to source.state.previewActive.toString(),
            "previewRequested" to source.state.previewRequested.toString(),
            "bufferWidth" to (bufferSize?.width?.toString() ?: "unknown"),
            "bufferHeight" to (bufferSize?.height?.toString() ?: "unknown"),
            "cameraGeneration" to cameraGeneration.toString(),
            "lastFrameAgeMs" to (freshness.age(SystemClock.elapsedRealtime())?.toString() ?: "unknown")))
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
