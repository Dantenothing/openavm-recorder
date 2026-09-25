package com.dante.zeekrcheck

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.platform.app.InstrumentationRegistry
import com.dante.zeekrbridge.ui.PhoneLanguage
import com.dante.zeekrbridge.ui.PhoneLanguageMode
import com.dante.zeekrcheck.core.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.time.Instant

/** Render-only fixtures: no model, service, account writes or real vehicle commands. */
class ThermalVisualUiTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val now = Instant.parse("2026-09-21T10:00:00Z")
    private val time = now.toEpochMilli()
    private var language = PhoneLanguageMode.SIMPLIFIED_CHINESE
    @Before fun saveLanguage() { language = PhoneLanguage.mode }
    @After fun restoreLanguage() { instrumentation.runOnMainSync { PhoneLanguage.selectMode(language);VehicleWidgetProvider.updateAll(context) } }
    private fun state(temp:String="29.0 °C",on:Boolean=true) = VehicleOverview(vehicleKey="synthetic-thermal",nickname="Demo 7X",
        acOn=on,blowerActive=on,climateSource=time,climateFetched=time,motion=MotionReading("Parked",time,time,false),readings=mapOf(
            "cabin_temperature" to OverviewReading(temp,time,time),"battery" to OverviewReading("68%",time,time),
            "range" to OverviewReading("412 km",time,time),"sentry" to OverviewReading("关闭",time,time),
            "lock" to OverviewReading("已锁",time,time),"trunk" to OverviewReading("关闭",time,time),"port" to OverviewReading("关闭",time,time)))
    private fun session(phase:PreparationPhase) = PreparationSession("synthetic-thermal",time-120_000,time+600_000,
        ComfortPreferences(),listOf(ClimateChannel.AC),phase=phase,lastSource=time,remoteRunningObserved=true,initialTemperature=34.0,lastTemperature=29.0)
    private fun scenarios() = linkedMapOf(
        "hot" to (state(on=false) to null), "cold" to (state("12.0 °C",false) to null),
        "cooling" to (state() to session(PreparationPhase.COOLING)),
        "warming" to (state("12.0 °C") to session(PreparationPhase.WARMING)),
        "warming-start" to (state("18.0 °C") to session(PreparationPhase.RUNNING).copy(initialTemperature=18.0,lastTemperature=18.0)),
        "cooling-start" to (state("32.0 °C") to session(PreparationPhase.RUNNING).copy(initialTemperature=32.0,lastTemperature=32.0)),
        "hold" to (state("22.0 °C") to session(PreparationPhase.HOLD)),
        "ready" to (state("22.0 °C",false) to session(PreparationPhase.READY).copy(finished=true)),
        "waiting" to (state() to session(PreparationPhase.ACCEPTED)),
        "stopping" to (state() to session(PreparationPhase.STOPPING)),
        "air" to (state().copy(acOn=false) to null),
        "stale" to (state().copy(readings=state().readings+("cabin_temperature" to OverviewReading("29.0 °C",time-600_000,time))) to session(PreparationPhase.COOLING)))
    private fun save(bitmap:Bitmap,name:String) {
        val dir=File(context.getExternalFilesDir(null),"qa-thermal-local11").apply { mkdirs() }
        File(dir,name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
    }
    @Test fun bigWidgetsShareEvidenceAndKeepMotionTimeInBothLanguages() {
        instrumentation.runOnMainSync {
            for(lang in listOf(PhoneLanguageMode.SIMPLIFIED_CHINESE,PhoneLanguageMode.ENGLISH)) {
                PhoneLanguage.selectMode(lang)
                for((name,pair) in scenarios()) for(compact in listOf(true,false)) for(widthDp in listOf(250,340)) {
                    val (o,s)=pair;val visual=ThermalPresentation.from(o,s,now)
                    val view=VehicleWidgetProvider.views(context,o,compact,now,"家 · 示例位置",preview=true,preparation=s).apply(context,FrameLayout(context))
                    assertNull("Car image must not have a clipped oval background",view.findViewById<View>(R.id.widget_car).background)
                    val d=context.resources.displayMetrics.density
                    val width=(widthDp*d).toInt();val height=((if(compact)224 else 340)*d).toInt()
                    view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));view.layout(0,0,width,height)
                    assertEquals(if(visual.airflow==Airflow.NONE)View.GONE else View.VISIBLE,view.findViewById<View>(R.id.widget_airflow).visibility)
                    val status=view.findViewById<TextView>(if(compact)R.id.widget_cabin_caption else R.id.widget_thermal)
                    val expected=if(!compact)visual.label else if(o.readings["cabin_temperature"]?.fresh(now)!=true)o.cabinCaption(now)
                        else if(visual.airflow!=Airflow.NONE)visual.compactLabel else o.thermalLabel(now)
                    assertEquals(ui(expected),status.text.toString())
                    if(status.height<status.layout.height) {
                        val debug=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);view.draw(Canvas(debug));save(debug,"layout-investigation.png");debug.recycle()
                    }
                    assertTrue("Thermal label clipped vertically: $name/$lang/$widthDp compact=$compact view=${status.width}x${status.height} layout=${status.layout.width}x${status.layout.height} lines=${status.lineCount} font=${status.includeFontPadding} parent=${(status.parent as View).height}",status.height>=status.layout.height)
                    assertTrue(view.findViewById<TextView>(R.id.widget_climate).text.startsWith("Parked ·"))
                    assertEquals(o.cabin(now),view.findViewById<TextView>(R.id.widget_cabin).text.toString())
                    val cabin=view.findViewById<TextView>(R.id.widget_cabin)
                    assertEquals("Degrees and unit must stay together",1,cabin.layout.lineCount)
                    assertTrue("Cabin temperature clipped: $name/$lang/$widthDp compact=$compact height=${cabin.height} layout=${cabin.layout.height}",cabin.height>=cabin.layout.height)
                    assertEquals(0,cabin.layout.getEllipsisCount(0))
                    if(name=="stale") {
                        val control=view.findViewById<TextView>(R.id.widget_prepare)
                        assertTrue(control.text.startsWith(ui("备车状态待核实")))
                        assertFalse(control.text.contains(ui("正在降温")))
                        assertTrue(control.contentDescription.contains(ui("停止备车")))
                    }
                    if(widthDp==340 && (lang==PhoneLanguageMode.SIMPLIFIED_CHINESE || name in listOf("warming-start","cooling-start","cooling","hold","stale"))) {
                        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap))
                        save(bitmap,"$name-${if(compact)"4x2" else "4x3"}-${lang.storedValue}.png");bitmap.recycle()
                    }
                }
            }
        }
    }
    @Test fun homeCarKeepsItsEditActionAndChangesFlowWithoutTouchingControls() {
        var visual by mutableStateOf(ThermalPresentation.from(state(),session(PreparationPhase.COOLING),now))
        var clicks=0
        compose.mainClock.autoAdvance=false
        compose.setContent { AssistantTheme { AssistantCard { Column {
            VehicleArt(null,Modifier.fillMaxWidth().height(210.dp).testTag("thermal_car"),visual.ambient,visual.airflow) { clicks++ }
            UiText(visual.label)
        } } } }
        for((name,pair) in scenarios()) {
            compose.runOnIdle { visual=ThermalPresentation.from(pair.first,pair.second,now) }
            compose.mainClock.advanceTimeBy(500)
            compose.onNodeWithTag("thermal_air_${visual.airflow.name}",useUnmergedTree=true).assertExists()
            save(compose.onNodeWithTag("thermal_car").captureToImage().asAndroidBitmap(),"app-$name.png")
        }
        compose.onNodeWithTag("thermal_car").performClick();compose.runOnIdle { assertEquals(1,clicks) }
    }
    @Test fun airflowStopsAdvancingWhenTheHostLeavesTheForeground() {
        lateinit var registry:LifecycleRegistry
        val owner=object:LifecycleOwner { override val lifecycle:Lifecycle get()=registry }
        compose.runOnUiThread { registry=LifecycleRegistry(owner).apply { currentState=Lifecycle.State.RESUMED } }
        compose.mainClock.autoAdvance=false
        compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) {
            ThermalAirflow(Airflow.COOLING,Modifier.size(320.dp,178.dp).testTag("air_test"))
        } }
        compose.mainClock.advanceTimeBy(100)
        val first=compose.onNodeWithTag("air_test").captureToImage().asAndroidBitmap()
        compose.mainClock.advanceTimeBy(1000)
        val next=compose.onNodeWithTag("air_test").captureToImage().asAndroidBitmap()
        assertFalse("Foreground airflow must move",first.sameAs(next))
        compose.runOnUiThread { registry.currentState=Lifecycle.State.STARTED }
        compose.mainClock.advanceTimeBy(100)
        val stopped=compose.onNodeWithTag("air_test").captureToImage().asAndroidBitmap()
        compose.mainClock.advanceTimeBy(1000)
        assertTrue("Background must not animate",stopped.sameAs(compose.onNodeWithTag("air_test").captureToImage().asAndroidBitmap()))
        compose.runOnUiThread { registry.currentState=Lifecycle.State.DESTROYED }
    }
    @Test fun staticOverlaysLeaveCarPaintAndPrivatePlateUntouched() {
        val appearance=VehicleAppearance(bodyColor="#356EAD",plateEnabled=true,plateText="DEMO 123")
        val car=AppearanceRenderer.render(context,appearance,320,true)
        val untouched=car.copy(Bitmap.Config.ARGB_8888,false)
        for(flow in listOf(Airflow.COOLING,Airflow.WARMING,Airflow.HOLD,Airflow.AIR)) {
            val overlay=ThermalWidgetArtwork.bitmap(flow)
            assertSame(overlay,ThermalWidgetArtwork.bitmap(flow))
            val merged=car.copy(Bitmap.Config.ARGB_8888,true);Canvas(merged).drawBitmap(overlay,0f,0f,null)
            // Front plate/bonnet and body panels are outside the window airflow region.
            for(y in 90 until car.height)for(x in 0 until car.width)assertEquals(car.getPixel(x,y),merged.getPixel(x,y))
            merged.recycle()
        }
        assertTrue(car.sameAs(untouched));car.recycle();untouched.recycle()
    }
}
