package com.dante.zeekrcapabilitylab.mirror

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import com.dante.zeekrcapabilitylab.service.recorder.TimeLapsePolicy
import com.dante.zeekrcapabilitylab.ui.product.OverlayChrome
import com.dante.zeekrcapabilitylab.util.Utils

/** Fixed 44 dp rate wheel, with touch, keyboard and accessibility adjustment. */
internal class MirrorRateControl(context: Context, private val commit: (Int) -> Unit) : Button(context) {
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private var confirmed = TimeLapsePolicy.DEFAULT_MULTIPLIER
    private var drag: MirrorRateGesture? = null
    private var startY = 0f
    private var pointer = -1
    init {
        textSize = 14f; isAllCaps = false; includeFontPadding = false
        minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0; setPadding(0, 0, 0, 0)
        contentDescription = Utils.t("Time-lapse speed", "延时倍率")
        tooltipText = Utils.t("Swipe up to increase, down to decrease", "上滑增大倍率，下滑减小倍率")
        OverlayChrome.button(this); showValue(confirmed)
    }
    fun render(value: Int, enabled: Boolean, selected: Boolean) {
        if (!enabled || value != confirmed) cancelDrag()
        confirmed = value
        isEnabled = enabled
        if (isSelected != selected) OverlayChrome.button(this, selected)
        showValue(drag?.value ?: confirmed)
    }
    private fun showValue(value: Int) { if (text.toString() != "${value}×") text = "${value}×" }
    private fun cancelDrag() { drag?.cancel(); drag = null; pointer = -1; isPressed = false; parent?.requestDisallowInterceptTouchEvent(false); showValue(confirmed) }
    override fun onDetachedFromWindow() { cancelDrag(); super.onDetachedFromWindow() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        ink.color = currentTextColor; ink.strokeWidth = density; ink.alpha = if (isEnabled) 160 else 70
        val x = width / 2f; val s = 3f * density
        canvas.drawLine(x-s, 8*density, x, 5*density, ink); canvas.drawLine(x, 5*density, x+s, 8*density, ink)
        canvas.drawLine(x-s, height-8*density, x, height-5*density, ink); canvas.drawLine(x, height-5*density, x+s, height-8*density, ink)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) { cancelDrag(); return false }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drag = MirrorRateGesture(confirmed); startY = event.rawY; pointer = event.getPointerId(0)
                isPressed = true; parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> if (event.getPointerId(0) == pointer) {
                drag?.move((startY-event.rawY)/density)?.let(::showValue)
            }
            MotionEvent.ACTION_UP -> {
                val chosen = drag?.finish(); cancelDrag(); parent?.requestDisallowInterceptTouchEvent(false)
                if (chosen != null) commit(chosen) else performClick()
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> cancelDrag()
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    private fun step(direction: Int): Boolean {
        if (!isEnabled) return false
        cancelDrag()
        val values = TimeLapsePolicy.MULTIPLIERS
        val next = values[(values.indexOf(confirmed) + direction).coerceIn(0, values.lastIndex)]
        if (next != confirmed) commit(next)
        return true
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> step(1)
        KeyEvent.KEYCODE_DPAD_DOWN -> step(-1)
        else -> super.onKeyDown(keyCode, event)
    }
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL) {
            val dy = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (dy != 0f) return step(if (dy > 0) 1 else -1)
        }
        return super.onGenericMotionEvent(event)
    }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.SeekBar"
        info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT,
            0f, TimeLapsePolicy.MULTIPLIERS.lastIndex.toFloat(), TimeLapsePolicy.MULTIPLIERS.indexOf(confirmed).toFloat())
        if (isEnabled) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        }
    }
    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean = when (action) {
        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> step(1)
        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> step(-1)
        else -> super.performAccessibilityAction(action, arguments)
    }
}
