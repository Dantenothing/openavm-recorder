package com.dante.zeekrcheck

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import com.dante.zeekrbridge.ui.PhoneLanguage
import com.dante.zeekrbridge.ui.PhoneLanguageMode
import com.dante.zeekrcheck.core.VehicleOverview
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Renders all four layouts without executing their pending intents. */
class UnconfiguredWidgetUiTest {
    @Test fun allSizesExplainSetupAndDisableVehicleActionsInBothLanguages() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".freshqa"))
        instrumentation.runOnMainSync {
            val original = PhoneLanguage.mode
            try {
                for (language in listOf(PhoneLanguageMode.ENGLISH, PhoneLanguageMode.SIMPLIFIED_CHINESE)) {
                    PhoneLanguage.selectMode(language)
                    for (size in listOf("2x2", "4x1", "4x2", "4x3")) {
                        val overview = VehicleOverview(nickname = "OpenAVM", message = "设置云端连接")
                        val remote = if (size in setOf("2x2", "4x1")) SmallVehicleWidget.views(context, overview,
                            strip = size == "4x1", controlsEnabled = false)
                        else VehicleWidgetProvider.views(context, overview, compact = size == "4x2",
                            locationLabel = "设置云端连接", controlsEnabled = false)
                        val view = remote.apply(context, FrameLayout(context))
                        view.layoutDirection = View.LAYOUT_DIRECTION_LTR
                        val dp = context.resources.displayMetrics.density
                        val width = ((if (size == "2x2") 165 else 340) * dp).toInt()
                        val height = ((when (size) { "2x2" -> 200; "4x1" -> 100; "4x2" -> 224; else -> 340 }) * dp).toInt()
                        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                        view.layout(0, 0, width, height)
                        assertEquals(ui("设置云端连接"), view.findViewById<TextView>(R.id.widget_message).text.toString())
                        for (id in listOf(R.id.widget_prepare, R.id.widget_find, R.id.widget_lock, R.id.widget_guard, R.id.widget_refresh, R.id.widget_temperature_update))
                            assertFalse(view.findViewById<View>(id).isEnabled)
                        assertTrue(view.findViewById<View>(R.id.widget_open).isEnabled)
                        val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        view.draw(Canvas(image))
                        val path = File(context.getExternalFilesDir(null), "config-qa/widget-$size-${language.storedValue}.png").apply { parentFile!!.mkdirs() }
                        path.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        image.recycle()
                    }
                }
            } finally { PhoneLanguage.selectMode(original) }
        }
    }
}
