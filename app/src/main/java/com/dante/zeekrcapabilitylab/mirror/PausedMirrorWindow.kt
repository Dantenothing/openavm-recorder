package com.dante.zeekrcapabilitylab.mirror

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.dante.zeekrcapabilitylab.util.Utils
import kotlin.math.abs
import kotlin.math.roundToInt

/** Retained OpenAVM logo. No Surface, capture, encoder, repeating timer or WakeLock. */
@SuppressLint("ClickableViewAccessibility", "RtlHardcoded")
internal class PausedMirrorWindow(
    context: Context, private val preferences: MirrorPresentation,
    resume: () -> Unit, menu: (View) -> Unit,
) {
    val identity: String = java.util.UUID.randomUUID().toString()
    private val windows = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).roundToInt()
    private val frame = GradientDrawable().apply {
        setColor(0xFF101317.toInt()); cornerRadius = dp(20).toFloat(); setStroke(dp(1), 0xFF697780.toInt())
    }
    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(4))
        background = frame; elevation = dp(8).toFloat()
    }
    private val logo = ImageView(context).apply {
        setImageDrawable(MirrorCameraSelector.LogoDrawable())
        contentDescription = Utils.t("OpenAVM · tap to restore preview, hold for actions", "OpenAVM · 点击恢复预览，长按打开菜单")
        setOnClickListener { resume() }
    }
    private val status = TextView(context).apply {
        textSize = 12f; setTextColor(Color.WHITE); gravity = Gravity.CENTER_VERTICAL
        maxLines = 3; setOnClickListener { resume() }
    }
    private val more = TextView(context).apply {
        text = "⋯"; textSize = 24f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        contentDescription = Utils.t("Mirror actions", "后视镜操作"); setOnClickListener(menu)
    }
    private val params = WindowManager.LayoutParams(dp(164), WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.LEFT }
    private var added = false
    val attached get() = added && root.isAttachedToWindow
    val shown get() = attached && root.isShown

    init {
        root.addView(logo, LinearLayout.LayoutParams(-1, dp(144)))
        val footer = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        footer.addView(status, LinearLayout.LayoutParams(0, dp(48), 1f))
        footer.addView(more, LinearLayout.LayoutParams(dp(44), dp(48)))
        root.addView(footer)
        logo.setOnLongClickListener { menu(more); true }
        root.setOnLongClickListener { menu(more); true }
        // Close is available in the menu and notification without covering the logo in buttons.
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        var moved = false; var held = false
        val gesture = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onLongPress(e: MotionEvent) { if (!moved) { held = true; logo.performLongClick() } }
        })
        logo.setOnTouchListener { view, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                downX = event.rawX; downY = event.rawY; startX = params.x; startY = params.y; moved = false; held = false
            }
            if (event.actionMasked == MotionEvent.ACTION_MOVE &&
                (abs(event.rawX - downX) > dp(6) || abs(event.rawY - downY) > dp(6))) moved = true
            gesture.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> if (moved && !held && !preferences.locked) {
                    params.x = startX + (event.rawX - downX).roundToInt(); params.y = startY + (event.rawY - downY).roundToInt()
                    constrain(); if (added) windows.updateViewLayout(root, params)
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved && !held) view.performClick()
                    else if (moved && !held && !preferences.locked) {
                        val metrics = context.resources.displayMetrics
                        preferences.position(params.x.toFloat() / (metrics.widthPixels - params.width).coerceAtLeast(1),
                            params.y.toFloat() / (metrics.heightPixels - root.height).coerceAtLeast(1))
                    }
                }
            }; true
        }
    }
    private fun constrain() {
        val metrics = root.resources.displayMetrics
        params.x = params.x.coerceIn(0, (metrics.widthPixels - params.width).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (metrics.heightPixels - root.height.coerceAtLeast(dp(204))).coerceAtLeast(0))
    }
    fun show() {
        if (added) return
        val metrics = root.resources.displayMetrics
        params.width = dp(164).coerceAtMost(metrics.widthPixels)
        params.x = ((metrics.widthPixels - params.width) * preferences.xFraction).roundToInt()
        params.y = ((metrics.heightPixels - dp(204)) * preferences.yFraction).roundToInt()
        constrain(); windows.addView(root, params); added = true
    }
    fun render(released: Boolean, screenUsable: Boolean, message: String? = null) {
        frame.setStroke(dp(1), if (released) 0xFF83ABA7.toInt() else 0xFFCCAA6A.toInt())
        status.text = message ?: when {
            !released -> Utils.t("Releasing camera…", "等待相机释放")
            !screenUsable -> Utils.t("Camera released", "相机已释放")
            else -> Utils.t("Tap to restore", "点击恢复预览")
        }
        status.contentDescription = if (released) Utils.t("App camera released. {0}", "本应用相机已释放。{0}", status.text)
            else Utils.t("App camera release not confirmed. {0}", "本应用相机释放尚未确认。{0}", status.text)
    }
    fun close() {
        if (added) runCatching { windows.removeViewImmediate(root) }
        added = false
    }
}
