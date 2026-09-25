package com.dante.zeekrcheck

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import android.app.Application
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.dante.zeekrcheck.core.*
import java.io.File
import java.time.Instant

class ClimateUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun shortTapCyclesAndLongPressSelectsAnAbsoluteLevelWithoutFakingReadback() {
        var chosen: Int? by mutableStateOf(null)
        compose.setContent {
            MaterialTheme {
                Row {
                    SeatControl("左前座椅", 1, chosen, true, "LEFT", Modifier.weight(1f)) { chosen = it }
                    SeatControl("右前座椅", null, null, true, "RIGHT", Modifier.weight(1f)) {}
                }
            }
        }
        compose.onNodeWithTag("seat_LEFT").performClick()
        compose.runOnIdle { assertEquals(2, chosen) }
        compose.onNodeWithTag("seat_LEFT").performTouchInput { longClick() }
        compose.onNodeWithTag("seat_choice_LEFT_3").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(3, chosen) }
        compose.onNodeWithTag("seat_reported_LEFT", useUnmergedTree = true).assertTextEquals("回读 1 档")
        compose.onNodeWithTag("seat_LEFT").performTouchInput { longClick() }
        compose.onNodeWithTag("seat_choice_LEFT_0").performClick()
        compose.runOnIdle { assertEquals(0, chosen) }
        compose.onNodeWithTag("seat_reported_RIGHT", useUnmergedTree = true).assertTextEquals("回读 未知")
    }
    @Test fun fullPanelShowsCurrentAndQueuedLevelsAndRendersTheQuickMenu() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val model = CheckViewModel(instrumentation.targetContext.applicationContext as Application)
        val store = androidx.lifecycle.ViewModelStore().apply { put("qa", model) }
        val snapshot = ClimateSnapshot(frontLeft = 1, frontRight = 0, acOn = true,
            cabinTemperature = 29.5, sourceTime = Instant.parse("2026-09-19T06:00:00Z"), fetchedAt = Instant.now())
        val state = ClimateUiState(enabled = true, snapshot = snapshot, queue = ClimateQueueState(
            phase = ClimatePhase.WAITING, inFlight = ClimateTarget(ClimateChannel.FRONT_LEFT, 2),
            pending = listOf(ClimateTarget(ClimateChannel.FRONT_LEFT, 3))), requestAttempts = 1)
        try {
            compose.setContent {
                MaterialTheme {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        Text("交互预览 · 合成数据 · 不连接车辆")
                        ClimatePanel(state, false, model)
                    }
                }
            }
            compose.onNodeWithTag("seat_reported_FRONT_LEFT", useUnmergedTree = true).assertTextEquals("回读 1 档")
            screenshot("05-climate-panel-synthetic.png")
            compose.onNodeWithTag("seat_FRONT_LEFT").performTouchInput { longClick() }
            compose.onNodeWithTag("seat_choice_FRONT_LEFT_3").assertIsDisplayed()
            screenshot("06-climate-menu-synthetic.png")
        } finally { compose.runOnIdle { store.clear() } }
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "qa").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
