package com.dante.zeekrcapabilitylab.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.PopupMenu
import com.dante.zeekrcapabilitylab.MainActivity
import com.dante.zeekrcapabilitylab.ZeekrApp
import com.dante.zeekrcapabilitylab.product.SettingsStore
import com.dante.zeekrcapabilitylab.service.recorder.RecorderStatus
import com.dante.zeekrcapabilitylab.service.recorder.RecordingStorageKind
import com.dante.zeekrcapabilitylab.util.Utils
import com.dante.zeekrcapabilitylab.ui.product.OverlayChrome
import kotlin.math.roundToInt

/** Presentation owned by the existing recorder service. Never opens cameras or changes power policy. */
class RecordingStatusOverlay(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val windows = context.getSystemService(WindowManager::class.java)
    private var root: LinearLayout? = null
    private var status: TextView? = null
    private var modeButton: Button? = null
    private var bookmarkButton: Button? = null
    private var closed = false
    private var dismissed = false
    private var failed = false
    private var enabledBefore = false
    private val preferences = com.dante.zeekrcapabilitylab.mirror.MirrorPresentation(context)
    private val positionStore = context.getSharedPreferences("status_overlay_v2", Context.MODE_PRIVATE)
    private var docked = positionStore.getBoolean("docked", false)
    private var lastIncident: String? = null
    // Drag deltas use physical screen X; the content itself follows the language direction.
    @SuppressLint("RtlHardcoded")
    private val params = WindowManager.LayoutParams(dp(280), WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        x = positionStore.getInt(if(preferences.rightHandDrive) "rx" else "lx", if(preferences.rightHandDrive) context.resources.displayMetrics.widthPixels - dp(300) else dp(20))
        y = positionStore.getInt("y", dp(80))
    }
    private val tick = object : Runnable {
        override fun run() {
            if (closed) return
            runCatching { update() }.onFailure { failed = true; remove() }
            handler.postDelayed(this, 1000)
        }
    }
    init { handler.post(tick) }

    private fun update() {
        val enabled = SettingsStore.get(context).recordingOverlayEnabled || CameraRecordingService.keepFloatingControls
        if (enabled != enabledBefore) { failed = false; dismissed = false; enabledBefore = enabled }
        val state = CameraRecordingService.state.value
        val switching = CameraRecordingService.modeSwitchProgress.value
        val show = enabled && !failed && !dismissed && Settings.canDrawOverlays(context) &&
            !com.dante.zeekrcapabilitylab.mirror.FloatingMirrorService.active() &&
            com.dante.zeekrcapabilitylab.mirror.MirrorPreviewRuntime.evidence.destination != "OVERLAY" &&
            !ZeekrApp.isForeground.value && (switching != null || state.status !in setOf(RecorderStatus.IDLE, RecorderStatus.STOPPED))
        if (!show) { remove(); return }
        if (root == null) add()
        val label = switching?.let(RecordingModeOverlayMenu::progressLabel) ?: when (state.status) {
            RecorderStatus.RECORDING -> RecordingModeOverlayMenu.label(state.recordingMode, state.timeLapseMultiplier)
            RecorderStatus.FINALIZING -> Utils.t("Saving", "正在保存")
            RecorderStatus.STARTING -> Utils.t("Preparing", "正在准备")
            RecorderStatus.RESUMING -> Utils.t("Recovering recording", "正在恢复录像")
            RecorderStatus.WAITING_CAMERA -> Utils.t("Waiting for camera", "正在等待摄像头")
            else -> Utils.t("Check recorder", "请检查录像状态")
        }
        val storage = if (state.activeStorageKind == RecordingStorageKind.USB_MEDIASTORE) "USB" else Utils.t("Head unit", "车机")
        status?.text = if(docked) "$label\n$storage" else Utils.t("{0} · {1} · #{2}", "{0} · {1} · 第 {2} 段", label, storage, state.segmentNumber)
        if(state.incidentMessage != null && state.incidentMessage != lastIncident) {
            lastIncident = state.incidentMessage
            android.widget.Toast.makeText(context, state.incidentMessage, android.widget.Toast.LENGTH_LONG).show()
        }
        status?.setTextColor(if (switching == null && state.status == RecorderStatus.RECORDING) Color.rgb(157, 231, 188) else Color.rgb(255, 204, 128))
        modeButton?.isEnabled = RecordingModeOverlayMenu.available()
        bookmarkButton?.isEnabled = switching == null && state.status == RecorderStatus.RECORDING &&
            state.recordingMode == com.dante.zeekrcapabilitylab.service.recorder.RecordingMode.NORMAL
        clampPosition()
    }

    @SuppressLint("ClickableViewAccessibility", "RtlHardcoded")
    private fun add() {
        val previousCenter = params.x + params.width / 2
        params.width = dp(if(docked) 112 else 320)
        if (docked) {
            val screenWidth = context.resources.displayMetrics.widthPixels
            params.x = if (previousCenter < screenWidth / 2) 0 else (screenWidth - params.width).coerceAtLeast(0)
            positionStore.edit().putInt(if (preferences.rightHandDrive) "rx" else "lx", params.x).apply()
        }
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            OverlayChrome.frame(this)
            layoutDirection = if (com.dante.zeekrcapabilitylab.product.AppLanguage.language.isRtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        }
        val title = TextView(context).apply {
            textSize = 15f; gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(42)
            setPadding(dp(8), 0, dp(8), 0)
            contentDescription = Utils.t("Drag recording status", "拖动录像状态窗")
            setOnClickListener { if(docked) { docked = false; rebuild() } }
            setOnLongClickListener { menu(this); true }
        }
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        title.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; startX = params.x; startY = params.y; moved = false; false }
                MotionEvent.ACTION_MOVE -> {
                    if(!preferences.locked && kotlin.math.abs(event.rawX - downX) + kotlin.math.abs(event.rawY - downY) > dp(10)) {
                        moved = true; title.cancelLongPress()
                        params.x = startX + (event.rawX - downX).roundToInt()
                        params.y = startY + (event.rawY - downY).roundToInt()
                        runCatching { clampPosition() }.onFailure { failed = true; remove() }
                    }; moved
                }
                MotionEvent.ACTION_UP -> {
                    if(moved) {
                        positionStore.edit().putInt(if(preferences.rightHandDrive) "rx" else "lx", params.x).putInt("y", params.y).apply()
                        if(com.dante.zeekrcapabilitylab.mirror.MirrorLayoutPolicy.dockSide(params.x, params.width, context.resources.displayMetrics.widthPixels, dp(24)) != 0) { docked = true; rebuild() }
                    }; moved
                }
                MotionEvent.ACTION_CANCEL -> false
                else -> false
            }
        }
        layout.addView(title)
        if(docked) { windows.addView(layout, params); root = layout; status = title; return }
        val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        fun button(text: String, weight: Float, onClick: () -> Unit): Button {
            return Button(context).apply {
                this.text = text; textSize = 13f; isAllCaps = false
                minWidth = 0; minimumWidth = 0
                setPadding(dp(3), 0, dp(3), 0)
                OverlayChrome.button(this)
                setOnClickListener { onClick() }
                row.addView(this, LinearLayout.LayoutParams(0, dp(48), weight).apply {
                    marginStart = dp(2); marginEnd = dp(2); topMargin = dp(3); bottomMargin = dp(3)
                })
            }
        }
        button("OpenAVM", 1.3f) {
            runCatching { context.startActivity(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
        }
        bookmarkButton = button(Utils.t("Save clip", "保存片段"), 1f) { CameraRecordingService.bookmark(context) }
        modeButton = button(Utils.t("Mode", "模式"), 0.9f) { RecordingModeOverlayMenu.show(context, title) }
        button("⋯", 0.55f) { menu(title) }.contentDescription = Utils.t("Mirror actions", "后视镜操作")
        layout.addView(row)
        windows.addView(layout, params)
        root = layout; status = title
    }

    private fun rebuild() { positionStore.edit().putBoolean("docked", docked).apply(); remove(); update() }
    private fun menu(anchor: View) {
        val popup = PopupMenu(context, anchor)
        val actions = mutableMapOf<Int, () -> Unit>()
        fun item(label: String, action: () -> Unit) { val id = actions.size; actions[id] = action; popup.menu.add(0, id, id, label) }
        item(Utils.t("Mirror", "后视镜")) { com.dante.zeekrcapabilitylab.mirror.MirrorPreviewRuntime.restore() }
        if (RecordingModeOverlayMenu.available()) item(Utils.t("Recording mode", "录像模式")) { RecordingModeOverlayMenu.show(context, anchor) }
        item(if(docked) Utils.t("Expand", "展开") else Utils.t("Collapse", "收起")) { docked = !docked; rebuild() }
        item(if(preferences.locked) Utils.t("Unlock position", "解锁位置") else Utils.t("Lock position", "锁定位置")) { preferences.locked = !preferences.locked }
        item(Utils.t("Stop", "停止")) { CameraRecordingService.stop(context) }
        item(Utils.t("Hide recording status", "隐藏录像状态窗")) { dismissed = true; remove() }
        popup.setOnMenuItemClickListener { actions[it.itemId]?.invoke(); true }; popup.show()
    }

    private fun clampPosition() {
        val size = context.resources.displayMetrics
        params.x = params.x.coerceIn(0, (size.widthPixels - params.width).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (size.heightPixels - (root?.height?.takeIf { it > 0 } ?: dp(105))).coerceAtLeast(0))
        root?.let { windows.updateViewLayout(it, params) }
    }
    private fun remove() {
        val view = root
        root = null; status = null; modeButton = null; bookmarkButton = null
        if (view != null) runCatching { windows.removeView(view) }
    }
    fun showForModeSwitch() {
        if (closed) return
        dismissed = false; failed = false
    }
    fun close() { closed = true; handler.removeCallbacks(tick); remove() }
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
}
