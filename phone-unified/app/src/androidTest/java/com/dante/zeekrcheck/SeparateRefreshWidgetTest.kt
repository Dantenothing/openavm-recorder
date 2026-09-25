package com.dante.zeekrcheck

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import com.dante.zeekrbridge.ui.PhoneLanguage
import com.dante.zeekrbridge.ui.PhoneLanguageMode
import com.dante.zeekrcheck.core.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.Instant

/** Offscreen RemoteViews only: no Activity, services, screen input or real account state. */
class SeparateRefreshWidgetTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val now = Instant.parse("2026-09-22T04:00:00Z")
    private val at = now.toEpochMilli()
    private fun task(phase: TemperaturePhase) = TemperatureUpdate("demo-temperature", at - 10_000, 22, phase,
        message = if (phase == TemperaturePhase.NEEDS_STOP) "车温已更新 · 临时空调停止待确认" else "空调取温中 · 等待车辆上报",
        startSent = at - 5_000, ownsAc = true, sawRunning = true, temperature = if (phase == TemperaturePhase.NEEDS_STOP) 19.9 else null)
    private val state get() = VehicleOverview(vehicleKey = "demo-temperature", nickname = "Demo 7X",
        motion = MotionReading("Parked", at, at, false),
        readings = mapOf("cabin_temperature" to OverviewReading("19.9 °C", at - 600_000, at),
            "battery" to OverviewReading("68%", at, at), "range" to OverviewReading("412 km", at, at)))

    private fun render(size: String, task: TemperatureUpdate?, widthDp: Int): View {
        val small = size in setOf("2x2", "4x1")
        val remote = if (small) SmallVehicleWidget.views(context, state, size == "4x1", now,
            preview = true, temperatureUpdate = task)
        else VehicleWidgetProvider.views(context, state, size == "4x2", now, "示例位置",
            preview = true, temperatureUpdate = task)
        val view = remote.apply(context, FrameLayout(context))
        val density = context.resources.displayMetrics.density
        val width = (widthDp * density).toInt()
        val height = ((when (size) { "4x3" -> 340; "4x2" -> 224; "2x2" -> 180; else -> 100 }) * density).toInt()
        view.layoutDirection = View.LAYOUT_DIRECTION_LTR
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
        return view
    }
    @Test fun fourSizesKeepSeparateRefreshTargetsInBothLanguages() {
        val original = PhoneLanguage.mode
        instrumentation.runOnMainSync {
            try {
                val dir = File(context.filesDir, "qa-temperature-local21").apply { mkdirs() }
                for (language in listOf(PhoneLanguageMode.SIMPLIFIED_CHINESE, PhoneLanguageMode.ENGLISH)) {
                    PhoneLanguage.selectMode(language)
                    for (size in listOf("4x3", "4x2", "2x2", "4x1")) for ((label, task) in listOf(
                        "idle" to null, "reading" to task(TemperaturePhase.OBSERVING), "stop-pending" to task(TemperaturePhase.NEEDS_STOP))) {
                        val view = render(size, task, if (size == "2x2") 170 else 340)
                        assertEquals("19.9°C", view.findViewById<TextView>(R.id.widget_cabin).text.toString())
                        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.widget_temperature_update).visibility)
                        val temperatureButton = view.findViewById<View>(R.id.widget_temperature_update)
                        assertEquals(ui(task.temperatureActionDescription(at)), temperatureButton.contentDescription.toString())
                        val button = view.findViewById<View>(R.id.widget_refresh)
                        assertTrue(button.height > 0)
                        assertTrue(button.contentDescription.isNotBlank())
                        if (size in listOf("4x2", "4x3")) assertEquals(ui("刷新"), (button as TextView).text.toString())
                        assertEquals(if (task?.active == true) View.VISIBLE else View.GONE,
                            view.findViewById<View>(R.id.widget_temperature_progress).visibility)
                        if (language == PhoneLanguageMode.ENGLISH) assertFalse(Regex("[\u3400-\u9fff]").containsMatchIn(button.contentDescription))
                        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                        view.draw(Canvas(bitmap))
                        File(dir, "${language.name}-$size-$label.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        bitmap.recycle()
                    }
                }
            } finally { PhoneLanguage.selectMode(original) }
        }
    }
    @Test fun narrowWidgetsKeepTemperatureActionInsideLayout() {
        val original = PhoneLanguage.mode
        instrumentation.runOnMainSync {
            try {
                PhoneLanguage.selectMode(PhoneLanguageMode.ENGLISH)
                for (size in listOf("4x3", "4x2", "2x2", "4x1")) {
                    val view = render(size, null, if (size == "2x2") 170 else 250)
                    val cabin = view.findViewById<TextView>(R.id.widget_cabin)
                    assertTrue("$size temperature clipped", cabin.height >= cabin.layout.height)
                    assertEquals(View.VISIBLE, view.findViewById<View>(R.id.widget_temperature_update).visibility)
                    val action = view.findViewById<View>(R.id.widget_temperature_update)
                    assertTrue("$size action collapsed", action.width >= 32 * context.resources.displayMetrics.density)
                    assertTrue("$size action too short", action.height >= 24 * context.resources.displayMetrics.density)
                    assertSame("$size temperature button should be beside the reading", cabin.parent, action.parent)
                    assertTrue("$size temperature/action overlap", cabin.right <= action.left)
                    val bounds = android.graphics.Rect()
                    action.getDrawingRect(bounds)
                    (view as android.view.ViewGroup).offsetDescendantRectToMyCoords(action, bounds)
                    assertTrue("$size button outside card: $bounds", bounds.right <= view.width && bounds.bottom <= view.height)
                    if (action is TextView) {
                        assertTrue("$size action clipped vertically", action.layout.height <= action.height)
                        for (line in 0 until action.layout.lineCount) assertEquals("$size action text ellipsized", 0, action.layout.getEllipsisCount(line))
                    }
                }
            } finally { PhoneLanguage.selectMode(original) }
        }
    }

    @Test fun statusAndTemperatureIntentsHaveIndependentTargetsAndVehicleBinding() {
        val status = VehicleWidgetProvider.refreshIntent(context, "synthetic", 42)
        val temperature = TemperatureUpdateService.intent(context, "synthetic", null, 42)
        assertEquals(VehicleWidgetProvider.REFRESH, status.action)
        assertEquals(VehicleWidgetProvider::class.java.name, status.component!!.className)
        assertEquals(TemperatureUpdateService::class.java.name, temperature.component!!.className)
        assertNotEquals(status.data, temperature.data)
        for (intent in listOf(status, temperature)) {
            assertEquals("synthetic", intent.getStringExtra("vehicleKey"))
            assertEquals(42, intent.getIntExtra("originWidget", 0))
            assertFalse(intent.getBooleanExtra("combined", false))
        }
        val header = VehicleWidgetProvider.refreshPending(context, "synthetic", 42)
        val sample = TemperatureUpdateService.pending(context, "synthetic", null, 42)
        assertTrue(header.isBroadcast)
        assertTrue(sample.isForegroundService)
        assertNotEquals(header, sample)
        header.cancel(); sample.cancel()
        val cleanup = TemperatureUpdateService.intent(context, "synthetic", task(TemperaturePhase.NEEDS_STOP), 42)
        assertTrue(cleanup.getBooleanExtra("stop", false))
        assertEquals(at - 10_000, cleanup.getLongExtra("expectedId", 0))
    }
}
