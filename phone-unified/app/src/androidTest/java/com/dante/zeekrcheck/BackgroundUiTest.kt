package com.dante.zeekrcheck

import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.dante.zeekrcheck.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/** Synthetic UI only. No saved configuration, live client, system settings or vehicle actions. */
class BackgroundUiTest {
    @get:Rule val compose = createComposeRule()
    private val now = Instant.parse("2026-09-20T02:00:00Z")
    @Test fun readOnlyCheckUsesDedicatedCallbackAndShowsItsResult() {
        var taps = 0
        val status = SyncStatus().started(now.toEpochMilli(), SyncReason.PERIODIC)
            .finished(now.toEpochMilli(), ProbeOutcome.SUCCESS, "已查询 · 温度暂无新上报")
        compose.setContent { AssistantTheme { SyncStatusCard(status, false, "只读自检：车辆位置缺少近期采样证据。", { taps++ }) } }
        compose.onNodeWithTag("background_diagnostic").performClick()
        compose.runOnIdle { assertEquals(1, taps) }
        compose.onNodeWithTag("background_last_result").assertTextEquals("已查询 · 温度暂无新上报")
        compose.onNodeWithTag("background_diagnostic_result").assertExists()
    }
    @Test fun compactWidgetShowsOriginalTemperatureTimeAndManualPauseWithoutHidingSixActions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val state = VehicleOverview(refreshedAt = now.toEpochMilli(), readings = mapOf(
            "cabin_temperature" to OverviewReading("25.4 °C", now.minusSeconds(3600).toEpochMilli(), now.toEpochMilli()),
            "sentry" to OverviewReading("关闭", null, now.toEpochMilli())))
        compose.runOnUiThread {
            val view = VehicleWidgetProvider.views(context, state, true, now, preview = true, guardPaused = true).apply(context, FrameLayout(context))
            val density = context.resources.displayMetrics.density
            view.layoutDirection = View.LAYOUT_DIRECTION_LTR
            view.measure(View.MeasureSpec.makeMeasureSpec((320*density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((224*density).toInt(), View.MeasureSpec.EXACTLY))
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            assertEquals("上次车温", view.findViewById<TextView>(R.id.widget_cabin_caption).text.toString())
            assertEquals("本次暂停", view.findViewById<TextView>(R.id.widget_guard).text.toString())
            assertEquals("刷新", view.findViewById<TextView>(R.id.widget_refresh).text.toString())
            assertTrue(view.findViewById<View>(R.id.widget_refresh).contentDescription.contains(state.queryLabel(now)))
            assertTrue(view.findViewById<TextView>(R.id.widget_source).text.contains(state.readings.getValue("cabin_temperature").timeLabel(now).removeSuffix(" 记录")))
            val energy = view.findViewById<TextView>(R.id.widget_energy)
            val textHeight = energy.paint.fontMetricsInt.let { it.descent - it.ascent }
            assertTrue("Battery/range must retain a complete text line: ${energy.height}/$textHeight", energy.height >= textHeight)
            val footer = view.findViewById<TextView>(R.id.widget_message)
            assertEquals(View.VISIBLE, footer.visibility); assertTrue(footer.text.contains("本次自动守护已暂停"))
            for (id in listOf(R.id.widget_prepare, R.id.widget_find, R.id.widget_lock, R.id.widget_guard, R.id.widget_trunk, R.id.widget_port)) {
                assertTrue(view.findViewById<View>(id).height >= 44*density)
            }
        }
    }
}
