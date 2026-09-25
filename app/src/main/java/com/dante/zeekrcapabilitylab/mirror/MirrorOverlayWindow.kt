package com.dante.zeekrcapabilitylab.mirror

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.view.*
import android.widget.*
import com.dante.zeekrcapabilitylab.product.FourLaneLensMode
import com.dante.zeekrcapabilitylab.product.SettingsStore
import com.dante.zeekrcapabilitylab.service.recorder.*
import com.dante.zeekrcapabilitylab.ui.product.OverlayChrome
import com.dante.zeekrcapabilitylab.util.Utils
import kotlin.math.roundToInt

data class MirrorOverlayState(
    val status: String, val recording: Boolean = false, val busy: Boolean = false,
    val canRecord: Boolean = true, val canChangeMode: Boolean = true, val canPhoto: Boolean = false,
    val mode: RecordingMode = RecordingMode.NORMAL, val multiplier: Int = TimeLapsePolicy.DEFAULT_MULTIPLIER,
    val zoom: Float = 1f, val canStopWhileBusy: Boolean = false, val elapsedMs: Long = 0,
    val cabin: Boolean = false, val canCabin: Boolean = true, val error: Boolean = false,
    val canSaveEmergency: Boolean = false,
)

/** Presentation only. Layout, grid and popovers never acquire a camera or recording permit. */
@SuppressLint("ClickableViewAccessibility", "RtlHardcoded")
class MirrorOverlayWindow(
    private val context: Context, private val preferences: MirrorPresentation,
    private val onRecord: () -> Unit, private val onMode: (RecordingModeChoice) -> Unit,
    private val onVideo: (Boolean) -> Unit, private val onClose: () -> Unit,
    private val onPhoto: () -> Unit, private val onLane: (Int) -> Unit,
    private val onLens: (FourLaneLensMode) -> Unit, private val onZoom: (Float) -> Unit,
    private val onReset: () -> Unit, private val onMenu: (View) -> Unit,
    private val onCabin: () -> Unit = {}, private val onGrid: () -> Unit = {},
    private val onEmergency: () -> Unit = {},
) {
    private val windows = context.getSystemService(WindowManager::class.java)
    val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(4)); OverlayChrome.frame(this)
    }
    val videoHost = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
    private val body = FrameLayout(context)
    private val content = LinearLayout(context)
    private val rail = FrameLayout(context)
    private val upperControls = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val lowerControls = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val title = TextView(context).apply {
        setTextColor(Color.WHITE); textSize = 12f; gravity = Gravity.CENTER_VERTICAL
        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dp(4), 0, dp(4), 0)
        contentDescription = Utils.t("Drag rear view", "拖动后视镜窗口")
    }
    private val params = WindowManager.LayoutParams(dp(preferences.widthDp), -2,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.LEFT }
    private var attached = false
    private var initialized = false
    private var appliedBounds: List<Int>? = null
    private var compact = false
    private var lastState = MirrorOverlayState(Utils.t("Preparing preview", "正在准备预览"), busy = true)
    private var popup: PopupWindow? = null
    internal var popupContent: View? = null
        private set
    private var popupRate: MirrorRateControl? = null
    private val selector = selector()
    private val miniArt = MirrorCameraSelector.LogoDrawable()
    private val miniCamera: Button
    private val grid: Button
    private val lens: Button
    private val record: Button
    private val photo: Button
    private val normal: Button
    private val lapse: Button
    private val rate: MirrorRateControl
    private val mode: Button
    private val tools: Button
    private val headerRecord: Button
    private val headerEmergency: Button
    private val emergency: Button
    private val fold: Button
    private val video: Button
    private val resize: Button
    private val modeRow = LinearLayout(context)
    private val labels = object : FrameLayout(context) {
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            for (i in 0 until childCount) {
                val label = getChildAt(i)
                val x = (i % 2) * width / 2 + dp(5)
                val y = (i / 2 + 1) * height / 2 - dp(30)
                label.layout(x, y, x + label.measuredWidth, y + dp(24))
            }
        }
    }
    private fun button(row: LinearLayout, textValue: String, description: String = textValue,
                       fixed: Boolean = false, action: () -> Unit): Button = Button(context).apply {
        text = textValue; contentDescription = description; tooltipText = description
        textSize = 12f; isAllCaps = false
        minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0; setPadding(dp(2), 0, dp(2), 0)
        OverlayChrome.button(this); setOnClickListener { action() }
        row.addView(this, LinearLayout.LayoutParams(if (fixed) dp(44) else 0, dp(44), if (fixed) 0f else 1f))
    }
    private fun railRow(row: View, parent: LinearLayout = upperControls, height: Int = 44) {
        parent.addView(row, LinearLayout.LayoutParams(-1, dp(height)).apply { topMargin = dp(4) })
    }
    private fun selector() = MirrorCameraSelector(context, (0..3).map(preferences::directionLabel), Utils.t("Cabin", "车内"),
        onDirection = { slot, anchor ->
            val lane = preferences.directionLanes()[slot]
            if (lane in 1..4) { preferences.grid = false; dismissPopup(); onLane(lane); render(lastState) }
            else chooseDirection(slot, anchor)
        }, onCabin = { preferences.grid = false; dismissPopup(); onCabin() })
    init {
        val header = LinearLayout(context)
        header.addView(title, LinearLayout.LayoutParams(0, dp(44), 1f))
        headerEmergency = button(header, Utils.t("SOS", "紧急"), Utils.t("Save emergency video", "保存紧急视频"), true) {
            if (lastState.canSaveEmergency && !lastState.busy && !lastState.error) onEmergency()
        }.apply { textSize = 11f }
        headerRecord = button(header, "●", Utils.t("Start recording", "开始录像"), true, onRecord)
        fold = button(header, "›", Utils.t("Hide controls", "收起控制栏"), true) {
            dismissPopup(); preferences.controlsVisible = !preferences.controlsVisible; render(lastState)
        }
        video = button(header, "↙", Utils.t("Hide video", "隐藏视频"), true) {
            dismissPopup(); preferences.videoVisible = !preferences.videoVisible
            render(lastState); onVideo(preferences.videoVisible)
        }
        button(header, "⋯", Utils.t("Mirror actions", "后视镜操作"), true) { dismissPopup(); onMenu(title) }
        button(header, "×", Utils.t("Close window; keep recording", "关闭窗口，录像继续"), true) { dismissPopup(); onClose() }
        root.addView(header)
        content.addView(videoHost, LinearLayout.LayoutParams(0, -1, 1f))
        content.addView(rail, LinearLayout.LayoutParams(dp(156), -1).apply { marginStart = dp(4) })
        body.addView(content, FrameLayout.LayoutParams(-1, -1))
        root.addView(body, LinearLayout.LayoutParams(-1, dp(540)).apply { topMargin = dp(4) })
        rail.addView(upperControls, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        rail.addView(lowerControls, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        rail.addView(selector, FrameLayout.LayoutParams(dp(156), dp(156), Gravity.CENTER))
        val miniRow = LinearLayout(context)
        miniCamera = button(miniRow, "", Utils.t("Choose camera", "选择摄像头")) { showCameras() }.apply { background = miniArt }
        rail.addView(miniRow, FrameLayout.LayoutParams(-1, dp(44), Gravity.CENTER))
        val lensRow = LinearLayout(context)
        lens = button(lensRow, "", Utils.t("Switch standard / fisheye", "切换标准／鱼眼")) { changeLens() }
        railRow(lensRow)
        val views = LinearLayout(context)
        grid = button(views, "⊞", Utils.t("Four-view preview", "四宫格预览")) { toggleGrid() }
        railRow(views)
        val toolsRow = LinearLayout(context)
        tools = button(toolsRow, "⌖", Utils.t("Framing and photo", "取景与拍照")) { showTools() }.apply { textSize = 24f }
        railRow(toolsRow)
        val recordRow = LinearLayout(context)
        record = button(recordRow, "", Utils.t("Start recording", "开始录像"), action = onRecord)
        railRow(recordRow, lowerControls)
        val photoRow = LinearLayout(context)
        photo = button(photoRow, Utils.t("◎ Photo", "◎ 拍照"), Utils.t("Photo", "拍照"), action = onPhoto)
        railRow(photoRow)
        val emergencyRow = LinearLayout(context)
        emergency = button(emergencyRow, Utils.t("Save emergency video", "保存紧急视频")) {
            if (lastState.canSaveEmergency && !lastState.busy && !lastState.error) onEmergency()
        }
        railRow(emergencyRow, lowerControls)
        val normalRow = LinearLayout(context)
        normal = button(normalRow, Utils.t("Normal recording", "普通录像"), Utils.t("Normal recording", "普通录像")) { chooseNormal() }
        railRow(normalRow, lowerControls)
        lapse = button(modeRow, Utils.t("Lapse", "延时录像"), Utils.t("Time-lapse recording", "延时录像")) { chooseLapse() }
        rate = MirrorRateControl(context, ::commitRate)
        modeRow.addView(rate, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(4) })
        railRow(modeRow, lowerControls)
        val modeOnly = LinearLayout(context)
        mode = button(modeOnly, "", Utils.t("Recording mode / speed", "录像模式／倍率")) { showModes() }
        railRow(modeOnly, lowerControls)
        val resizeRow = LinearLayout(context)
        resize = button(resizeRow, "↘", Utils.t("Drag to resize window", "拖动调整窗口大小"), true) {}.apply { textSize = 22f }
        body.addView(resizeRow, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.BOTTOM or Gravity.RIGHT))
        for (slot in 0..3) labels.addView(TextView(context).apply {
            text = preferences.directionLabel(slot); setTextColor(Color.WHITE); textSize = 12f
            setPadding(dp(5), 0, dp(5), 0); gravity = Gravity.CENTER
            setBackgroundColor(0xCC101317.toInt())
        }, FrameLayout.LayoutParams(-2, dp(24)))
        videoHost.addView(labels, FrameLayout.LayoutParams(-1, -1))
        installDrag(title, false); installDrag(resize, true)
        resize.setOnKeyListener { _, key, event ->
            val delta = when (key) { KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> 10
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> -10; else -> 0 }
            if (event.action != KeyEvent.ACTION_DOWN || preferences.locked || delta == 0) false
            else { preferences.widthDp += delta; fit(); savePosition(); true }
        }
        render(lastState)
    }
    private fun dismissPopup() { popup?.dismiss(); popup = null; popupContent = null; popupRate = null }
    private fun popupPanel(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)); setBackgroundColor(0xFF20252C.toInt())
    }
    private fun showPopup(panel: LinearLayout, width: Int = 232) {
        dismissPopup()
        if (!attached) return
        val screen = context.resources.displayMetrics
        panel.measure(View.MeasureSpec.makeMeasureSpec(dp(width), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(screen.heightPixels, View.MeasureSpec.AT_MOST))
        val next = PopupWindow(panel, dp(width), -2, true).apply {
            windowLayoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            setBackgroundDrawable(ColorDrawable(0xFF20252C.toInt())); isOutsideTouchable = true; elevation = dp(8).toFloat()
            setOnDismissListener { popup = null; popupContent = null; popupRate = null }
        }
        popup = next; popupContent = panel
        next.showAtLocation(root, Gravity.TOP or Gravity.LEFT,
            (params.x + if (preferences.controlsOnRight) params.width - dp(width) else 0).coerceIn(0, (screen.widthPixels-dp(width)).coerceAtLeast(0)),
            (params.y + dp(48)).coerceIn(0, (screen.heightPixels-panel.measuredHeight).coerceAtLeast(0)))
    }
    private fun showCameras() {
        val panel = popupPanel()
        panel.addView(selector().also { renderSelector(it) }, LinearLayout.LayoutParams(dp(156), dp(156)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        showPopup(panel, 172)
    }
    private fun changeLens() {
        val next = if (preferences.lens(preferences.selectedLane) == FourLaneLensMode.STANDARD) FourLaneLensMode.FISHEYE else FourLaneLensMode.STANDARD
        onLens(next); render(lastState)
    }
    private fun toggleGrid() {
        if (MirrorLayoutPolicy.grid(preferences.directionLanes()) == null) {
            Toast.makeText(context, Utils.t("Set all four camera directions first", "请先设置四个摄像头的方向对应"), Toast.LENGTH_LONG).show()
            directionMenu(grid); return
        }
        preferences.grid = !preferences.grid; preferences.triple = false; onGrid(); render(lastState)
    }
    private fun chooseNormal() {
        dismissPopup()
        if (lastState.canChangeMode && !lastState.busy && lastState.mode != RecordingMode.NORMAL) onMode(RecordingModeChoice(RecordingMode.NORMAL))
    }
    private fun chooseLapse() {
        if (!lastState.canChangeMode || lastState.busy) return
        dismissPopup()
        if (lastState.mode != RecordingMode.TIME_LAPSE) onMode(RecordingModeChoice(RecordingMode.TIME_LAPSE, SettingsStore.get(context).timeLapseMultiplier))
    }
    private fun showModes() {
        if (!lastState.canChangeMode || lastState.busy) return
        val panel = popupPanel(); val row = LinearLayout(context); panel.addView(row)
        button(row, Utils.t("Normal recording", "普通录像")) { chooseNormal() }.also { selected(it, lastState.mode == RecordingMode.NORMAL) }
        val lower = LinearLayout(context); panel.addView(lower, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(4) })
        button(lower, Utils.t("Lapse", "延时录像"), Utils.t("Time-lapse recording", "延时录像")) { chooseLapse() }.also { selected(it, lastState.mode == RecordingMode.TIME_LAPSE) }
        val wheel = MirrorRateControl(context, ::commitRate)
        lower.addView(wheel, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(4) })
        wheel.render(displayRate(), true, lastState.mode == RecordingMode.TIME_LAPSE)
        showPopup(panel); popupRate = wheel
    }
    private fun displayRate() = if (lastState.mode == RecordingMode.TIME_LAPSE) lastState.multiplier else SettingsStore.get(context).timeLapseMultiplier
    private fun commitRate(value: Int) {
        if (!lastState.canChangeMode || lastState.busy || value !in TimeLapsePolicy.MULTIPLIERS) return
        if (lastState.mode == RecordingMode.NORMAL) {
            SettingsStore.get(context).setTimeLapseMultiplier(value); render(lastState)
        } else if (value != lastState.multiplier) {
            dismissPopup(); onMode(RecordingModeChoice(RecordingMode.TIME_LAPSE, value))
        }
    }
    private fun showTools() {
        val panel = popupPanel(); val upper = LinearLayout(context); panel.addView(upper)
        button(upper, Utils.t("Standard / fisheye", "标准／鱼眼")) { dismissPopup(); changeLens() }.isEnabled = !lastState.cabin && !lastState.busy
        button(upper, Utils.t("Photo", "拍照")) { dismissPopup(); onPhoto() }.isEnabled = lastState.canPhoto && !lastState.busy
        showPopup(panel, 220)
    }
    private fun selected(button: Button, value: Boolean) { if (button.isSelected != value) OverlayChrome.button(button, value) }
    private fun TextView.showText(value: String) { if (text.toString() != value) text = value }
    private fun renderSelector(target: MirrorCameraSelector) {
        val slot = if (preferences.grid) -1 else preferences.directionLanes().indexOf(preferences.selectedLane)
        target.render(slot, lastState.cabin, lastState.recording && !lastState.busy, lastState.busy, lastState.canCabin)
    }
    fun render(value: MirrorOverlayState) {
        lastState = value
        if (value.busy || value.error) dismissPopup()
        title.showText(when {
            value.error -> value.status
            value.busy -> Utils.t("Switching…", "切换中…")
            value.recording -> "● ${MirrorRecordingClock.text(value.elapsedMs)}" + if (value.cabin) Utils.t(" Cabin", " 车内") else ""
            value.cabin -> Utils.t("Cabin preview", "车内预览")
            else -> Utils.t("Live preview", "实时预览")
        })
        title.setTextColor(if (value.recording && !value.busy) 0xFFFF8E8E.toInt() else Color.WHITE); title.tooltipText = value.status
        listOf(emergency, headerEmergency).forEach { button ->
            button.isEnabled = value.canSaveEmergency && !value.busy && !value.error
            button.setTextColor(if (button.isEnabled) 0xFFFFC178.toInt() else 0xFF818792.toInt())
            button.tooltipText = if (button.isEnabled) Utils.t("Save emergency video", "保存紧急视频") else
                Utils.t("Available during normal recording", "普通录像时可保存紧急视频")
        }
        renderSelector(selector)
        miniArt.selected = if (value.cabin) 4 else if (preferences.grid) -1 else preferences.directionLanes().indexOf(preferences.selectedLane)
        miniArt.recording = value.recording && !value.busy; miniArt.invalidateSelf(); miniCamera.isEnabled = !value.busy
        grid.isEnabled = !value.busy; selected(grid, preferences.grid && !value.cabin)
        labels.visibility = if (preferences.grid && !value.cabin) View.VISIBLE else View.GONE
        if (videoHost.getChildAt(videoHost.childCount - 1) !== labels) labels.bringToFront()
        val currentLens = preferences.lens(preferences.selectedLane)
        if (lens.tag != currentLens) {
            val standard = Utils.t("Standard", "标准"); val fish = Utils.t("Fisheye", "鱼眼")
            lens.text = android.text.SpannableString("$standard / $fish").apply {
                val start = if (currentLens == FourLaneLensMode.STANDARD) 0 else standard.length + 3
                val end = if (currentLens == FourLaneLensMode.STANDARD) standard.length else length
                setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            lens.tag = currentLens
        }
        lens.isEnabled = !value.cabin && !value.busy
        listOf(record, headerRecord).forEach {
            it.contentDescription = if (value.recording) Utils.t("Stop recording", "停止录像") else Utils.t("Start recording", "开始录像")
            it.tooltipText = it.contentDescription; it.isEnabled = value.canRecord && (!value.busy || value.recording && value.canStopWhileBusy)
        }
        headerRecord.showText(if (value.recording) "■" else "●")
        photo.isEnabled = !value.busy && value.canPhoto; tools.isEnabled = !value.busy
        normal.isEnabled = value.canChangeMode && !value.busy; lapse.isEnabled = normal.isEnabled; mode.isEnabled = normal.isEnabled
        selected(normal, value.mode == RecordingMode.NORMAL); selected(lapse, value.mode == RecordingMode.TIME_LAPSE)
        mode.showText(if (value.mode == RecordingMode.NORMAL) Utils.t("Mode", "普通") else "${value.multiplier}×")
        rate.render(displayRate(), normal.isEnabled, value.mode == RecordingMode.TIME_LAPSE)
        popupRate?.render(displayRate(), normal.isEnabled, value.mode == RecordingMode.TIME_LAPSE)
        video.showText(if (preferences.videoVisible) "↙" else "↗")
        video.contentDescription = if (preferences.videoVisible) Utils.t("Hide video", "隐藏视频") else Utils.t("Show video", "显示视频")
        fold.showText(if (preferences.controlsVisible) "›" else "‹")
        fold.contentDescription = if (preferences.controlsVisible) Utils.t("Hide controls", "收起控制栏") else Utils.t("Show controls", "展开控制栏")
        resize.isEnabled = !preferences.locked; fit()
    }
    fun show() { if (!attached) { fit(); windows.addView(root, params); attached = true } }
    fun close() { dismissPopup(); if (attached) runCatching { windows.removeViewImmediate(root) }; attached = false }
    val isVisible get() = attached && root.isShown
    fun directionMenu(anchor: View) {
        val menu = PopupMenu(context, anchor)
        (0..3).forEach { slot -> menu.menu.add(0, slot, slot, preferences.directionLabel(slot)) }
        menu.setOnMenuItemClickListener { chooseDirection(it.itemId, anchor); true }; menu.show()
    }
    private fun chooseDirection(slot: Int, anchor: View) {
        val menu = PopupMenu(context, anchor); val mapped = preferences.directionLanes()
        (1..4).forEach { lane -> menu.menu.add(0, lane, lane, Utils.t("View {0}", "视角{0}", lane)).isEnabled =
            mapped.withIndex().none { it.index != slot && it.value == lane } }
        menu.setOnMenuItemClickListener { preferences.setDirection(slot, it.itemId); preferences.grid = false; dismissPopup(); onLane(it.itemId); render(lastState); true }
        menu.show()
    }
    private fun visible(view: View, show: Boolean) { view.visibility = if (show) View.VISIBLE else View.GONE }
    private fun fit() {
        val screen = context.resources.displayMetrics; val density = screen.density
        val layout = MirrorCompactGeometry.fit(preferences.widthDp, (screen.widthPixels/density).toInt(),
            (screen.heightPixels/density).toInt(), preferences.videoVisible, preferences.controlsVisible)
        if (compact != layout.compact) dismissPopup()
        compact = layout.compact
        visible(body, preferences.videoVisible); visible(rail, preferences.controlsVisible)
        visible(selector, !compact); visible(miniCamera.parent as View, compact)
        visible(lens.parent as View, !compact); visible(tools.parent as View, compact)
        visible(photo.parent as View, !compact); visible(normal.parent as View, !compact)
        visible(modeRow, !compact); visible(mode.parent as View, compact)
        // Reserve a full-width row above Normal, without moving the centred selector
        // when recording starts. Narrow/collapsed windows keep the existing shortcut.
        visible(emergency.parent as View, !compact)
        visible(emergency, lastState.recording)
        grid.showText(if (compact) "⊞" else Utils.t("⊞ Four views", "⊞ 四宫格"))
        grid.textSize = if (compact) 26f else 12f
        visible(headerRecord, !preferences.controlsVisible || !preferences.videoVisible)
        // At the smallest width, a full-image view keeps the clock readable;
        // the same command remains in the actions menu until controls are restored.
        visible(headerEmergency, lastState.recording &&
            (compact || !preferences.controlsVisible || !preferences.videoVisible) &&
            (layout.width >= 344 || preferences.controlsVisible || !preferences.videoVisible))
        visible(fold, preferences.videoVisible)
        record.showText(if (compact) { if (lastState.recording) "■" else "●" }
            else if (lastState.recording) Utils.t("■ Stop recording", "■ 停止录像") else Utils.t("● Start recording", "● 开始录像"))
        params.width = dp(layout.width)
        // The logo's centre follows the entire rail, including after resize or compact mode.
        // Equal clear space above/below both button groups keeps the corner grip separate.
        val clear = dp(MirrorCompactGeometry.controlsInset(layout.bodyHeight, compact))
        listOf(upperControls, lowerControls).forEach { group ->
            val groupParams = group.layoutParams as FrameLayout.LayoutParams
            val top = if (group === upperControls) clear else 0
            val bottom = if (group === lowerControls) clear else 0
            if (groupParams.topMargin != top || groupParams.bottomMargin != bottom) {
                group.layoutParams = groupParams.apply { topMargin = top; bottomMargin = bottom }
            }
        }
        if (body.layoutParams.height != dp(layout.bodyHeight)) body.layoutParams = (body.layoutParams as LinearLayout.LayoutParams).apply { height = dp(layout.bodyHeight) }
        val railParams = rail.layoutParams as LinearLayout.LayoutParams
        if (railParams.width != dp(layout.railWidth) || railParams.marginStart != if (preferences.controlsOnRight) dp(4) else 0) {
            rail.layoutParams = railParams.apply { width = dp(layout.railWidth); marginStart = if (preferences.controlsOnRight) dp(4) else 0
                marginEnd = if (preferences.controlsOnRight) 0 else dp(4) }
        }
        if ((content.getChildAt(0) === rail) == preferences.controlsOnRight) { content.removeView(rail); content.addView(rail, if (preferences.controlsOnRight) 1 else 0) }
        if (!initialized) {
            params.x = ((screen.widthPixels - params.width).coerceAtLeast(0)*preferences.xFraction).toInt()
            params.y = ((screen.heightPixels - dp(layout.height)).coerceAtLeast(0)*preferences.yFraction).toInt(); initialized = true
        }
        params.x = params.x.coerceIn(0, (screen.widthPixels-params.width).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (screen.heightPixels-dp(layout.height)).coerceAtLeast(0))
        val bounds = listOf(params.width, params.x, params.y, layout.height)
        if (attached && appliedBounds != bounds) { windows.updateViewLayout(root, params); appliedBounds = bounds }
    }
    private fun savePosition() {
        val screen = context.resources.displayMetrics
        preferences.position(params.x.toFloat() / (screen.widthPixels-params.width).coerceAtLeast(1),
            params.y.toFloat() / (screen.heightPixels-root.height).coerceAtLeast(1))
    }
    private fun installDrag(view: View, size: Boolean) {
        var startX = 0f; var startY = 0f; var x = 0; var y = 0; var width = 0; var moved = false
        view.setOnTouchListener { _, event ->
            if (preferences.locked) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dismissPopup(); startX = event.rawX; startY = event.rawY; x = params.x; y = params.y; width = preferences.widthDp; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX-startX; val dy = event.rawY-startY
                    moved = moved || kotlin.math.abs(dx)+kotlin.math.abs(dy) > dp(4)
                    if (size) preferences.widthDp = width+((dx+dy*.35f)/1.1225f/context.resources.displayMetrics.density).roundToInt()
                    else { params.x = x+dx.toInt(); params.y = y+dy.toInt() }
                    fit(); true
                }
                MotionEvent.ACTION_UP -> { savePosition(); if (!moved) view.performClick(); true }
                MotionEvent.ACTION_CANCEL -> { if (size) preferences.widthDp = width; params.x = x; params.y = y; fit(); true }
                else -> true
            }
        }
    }
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
}
