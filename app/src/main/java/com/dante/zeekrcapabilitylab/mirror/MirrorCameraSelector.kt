package com.dante.zeekrcapabilitylab.mirror

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.widget.Button
import android.widget.FrameLayout

/** Logo-shaped presentation with five separate, non-overlapping 44 dp accessible controls. */
internal class MirrorCameraSelector(context: Context, labels: List<String>, cabinLabel: String,
    onDirection: (Int, Button) -> Unit, onCabin: () -> Unit) : FrameLayout(context) {
    private val art = LogoDrawable()
    val directions: List<Button>
    val cabin: Button
    init {
        background = art
        fun target(description: String, x: Int, y: Int, height: Int = 44): Button = Button(context).apply {
            text = ""; contentDescription = description; tooltipText = description
            minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0; setPadding(0, 0, 0, 0)
            backgroundTintList = null
            background = RippleDrawable(ColorStateList.valueOf(0x44FFFFFF), null, android.graphics.drawable.ColorDrawable(Color.WHITE))
            addView(this, LayoutParams(dp(44), dp(height)).apply { leftMargin = dp(x); topMargin = dp(y) })
        }
        val positions = listOf(56 to 0, 56 to 112, 0 to 44, 112 to 44)
        directions = positions.mapIndexed { slot, (x, y) ->
            target(labels[slot], x, y, if (slot >= 2) 68 else 44).also { button -> button.setOnClickListener { onDirection(slot, button) } }
        }
        cabin = target(cabinLabel, 56, 44, 68).apply { setOnClickListener { onCabin() } }
    }
    fun render(slot: Int, inCabin: Boolean, recording: Boolean, busy: Boolean, canCabin: Boolean) {
        art.selected = if (inCabin) 4 else slot; art.recording = recording
        directions.forEachIndexed { i, button -> button.isSelected = !inCabin && slot == i; button.isEnabled = !busy }
        cabin.isSelected = inCabin; cabin.isEnabled = !busy && canCabin
        art.alpha = if (busy) 120 else 255; art.invalidateSelf()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()

    /** Native vector geometry follows the supplied OpenAVM logo; the red dot is live state. */
    internal class LogoDrawable : Drawable() {
        var selected = -1
        var recording = false
        private var opacity = 255
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val fan = Path().apply {
            moveTo(35f, 15f); quadTo(50f, 7f, 65f, 15f); quadTo(66f, 16f, 65f, 17f)
            lineTo(56f, 27f); quadTo(55f, 28f, 54f, 28f); lineTo(46f, 28f)
            quadTo(45f, 28f, 44f, 27f); lineTo(35f, 17f); quadTo(34f, 16f, 35f, 15f); close()
        }
        override fun draw(canvas: Canvas) {
            val save = canvas.save()
            canvas.translate(bounds.left.toFloat(), bounds.top.toFloat()); canvas.scale(bounds.width()/100f, bounds.height()/100f)
            paint.shader = null; paint.style = Paint.Style.FILL; paint.alpha = opacity
            paint.color = 0xFF101317.toInt(); paint.alpha = opacity
            canvas.drawRoundRect(0f, 0f, 100f, 100f, 14f, 14f, paint)
            for ((slot, angle) in listOf(0 to 0f, 1 to 180f, 2 to 270f, 3 to 90f)) {
                val part = canvas.save(); canvas.rotate(angle, 50f, 50f)
                val colors = if (selected == slot) intArrayOf(0xFFADD9F5.toInt(), 0xFF569DC9.toInt())
                    else intArrayOf(0xFFAFB8C3.toInt(), 0xFF68717E.toInt())
                paint.shader = LinearGradient(35f, 12f, 60f, 29f, colors, null, Shader.TileMode.CLAMP)
                paint.alpha = opacity; canvas.drawPath(fan, paint); paint.shader = null
                paint.style = Paint.Style.STROKE; paint.strokeWidth = .6f; paint.color = 0xFFD2D9E0.toInt(); paint.alpha = opacity
                canvas.drawPath(fan, paint); paint.style = Paint.Style.FILL; canvas.restoreToCount(part)
            }
            // Preserve the logo's tall car; the central 44 x 68 dp target fits its full body.
            val car = canvas.save()
            canvas.translate(0f, -1.5f)
            paint.color = if (selected == 4) 0xFF97CCEC.toInt() else 0xFFF3F5F7.toInt(); paint.alpha = opacity
            canvas.drawRoundRect(40f, 30f, 60f, 73f, 8f, 8f, paint)
            canvas.drawRoundRect(38f, 43f, 62f, 46f, 1f, 1f, paint)
            paint.color = 0xFF101317.toInt(); paint.alpha = opacity
            val front = Path().apply { moveTo(42f, 41f); quadTo(50f, 36f, 58f, 41f); lineTo(56f, 48f); quadTo(50f, 46f, 44f, 48f); close() }
            canvas.drawPath(front, paint)
            val back = Path().apply { moveTo(44f, 62f); quadTo(50f, 64f, 56f, 62f); lineTo(58f, 68f); quadTo(50f, 72f, 42f, 68f); close() }
            canvas.drawPath(back, paint)
            canvas.drawRoundRect(41f, 47f, 43f, 61f, 1f, 1f, paint); canvas.drawRoundRect(57f, 47f, 59f, 61f, 1f, 1f, paint)
            canvas.restoreToCount(car)
            paint.color = if (recording) 0xFFFF373D.toInt() else 0xFF454C54.toInt(); paint.alpha = opacity
            canvas.drawCircle(86f, 14f, 4.5f, paint)
            canvas.restoreToCount(save)
        }
        override fun setAlpha(alpha: Int) { opacity = alpha; invalidateSelf() }
        override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
        @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
