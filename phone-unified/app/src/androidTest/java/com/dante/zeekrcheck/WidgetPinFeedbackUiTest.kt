package com.dante.zeekrcheck

import android.content.ComponentName
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.dante.zeekrbridge.ui.PhoneLanguage
import com.dante.zeekrbridge.ui.PhoneLanguageMode
import org.junit.*
import org.junit.Assert.*

/** Synthetic launcher outcomes; never pins a real widget or sends a vehicle control. */
class WidgetPinFeedbackUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var language: PhoneLanguageMode
    private val provider = ComponentName("com.dante.synthetic", "SyntheticWidget")

    @Before fun setup() {
        language = PhoneLanguage.mode
        compose.runOnUiThread { PhoneLanguage.selectMode(PhoneLanguageMode.SIMPLIFIED_CHINESE) }
    }
    @After fun restore() { compose.runOnUiThread { PhoneLanguage.selectMode(language) } }

    @Test fun unsupportedLauncherExplainsManualStepsAndCanOpenHome() {
        val platform = FakePlatform(supports = false)
        content(platform)
        compose.onNodeWithTag("widget_add").performClick()
        compose.onNodeWithTag("widget_manual_dialog").assertIsDisplayed()
        compose.onNodeWithText("1 · 回到桌面，长按空白处。").assertIsDisplayed()
        compose.onNodeWithTag("widget_go_home").performClick()
        compose.runOnIdle { assertEquals(0, platform.requests); assertEquals(1, platform.homeOpens) }
    }

    @Test fun acceptedButIgnoredRequestShowsFeedbackThenManualHelp() {
        val platform = FakePlatform()
        content(platform)
        compose.onNodeWithTag("widget_add").performClick()
        compose.onNodeWithTag("widget_pin_status").assertTextEquals("请在桌面弹窗中确认添加")
        compose.onNodeWithTag("widget_add").assertIsNotEnabled()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("widget_manual_dialog").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("widget_pin_status").assertTextEquals("尚未检测到新卡片，可从桌面手动添加。")
        compose.runOnIdle { assertEquals(1, platform.requests) }
    }

    @Test fun anActualNewWidgetIsRequiredBeforeReportingSuccess() {
        val platform = FakePlatform()
        content(platform)
        compose.onNodeWithTag("widget_add").performClick()
        compose.runOnIdle { platform.ids = setOf(17, 23) }
        compose.waitUntil(3_000) { compose.onAllNodesWithText("已添加到桌面").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("widget_add").assertIsEnabled()
        compose.onNodeWithTag("widget_manual_dialog").assertDoesNotExist()
    }

    @Test fun requestExceptionsOfferAVisibleRecoveryInEnglish() {
        compose.runOnUiThread { PhoneLanguage.selectMode(PhoneLanguageMode.ENGLISH) }
        val platform = FakePlatform(failRequest = true)
        content(platform)
        compose.onNodeWithTag("widget_add").performClick()
        compose.onNodeWithTag("widget_manual_dialog").assertIsDisplayed()
        compose.onNodeWithText("Add the widget from your home screen").assertIsDisplayed()
        compose.onNodeWithTag("widget_go_home").assertTextEquals("Open home screen")
    }

    private fun content(platform: FakePlatform) {
        compose.setContent { AssistantTheme { Column { WidgetPinActions(provider, "4×2", platform) } } }
    }

    private class FakePlatform(val supports: Boolean = true, val failRequest: Boolean = false) : WidgetPinPlatform {
        var ids = setOf(17)
        var requests = 0
        var homeOpens = 0
        override fun existingIds(provider: ComponentName) = ids
        override fun supported() = supports
        override fun request(provider: ComponentName): Boolean {
            requests++
            if (failRequest) throw IllegalStateException("Synthetic launcher rejection")
            return true
        }
        override fun openHome() { homeOpens++ }
    }
}
