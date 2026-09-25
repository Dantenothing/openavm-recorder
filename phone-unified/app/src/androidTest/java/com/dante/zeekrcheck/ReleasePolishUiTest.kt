package com.dante.zeekrcheck

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.dante.zeekrbridge.ui.PhoneLanguage
import com.dante.zeekrbridge.ui.PhoneLanguageMode
import com.dante.zeekrcheck.core.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.time.Instant

/** Synthetic UI only; no live ViewModel or command transport. Language preference is restored. */
class ReleasePolishUiTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var savedLanguage: PhoneLanguageMode
    @Before fun before() { savedLanguage = PhoneLanguage.mode; instrumentation.runOnMainSync { PhoneLanguage.selectMode(PhoneLanguageMode.ENGLISH) } }
    @After fun after() { instrumentation.runOnMainSync { PhoneLanguage.selectMode(savedLanguage); VehicleWidgetProvider.updateAll(context) } }

    @Test fun renameValidatesBlankAndPreservesUserText() {
        var saved: String? = null
        compose.setContent { AssistantTheme { RenameVehicleDialog("家", {}, { saved = it }) } }
        compose.onNodeWithTag("vehicle_nickname").assertTextContains("家")
        compose.onNodeWithTag("vehicle_nickname").performTextReplacement("   ")
        compose.onNodeWithTag("save_nickname").assertIsNotEnabled()
        compose.onNodeWithTag("vehicle_nickname").performTextReplacement("  Family 7X  ")
        compose.onNodeWithTag("save_nickname").performClick()
        compose.runOnIdle { assertEquals("Family 7X", saved) }
    }
    @Test fun seatModeSelectionSendsNothingAndHeatLevelUsesItsOwnChannel() {
        val sent = mutableListOf<Pair<ClimateChannel, Int>>()
        val snapshot = ClimateSnapshot(frontLeft = 1, heatLeft = 2, fetchedAt = Instant.now())
        compose.setContent { AssistantTheme { SeatModeControls(snapshot, ClimateQueueState(), true) { c, l -> sent += c to l } } }
        compose.onNodeWithTag("seat_mode_heat").performClick()
        compose.runOnIdle { assertTrue(sent.isEmpty()) }
        compose.onNodeWithTag("seat_reported_HEAT_LEFT", useUnmergedTree = true).assertTextEquals("Reported 2 level")
        compose.onNodeWithTag("seat_HEAT_LEFT").performTouchInput { longClick() }
        compose.onNodeWithTag("seat_choice_HEAT_LEFT_3").performClick()
        compose.onNodeWithTag("seat_mode_vent").performClick()
        compose.onNodeWithTag("seat_reported_FRONT_LEFT", useUnmergedTree = true).assertTextEquals("Reported 1 level")
        compose.runOnIdle { assertEquals(listOf(ClimateChannel.HEAT_LEFT to 3), sent) }
    }
    @Test fun guideRoutesToRealSetupAndFinishesWithoutControlActions() {
        val routes = mutableListOf<String>(); var finished = false
        compose.setContent { AssistantTheme { Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            BeginnerGuideScreen(false, false, { routes += it }, {}, { finished = true })
        } } }
        compose.onNodeWithTag("guide_next").assertIsDisplayed().performClick()
        compose.onNodeWithText("Connect vehicle").performScrollTo().performClick()
        compose.onNodeWithTag("guide_next").assertIsDisplayed().performClick()
        compose.onNodeWithText("Set home location").performScrollTo().performClick()
        compose.onNodeWithTag("guide_next").assertIsDisplayed().performClick()
        compose.onNodeWithText("Set preparation preferences").performScrollTo().performClick()
        compose.onNodeWithTag("guide_next").assertIsDisplayed().performClick()
        compose.onNodeWithText("Choose a widget").performScrollTo().performClick()
        compose.onNodeWithTag("guide_next").assertIsDisplayed().performClick()
        compose.onNodeWithText("Check background settings").performScrollTo().performClick()
        compose.onNodeWithTag("guide_next").assertIsDisplayed().performClick()
        compose.onNodeWithTag("guide_recorder").performScrollTo().performClick()
        compose.onNodeWithTag("guide_next").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf("account", "places", "preferences", "widgets", "background", "connection"), routes); assertTrue(finished) }
    }
    @Test fun languageChangesRecomposeControlsWithoutChangingSeatSelection() {
        compose.setContent { AssistantTheme { SeatModeControls(null, ClimateQueueState(), false) { _, _ -> fail("No controls expected") } } }
        compose.onNodeWithText("Seat heating").assertExists()
        compose.onNodeWithTag("seat_mode_heat").performClick()
        compose.runOnIdle { PhoneLanguage.selectMode(PhoneLanguageMode.SIMPLIFIED_CHINESE) }
        compose.onNodeWithText("座椅加热").assertExists()
        compose.onNodeWithTag("seat_HEAT_LEFT").assertExists()
    }
    @Test fun systemLanguageIsSharedWithMediaAndUnsupportedLanguagesFallBackToEnglish() {
        instrumentation.runOnMainSync {
            try {
                PhoneLanguage.selectMode(PhoneLanguageMode.SYSTEM)
                for ((tag, expected) in listOf("en-AU" to "Vehicle", "zh-CN" to "车辆", "zh-TW" to "车辆", "ar" to "Vehicle")) {
                    PhoneLanguage.updateSystemLocale(java.util.Locale.forLanguageTag(tag))
                    assertEquals(expected, ui("车辆"))
                    assertEquals(expected, PhoneLanguage.text("Vehicle", "车辆"))
                }
            } finally { PhoneLanguage.updateSystemLocale(context.resources.configuration.locales[0]) }
        }
    }
    @Test fun widgetProvidersDeclareTwoByTwoAndFourByOne() {
        val manager = AppWidgetManager.getInstance(context)
        val sizes = listOf(SquareVehicleWidgetProvider::class.java to (2 to 2), StripVehicleWidgetProvider::class.java to (4 to 1))
        for ((provider, cells) in sizes) {
            val info = manager.installedProviders.first { it.provider == ComponentName(context, provider) }
            assertEquals(cells.first, info.targetCellWidth); assertEquals(cells.second, info.targetCellHeight)
        }
        assertEquals(4, VehicleWidgetProvider.providers.size)
    }
    @Test fun smallWidgetsRenderBothLanguagesAndLargeFontsWithVisibleFailureFeedback() {
        val now = Instant.parse("2026-09-20T09:00:00Z")
        val overview = VehicleOverview(vehicleKey = "synthetic", nickname = "Sample 7X", message = "读取失败 · 未发送操作",
            readings = mapOf("battery" to OverviewReading("68 %", null, now.toEpochMilli()),
                "cabin_temperature" to OverviewReading("25.4 °C", now.minusSeconds(600).toEpochMilli(), now.toEpochMilli()),
                "lock" to OverviewReading("已锁", null, now.toEpochMilli()), "sentry" to OverviewReading("关闭", null, now.toEpochMilli())))
        instrumentation.runOnMainSync {
            for (language in listOf(PhoneLanguageMode.ENGLISH, PhoneLanguageMode.SIMPLIFIED_CHINESE)) {
                PhoneLanguage.selectMode(language)
                for (scale in listOf(1f, 1.3f)) for (strip in listOf(false, true)) {
                    val testContext = context.createConfigurationContext(Configuration(context.resources.configuration).apply { fontScale = scale })
                    val view = SmallVehicleWidget.views(testContext, overview, strip, now, preview = true).apply(testContext, FrameLayout(testContext))
                    val density = testContext.resources.displayMetrics.density
                    val width = ((if (strip) 340 else 170) * density).toInt()
                    val height = ((if (strip) 100 else 200) * density).toInt()
                    view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    view.layout(0, 0, width, height)
                    val message = view.findViewById<TextView>(R.id.widget_message)
                    assertTrue(message.text.toString().contains(if (language == PhoneLanguageMode.ENGLISH) "Read failed" else "读取失败"))
                    assertTrue("Status footer must fit", message.bottom <= height - view.paddingBottom)
                    for (id in listOf(R.id.widget_prepare, R.id.widget_find, R.id.widget_lock, R.id.widget_guard, R.id.widget_open, R.id.widget_refresh)) {
                        val button = view.findViewById<View>(id)
                        assertTrue(button.width > 0 && button.height > 0)
                        assertFalse(button.contentDescription.isNullOrBlank())
                    }
                    val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888); view.draw(Canvas(image))
                    val dir = File(context.getExternalFilesDir(null), "qa-release-local4").apply { mkdirs() }
                    File(dir, "widget-${if (strip) "4x1" else "2x2"}-${language.storedValue}-$scale.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    image.recycle()
                }
            }
        }
    }
}
