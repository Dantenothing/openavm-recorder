package com.dante.zeekrcheck

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
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

/** No ViewModel, service or cloud commands: only synthetic state and local callbacks. */
class SmartPreparationUiTest {
    @get:Rule val compose=createComposeRule()
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private lateinit var language:PhoneLanguageMode
    private val now=Instant.parse("2026-09-20T06:00:00Z")
    @Before fun saveLanguage() { language=PhoneLanguage.mode }
    @After fun restoreLanguage() { instrumentation.runOnMainSync { PhoneLanguage.selectMode(language); VehicleWidgetProvider.updateAll(context) } }

    @Test fun independentPermissionsAndNewAutomaticFinishPreferenceAreExplicitlySaved() {
        instrumentation.runOnMainSync { PhoneLanguage.selectMode(PhoneLanguageMode.ENGLISH) }
        var saved:ComfortPreferences?=null
        compose.setContent { AssistantTheme { PreferenceEditor(ComfortPreferences(finishWhenComfortable=false),{}, { saved=it }) } }
        for(text in listOf("Steering wheel heating","Seat heating","Seat ventilation","Stop when comfortable (immediate preparation)"))
            compose.onNodeWithText(text).performScrollTo().performClick()
        compose.onNodeWithTag("save_comfort_preferences").performClick()
        compose.runOnIdle {
            assertNotNull(saved); assertTrue(saved!!.steeringHeat && saved!!.seatHeat && saved!!.seatVentilation)
            assertTrue(saved!!.finishWhenComfortable); assertEquals(22,saved!!.target)
        }
    }

    @Test fun bothLanguagesAndAllWidgetsPresentNewStagesWithoutFalseCompletion() {
        instrumentation.runOnMainSync {
            val state=VehicleOverview(vehicleKey="synthetic",nickname="Demo 7X",acOn=true,climateSource=now.toEpochMilli(),climateFetched=now.toEpochMilli(),
                readings=mapOf("cabin_temperature" to OverviewReading("22.0 °C",now.toEpochMilli(),now.toEpochMilli())))
            for(lang in listOf(PhoneLanguageMode.SIMPLIFIED_CHINESE,PhoneLanguageMode.ENGLISH)) {
                PhoneLanguage.selectMode(lang)
                for(phase in listOf(PreparationPhase.SURFACE_FINISH,PreparationPhase.HOLD,PreparationPhase.DEGRADED)) {
                    val s=PreparationSession("synthetic",now.minusSeconds(300).toEpochMilli(),now.plusSeconds(600).toEpochMilli(),
                        ComfortPreferences(),listOf(ClimateChannel.AC),phase=phase,lastSource=now.toEpochMilli(),remoteRunningObserved=true,
                        thermal=ThermalSession(now.plusSeconds(300).toEpochMilli(),startElapsed=1000,
                            suspended=phase==PreparationPhase.DEGRADED,suspensionReason=if(phase==PreparationPhase.DEGRADED) "控制结果待核实，自动调节已暂停" else ""))
                    assertEquals(if(phase==PreparationPhase.DEGRADED) CardTone.ATTENTION else CardTone.ACTIVE,CardAppearance.tone("prepare",state,now,s))
                    for(size in listOf("4x2","4x3","2x2","4x1")) {
                        val remote=if(size=="4x2" || size=="4x3") VehicleWidgetProvider.views(context,state,size=="4x2",now,preview=true,preparation=s)
                            else SmallVehicleWidget.views(context,state,size=="4x1",now,preparation=s,preview=true)
                        val view=remote.apply(context,FrameLayout(context))
                        val density=context.resources.displayMetrics.density
                        val width=((if(size=="2x2") 180 else 370)*density).toInt()
                        val height=((when(size) { "4x1" -> 100; "2x2" -> 200; "4x2" -> 224; else -> 340 })*density).toInt()
                        view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
                        view.layout(0,0,width,height)
                        val control=view.findViewById<View>(R.id.widget_prepare)
                        assertTrue(control.contentDescription.contains(ui("停止备车")))
                        if(size.startsWith("4x") && size!="4x1") {
                            val button=control as TextView
                            assertTrue(button.text.startsWith(ui(PreparationProgress.title(phase))))
                            assertFalse(button.text.contains("已准备好"))
                        }
                        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
                        view.draw(Canvas(bitmap))
                        val dir=File(context.getExternalFilesDir(null),"qa-smart-preparation").apply { mkdirs() }
                        File(dir,"$size-${lang.storedValue}-${phase.name}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                        bitmap.recycle()
                    }
                }
            }
        }
    }
}
