package com.dante.zeekrcheck

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.dante.zeekrbridge.ui.PhoneLanguage
import com.dante.zeekrbridge.ui.PhoneLanguageMode
import com.dante.zeekrcheck.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant

/** Only synthetic snapshots and callbacks. Never opens a control service or changes saved vehicle state. */
class TemperatureUpdateUiTest {
    @get:Rule val compose = createComposeRule()
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

    @Test fun disclosureStaysVisibleAndSecondTapRequestsEndWithoutClearingProgress() {
        val language = PhoneLanguage.mode
        val current = mutableStateOf<TemperatureUpdate?>(null)
        var taps = 0
        try {
            compose.runOnUiThread { PhoneLanguage.selectMode(PhoneLanguageMode.SIMPLIFIED_CHINESE) }
            compose.setContent { AssistantTheme { TemperatureUpdatePanel(current.value, true, {
                taps++
                current.value = if (current.value == null) task(TemperaturePhase.OBSERVING) else current.value!!.copy(cancelRequested = true)
            }, at) } }
            compose.onNodeWithText("会短暂开启空调").assertIsDisplayed()
            compose.onNodeWithTag("temperature_task_action").performClick()
            compose.onNodeWithText("结束取温").assertIsDisplayed().performClick()
            compose.onNodeWithTag("temperature_task_progress").assertExists()
            compose.onNodeWithTag("temperature_task_action").assertIsNotEnabled()
            compose.runOnIdle { assertEquals(2, taps) }
        } finally { compose.runOnUiThread { PhoneLanguage.selectMode(language) } }
    }

    @Test fun temperatureSuccessNeverHidesUnconfirmedStopInEnglish() {
        val language = PhoneLanguage.mode
        try {
            compose.runOnUiThread { PhoneLanguage.selectMode(PhoneLanguageMode.ENGLISH) }
            val pending = task(TemperaturePhase.NEEDS_STOP)
            compose.setContent { AssistantTheme { TemperatureUpdatePanel(pending, true, {}, at) } }
            compose.onNodeWithText(ui(pending.message)).assertIsDisplayed()
            compose.onNodeWithText("Stop temporary A/C").assertIsDisplayed()
            compose.onNodeWithTag("temperature_task_progress").assertDoesNotExist()
            assertFalse(Regex("[\u3400-\u9fff]").containsMatchIn(ui(pending.message)))
        } finally { compose.runOnUiThread { PhoneLanguage.selectMode(language) } }
    }

}
