package com.dante.zeekrcapabilitylab.ui.product

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.widget.Button

/** Shared neutral chrome. Opacity applies to the frame, never the live image. */
object OverlayChrome {
    fun frame(view: View) {
        val density = view.resources.displayMetrics.density
        view.background = GradientDrawable().apply {
            setColor(0xE61C1C1F.toInt())
            cornerRadius = 14f * density
            setStroke(maxOf(1, density.toInt()), 0x665F5F63)
        }
        view.clipToOutline = true
        view.elevation = 6f * density
    }

    fun button(view: Button, selected: Boolean = false) {
        val density = view.resources.displayMetrics.density
        view.backgroundTintList = null
        view.background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), GradientDrawable().apply {
            setColor(if (selected) 0xFFECECF0.toInt() else 0xCC38383D.toInt()); cornerRadius = 8f * density
        }, null)
        view.setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(0x77FFFFFF, if (selected) 0xFF222328.toInt() else Color.WHITE)))
        view.isSelected = selected
    }
}
