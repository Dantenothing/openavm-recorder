package com.dante.zeekrcapabilitylab.mirror

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable

/** Top-down car silhouette; the containing Button keeps a normal 44 dp accessible hit target. */
internal class MirrorCarButtonDrawable(context: Context) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = context.resources.displayMetrics.density
    override fun isStateful() = true
    override fun onStateChange(state: IntArray): Boolean { invalidateSelf(); return true }
    override fun draw(canvas: Canvas) {
        val selected = android.R.attr.state_selected in state
        val r = RectF(bounds).apply { inset(5 * density, density) }
        paint.style = Paint.Style.FILL; paint.color = if (selected) 0xFF555F69.toInt() else 0xFF27292D.toInt()
        canvas.drawRoundRect(r, 10 * density, 10 * density, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.5f * density; paint.color = 0xFFE0E2E5.toInt()
        canvas.drawRoundRect(r, 10 * density, 10 * density, paint)
        canvas.drawLine(r.left + 5 * density, r.top + 7 * density, r.right - 5 * density, r.top + 7 * density, paint)
        canvas.drawLine(r.left + 5 * density, r.bottom - 7 * density, r.right - 5 * density, r.bottom - 7 * density, paint)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
    @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
