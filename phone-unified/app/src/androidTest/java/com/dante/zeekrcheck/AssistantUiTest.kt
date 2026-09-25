package com.dante.zeekrcheck

import android.graphics.Bitmap
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.dante.zeekrcheck.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant

/** No ViewModel, saved account, live connection, or command transport is used here. */
class AssistantUiTest {
    @get:Rule val compose = createComposeRule()
    private val now = Instant.parse("2026-09-19T09:00:00Z")
    private val overview = VehicleOverview(readings = mapOf(
        "cabin_temperature" to OverviewReading("34 °C", now.minusSeconds(60).toEpochMilli(), now.toEpochMilli()),
        "battery" to OverviewReading("68 %", null, now.toEpochMilli()), "range" to OverviewReading("412 km", null, now.toEpochMilli()),
        "sentry" to OverviewReading("关闭", null, now.toEpochMilli()), "lock" to OverviewReading("已锁", null, now.toEpochMilli())), acOn = false)
    @Test fun sixQuickActionsUseDedicatedCallbacksWithoutInventingChanges() {
        val actions = mutableListOf<String>()
        compose.setContent { AssistantTheme { Column { NativeVehicleCard(overview, now, onRefresh = {}, onAction = { actions += it }) } } }
        for (action in listOf("prepare", "find", "lock", "guard", "trunk", "port")) compose.onNodeWithTag("quick_$action").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf("prepare", "find", "lock", "guard", "trunk", "port"), actions) }
        compose.onNodeWithTag("overview_temperature", useUnmergedTree = true).assertTextEquals("34°C")
        screenshot("01-native-card-synthetic.png")
    }
    @Test fun staleTemperatureIsNotRenderedAsCurrent() {
        compose.setContent { AssistantTheme { NativeVehicleCard(overview, now.plusSeconds(600), onRefresh = {}, onAction = {}) } }
        compose.onNodeWithTag("overview_temperature", useUnmergedTree = true).assertTextEquals("34°C")
        compose.onNodeWithText("上次测量 · 非实时", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("上次车温", useUnmergedTree = true).assertExists()
    }
    @Test fun compactRefreshFailureKeepsTheErrorAndOriginalSamplingTimeVisible() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.runOnUiThread {
            val view = VehicleWidgetProvider.views(context, overview.copy(message = "基础车况读取失败"), true, now).apply(context, FrameLayout(context))
            val source = view.findViewById<TextView>(R.id.widget_source).text.toString()
            assertTrue(source.contains(overview.readings.getValue("cabin_temperature").timeLabel(now).removeSuffix(" 记录")))
            val feedback = view.findViewById<TextView>(R.id.widget_message)
            assertEquals(View.VISIBLE, feedback.visibility); assertTrue(feedback.text.contains("基础车况读取失败"))
        }
    }
    @Test fun allThreeComfortAllowancesCanBeSavedWithoutAnyControlTransport() {
        var saved: ComfortPreferences? = null
        compose.setContent { AssistantTheme { PreferenceEditor(ComfortPreferences(), {}, { saved = it }) } }
        compose.onNodeWithText("全选 / 取消").performClick()
        compose.onNodeWithText("座椅加热").performClick()
        compose.onNodeWithTag("save_comfort_preferences").performClick()
        compose.runOnIdle {
            assertTrue(saved!!.steeringHeat); assertFalse(saved!!.seatHeat); assertTrue(saved!!.seatVentilation)
            assertEquals(30, saved!!.minutes)
        }
    }
    @Test fun departureEditorFitsSevenDaysAndSavesOnlyALocalSnapshot() {
        var saved: DeparturePlan? = null
        val prefs = ComfortPreferences(seatHeat = true, seatVentilation = true)
        compose.setContent { AssistantTheme { DepartureEditor(prefs, null, "synthetic-only", {}, { saved = it }) } }
        for (day in "一二三四五六日") compose.onNodeWithText(day.toString()).assertIsDisplayed().performClick()
        screenshot("05-departure-editor-synthetic.png")
        compose.onNodeWithText("保存预约").performClick()
        compose.runOnIdle { assertEquals((1..7).toSet(), saved!!.days); assertEquals(prefs, saved!!.preferences); assertEquals("synthetic-only", saved!!.vehicleKey) }
    }
    @Test fun actualRemoteViewsInflateAtBothSupportedHeights() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.runOnUiThread {
            for ((compact, height) in listOf(false to 340, true to 224)) {
                val waiting = VehicleWidgetProvider.views(context, overview.copy(refreshingAt = now.toEpochMilli()), compact, now).apply(context, FrameLayout(context))
                assertTrue("refresh stays recoverable after process death", waiting.findViewById<View>(R.id.widget_refresh).isEnabled)
                val view = VehicleWidgetProvider.views(context, overview, compact, now).apply(context, FrameLayout(context))
                view.layoutDirection = View.LAYOUT_DIRECTION_LTR
                val density = context.resources.displayMetrics.density
                val widthPx = (320 * density).toInt(); val heightPx = (height * density).toInt()
                view.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, widthPx, heightPx)
                assertEquals("34°C", view.findViewById<TextView>(R.id.widget_cabin).text.toString())
                for (id in listOf(R.id.widget_energy, R.id.widget_climate, R.id.widget_cabin)) {
                    val field = view.findViewById<View>(id)
                    val parent = field.parent as View
                    assertTrue("telemetry clipped at height $height: $id", field.bottom <= parent.height - parent.paddingBottom)
                }
                for (id in listOf(R.id.widget_prepare, R.id.widget_find, R.id.widget_lock, R.id.widget_guard, R.id.widget_trunk, R.id.widget_port)) {
                    val control = view.findViewById<View>(id)
                    assertTrue("button height", control.height >= (44 * density).toInt())
                    assertTrue("button width", control.width >= (44 * density).toInt())
                }
                val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
                view.draw(android.graphics.Canvas(bitmap))
                save(bitmap, if (compact) "03-native-widget-compact-synthetic.png" else "02-native-widget-full-synthetic.png")
                bitmap.recycle()
            }
        }
    }
    @Test fun shortLandscapeKeepsAllSixIndependentActionTargets() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.runOnUiThread {
            val view = VehicleWidgetProvider.views(context, overview, true, now, strip = true).apply(context, FrameLayout(context))
            val density = context.resources.displayMetrics.density
            val width = (500*density).toInt(); val height = (100*density).toInt()
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            view.layout(0,0,width,height)
            for (id in listOf(R.id.widget_prepare,R.id.widget_find,R.id.widget_lock,R.id.widget_guard,R.id.widget_trunk,R.id.widget_port)) {
                val button = view.findViewById<View>(id)
                val rect = android.graphics.Rect(); assertTrue(button.getGlobalVisibleRect(rect))
                assertTrue(rect.height() >= 44*density); assertTrue(rect.width() >= 44*density)
                assertTrue(rect.bottom <= height)
            }
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap)); save(bitmap, "04-native-widget-landscape-synthetic.png"); bitmap.recycle()
        }
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { save(it, name); it.recycle() }
    }
    private fun save(bitmap: Bitmap, name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "qa-native-ui").apply { mkdirs() }
        File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
