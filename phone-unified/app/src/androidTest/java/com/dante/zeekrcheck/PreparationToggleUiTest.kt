package com.dante.zeekrcheck

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
import java.time.Instant

/** Synthetic state and callback only. No vehicle service, network or production assistant state. */
class PreparationToggleUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val now = Instant.parse("2026-09-20T06:00:00Z")
    private fun session() = PreparationSession("synthetic", now.minusSeconds(30).toEpochMilli(), now.plusSeconds(600).toEpochMilli(),
        ComfortPreferences(), listOf(ClimateChannel.AC), phase = PreparationPhase.RUNNING, lastSource = now.toEpochMilli())

    @Test fun sameButtonStartsStopsThenWaitsForVehicleBeforeReturningToIdle() {
        val current = mutableStateOf<PreparationSession?>(null)
        val actions = mutableListOf<PreparationTap>()
        compose.setContent { AssistantTheme {
            val s = current.value
            PreparationActionButton(s, now.toEpochMilli(), {
                val action = PreparationControl.decide(PreparationControl.shown(s, "synthetic", now.toEpochMilli()), current.value, "synthetic", now.toEpochMilli())
                actions += action
                if(action == PreparationTap.START) current.value = session()
                if(action == PreparationTap.STOP) current.value = PreparationControl.requestStop(current.value!!, now.toEpochMilli())
            })
        } }
        compose.onNodeWithTag("start_preparation").performClick()
        compose.onNodeWithText(ui("停止备车")).assertIsDisplayed().performClick()
        compose.onNodeWithText(ui("正在结束…")).assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(listOf(PreparationTap.START, PreparationTap.STOP, PreparationTap.OBSERVE), actions)
            current.value = current.value!!.copy(finished = true, phase = PreparationPhase.STOPPED, lastSource = now.toEpochMilli())
        }
        compose.onNodeWithText(ui("一键备车")).assertIsDisplayed()
    }

    @Test fun allWidgetSizesOfferStopAndShowPendingFeedbackInBothLanguages() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val saved = PhoneLanguage.mode
            try {
                for (language in listOf(PhoneLanguageMode.SIMPLIFIED_CHINESE, PhoneLanguageMode.ENGLISH)) {
                    PhoneLanguage.selectMode(language)
                    val state = VehicleOverview(vehicleKey = "synthetic")
                    val active = session()
                    for(compact in listOf(true, false)) {
                        val view = VehicleWidgetProvider.views(context, state, compact, now, preview = true, preparation = active).apply(context, FrameLayout(context))
                        assertTrue(view.findViewById<TextView>(R.id.widget_prepare).text.contains(ui("再点停止")))
                        assertTrue(view.findViewById<View>(R.id.widget_prepare).contentDescription.contains(ui("停止备车")))
                    }
                    val stopping = PreparationControl.requestStop(active, now.toEpochMilli())
                    for(strip in listOf(true, false)) {
                        val view = SmallVehicleWidget.views(context, state.copy(message = "收到停止操作 · 正在结束备车"), strip, now,
                            preparation = stopping, preview = true).apply(context, FrameLayout(context))
                        assertTrue(view.findViewById<View>(R.id.widget_prepare).contentDescription.contains(ui("正在结束…")))
                        assertTrue(view.findViewById<TextView>(R.id.widget_message).text.isNotBlank())
                    }
                }
            } finally { PhoneLanguage.selectMode(saved); VehicleWidgetProvider.updateAll(context) }
        }
    }
}
