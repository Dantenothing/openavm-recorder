package com.dante.zeekrcapabilitylab.mirror

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.dante.zeekrcapabilitylab.product.AppLanguage
import com.dante.zeekrcapabilitylab.telemetry.RecordingTelemetry
import com.dante.zeekrcapabilitylab.telemetry.RecordingVehicleTelemetry
import com.dante.zeekrcapabilitylab.ui.product.OverlayChrome
import com.dante.zeekrcapabilitylab.util.Utils
import io.github.dantenothing.drivemap.DriveHudFrame
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest

/** Native overlay presentation. Collectors and expiry redraws exist only while this HUD is visible. */
internal class MirrorLiveHud(context: Context) : LinearLayout(context) {
    private val settings = context.getSharedPreferences("drive-hud", Context.MODE_PRIVATE)
    private var hostActive = false
    private var collection: CoroutineScope? = null
    private var expanded = false
    private val readings = LinearLayout(context).apply { orientation = VERTICAL }
    private val primary = label(14f)
    private val controls = label(11f)
    private val pedals = label(10f).apply { visibility = GONE }
    private val restore = action("HUD", Utils.t("Show driving HUD", "显示行驶 HUD")) { saveVisible(true) }
    private val more = action("⋯", Utils.t("Show unverified pedal readings", "显示待验证踏板读数")) {
        expanded = !expanded; pedals.visibility = if (expanded) VISIBLE else GONE
    }
    private val hide = action("×", Utils.t("Hide driving HUD", "隐藏行驶 HUD")) { saveVisible(false) }
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "live-visible") syncVisibility()
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(2), dp(4), dp(2))
        setBackgroundColor(0xCC101317.toInt())
        readings.addView(primary); readings.addView(controls); readings.addView(pedals)
        addView(readings, LayoutParams(0, -2, 1f))
        addView(more, LayoutParams(dp(36), dp(44)))
        addView(hide, LayoutParams(dp(36), dp(44)))
        addView(restore, LayoutParams(dp(52), dp(44)))
        render(DriveHudFrame())
        syncVisibility()
    }

    fun setHostActive(value: Boolean) { hostActive = value; syncVisibility() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        settings.registerOnSharedPreferenceChangeListener(preferenceListener)
        syncVisibility()
    }

    override fun onDetachedFromWindow() {
        settings.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        stopObserving()
        super.onDetachedFromWindow()
    }

    private fun saveVisible(value: Boolean) {
        settings.edit().putBoolean("live-visible", value).apply()
        syncVisibility()
    }

    private fun syncVisibility() {
        val visible = settings.getBoolean("live-visible", true)
        setBackgroundColor(if (visible) 0xCC101317.toInt() else Color.TRANSPARENT)
        readings.visibility = if (visible) VISIBLE else GONE
        more.visibility = if (visible) VISIBLE else GONE
        hide.visibility = if (visible) VISIBLE else GONE
        restore.visibility = if (visible) GONE else VISIBLE
        if (hostActive && isAttachedToWindow && visible) startObserving() else stopObserving()
    }

    private fun startObserving() {
        if (collection != null) return
        collection = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope ->
            scope.launch {
                combine(RecordingVehicleTelemetry.state, RecordingTelemetry.state) { vehicle, gps -> vehicle to gps }
                    .collectLatest { (vehicle, gps) ->
                        val now = SystemClock.elapsedRealtime()
                        render(mirrorHudFrame(now, vehicle, gps))
                        for (expiry in mirrorHudExpiries(now, vehicle, gps)) {
                            delay((expiry - SystemClock.elapsedRealtime()).coerceAtLeast(1))
                            render(mirrorHudFrame(SystemClock.elapsedRealtime(), vehicle, gps))
                        }
                    }
            }
        }
    }

    private fun stopObserving() {
        collection?.cancel(); collection = null
        render(DriveHudFrame())
    }

    private fun render(frame: DriveHudFrame) {
        val value = mirrorHudText(frame, AppLanguage.usesChinese())
        if (primary.text.toString() != value.primary) primary.text = value.primary
        if (controls.text.toString() != value.controls) controls.text = value.controls
        if (pedals.text.toString() != value.pedals) pedals.text = value.pedals
        readings.contentDescription = value.primary + ", " + value.controls + if (expanded) ", " + value.pedals else ""
    }

    private fun label(size: Float) = TextView(context).apply { setTextColor(Color.WHITE); textSize = size; maxLines = 2 }
    private fun action(textValue: String, description: String, click: () -> Unit) = Button(context).apply {
        text = textValue; contentDescription = description; tooltipText = description
        textSize = 12f; isAllCaps = false
        minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0; setPadding(0, 0, 0, 0)
        OverlayChrome.button(this); setOnClickListener { click() }
    }
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
