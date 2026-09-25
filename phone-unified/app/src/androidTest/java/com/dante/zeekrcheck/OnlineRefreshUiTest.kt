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

/** Synthetic UI and isolated local lease files. No CloudClient or real vehicle request. */
class OnlineRefreshUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun interruptedLeaseIsRecoveredLocallyAndOldCleanupCannotDeleteNewLease() {
        val name = "online-presence-test"
        val file = File(context.noBackupFilesDir, "$name.json")
        val backup = File(file.path + ".bak")
        try {
            file.delete(); backup.delete()
            val lease = PresenceLease("a".repeat(64), "b".repeat(64), 1_800_000_000_000)
            OnlinePresenceStore(context, name).save(lease)
            assertEquals(lease, OnlinePresenceStore(context, name).load())
            assertTrue(file.renameTo(backup))
            assertEquals(lease, OnlinePresenceStore(context, name).load())
            val next = lease.copy(started = lease.started + 1)
            val store = OnlinePresenceStore(context, name)
            store.save(next); store.clear(lease)
            assertEquals(next, store.load())
            store.clear(next); assertNull(store.load())
        } finally { file.delete(); backup.delete() }
    }

    @Test fun fourWidgetSizesShowRefreshProgressAndKeepSourceTimeInBothLanguages() {
        val original = PhoneLanguage.mode
        instrumentation.runOnMainSync {
            try {
                val now = Instant.parse("2026-09-21T13:00:00Z")
                val old = now.minusSeconds(3600).toEpochMilli()
                val state = VehicleOverview(vehicleKey = "synthetic-refresh", nickname = "Demo 7X",
                    refreshingAt = now.toEpochMilli(), message = "等待车辆新上报…",
                    readings = mapOf("cabin_temperature" to OverviewReading("19.9 °C", old, now.toEpochMilli()),
                        "battery" to OverviewReading("68%", old, now.toEpochMilli())))
                val dir = File(context.filesDir, "qa-online-refresh").apply { mkdirs() }
                for (language in listOf(PhoneLanguageMode.SIMPLIFIED_CHINESE, PhoneLanguageMode.ENGLISH)) {
                    PhoneLanguage.selectMode(language)
                    for (size in listOf("4x3", "4x2", "2x2", "4x1")) {
                        val remote = if (size in setOf("2x2", "4x1")) SmallVehicleWidget.views(context, state,
                            strip = size == "4x1", now = now, preview = true)
                        else VehicleWidgetProvider.views(context, state, compact = size == "4x2", now = now,
                            locationLabel = "示例位置", preview = true)
                        val view = remote.apply(context, FrameLayout(context))
                        val text = view.findViewById<TextView>(R.id.widget_message).text.toString()
                        assertEquals(ui("等待车辆新上报…"), text)
                        assertEquals("19.9°C", view.findViewById<TextView>(R.id.widget_cabin).text.toString())
                        assertEquals(old, state.readings["cabin_temperature"]!!.source)
                        if (language == PhoneLanguageMode.ENGLISH) assertFalse(Regex("[\\u3400-\\u9fff]").containsMatchIn(text))
                        val density = context.resources.displayMetrics.density
                        val width = ((if (size == "2x2") 170 else 340) * density).toInt()
                        val height = ((when (size) { "4x3" -> 340; "4x2" -> 224; "2x2" -> 180; else -> 100 }) * density).toInt()
                        view.layoutDirection = View.LAYOUT_DIRECTION_LTR
                        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                        view.layout(0, 0, width, height)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        view.draw(Canvas(bitmap))
                        File(dir, "${language.name}-$size.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        bitmap.recycle()
                    }
                    val result = "已收到新车况上报 · 温度数值未变"
                    if (language == PhoneLanguageMode.ENGLISH) assertFalse(Regex("[\\u3400-\\u9fff]").containsMatchIn(ui(result)))
                }
            } finally { PhoneLanguage.selectMode(original); VehicleWidgetProvider.updateAll(context) }
        }
    }
}
