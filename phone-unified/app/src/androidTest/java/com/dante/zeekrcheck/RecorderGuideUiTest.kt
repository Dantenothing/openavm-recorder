package com.dante.zeekrcheck

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.dante.zeekrbridge.server.BridgeServer
import com.dante.zeekrbridge.service.BridgeService
import com.dante.zeekrbridge.ui.PhoneLanguage
import com.dante.zeekrbridge.ui.PhoneLanguageMode
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import java.io.File

class RecorderGuideUiTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun bilingualGuideAndConnectionStepsStayReadableWithoutStartingTheReceiver() {
        OpenAvmIntegration.initialize(context)
        assumeFalse(BridgeService.running.value || BridgeServer.state.value.running)
        assumeFalse(context.getSharedPreferences("phone_product_settings",0).getBoolean("auto_start_server",false))
        val saved = PhoneLanguage.mode
        var guide by mutableStateOf(true)
        var generation by mutableIntStateOf(0)
        try {
            compose.setContent { AssistantTheme { key(generation) {
                if (guide) Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    BeginnerGuideScreen(false,false,{}, {}, {})
                } else OpenAvmDestination("connection",false,{}, {}, {})
            } } }
            for (language in listOf(PhoneLanguageMode.SIMPLIFIED_CHINESE, PhoneLanguageMode.ENGLISH)) {
                compose.runOnIdle { PhoneLanguage.selectMode(language); guide=true; generation++ }
                for(step in 0..6) {
                    compose.onNodeWithTag("guide_title").performScrollTo().assertIsDisplayed()
                    capture("guide-${language.storedValue}-${step+1}.png")
                    compose.onNodeWithTag("guide_next").assertIsDisplayed()
                    if(step < 6) compose.onNodeWithTag("guide_next").performClick()
                }
                compose.onNodeWithText(if(language == PhoneLanguageMode.ENGLISH) "3 · Enter the code, then verify identity" else "3 · 填写配对码，再核对手机身份").performScrollTo().assertIsDisplayed()
                compose.onNodeWithTag("guide_recorder").performScrollTo().assertIsDisplayed()
                compose.runOnIdle { guide=false }
                compose.onNodeWithTag("recorder_pair_details").performScrollTo().performClick()
                compose.onNodeWithTag("recorder_start").assertIsEnabled()
                compose.onNodeWithTag("recorder_stop").assertIsNotEnabled()
                capture("connection-${language.storedValue}.png")
                compose.onNodeWithTag("recorder_pair_code").performScrollTo().assertIsDisplayed()
                capture("pairing-${language.storedValue}.png")
                compose.onNodeWithText(if(language == PhoneLanguageMode.ENGLISH) "4 · Send an existing recording" else "4 · 发送一段已有录像").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText(if(language == PhoneLanguageMode.ENGLISH) "Start recording" else "开始录像").assertDoesNotExist()
                compose.runOnIdle { assertFalse(BridgeServer.state.value.running); assertFalse(BridgeService.running.value) }
            }
        } finally { instrumentation.runOnMainSync { PhoneLanguage.selectMode(saved); VehicleWidgetProvider.updateAll(context) } }
    }

    private fun capture(name: String) {
        val image=instrumentation.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        val path=File(context.getExternalFilesDir(null),"qa-local16/$name").apply { parentFile!!.mkdirs() }
        path.outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }; image.recycle()
    }
}
