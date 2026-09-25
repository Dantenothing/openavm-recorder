package com.dante.zeekrcheck

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import com.dante.zeekrcheck.core.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.Instant

/** Synthetic rendering only: no service, account mutation or vehicle request. */
class WidgetStateUiTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val now=Instant.parse("2026-09-20T02:00:00Z")
    private fun state(active:Boolean)=VehicleOverview(vehicleKey="synthetic",nickname="7X · 示例车况",acOn=active,climateSource=now.toEpochMilli(),climateFetched=now.toEpochMilli(),readings=mapOf(
        "cabin_temperature" to OverviewReading(if(active) "29.0 °C" else "22.0 °C",now.toEpochMilli(),now.toEpochMilli()),
        "battery" to OverviewReading("68%",null,now.toEpochMilli()),"range" to OverviewReading("412 km",null,now.toEpochMilli()),
        "lock" to OverviewReading(if(active) "未锁" else "已锁",null,now.toEpochMilli()),
        "sentry" to OverviewReading(if(active) "开启" else "关闭",null,now.toEpochMilli()),
        "trunk" to OverviewReading(if(active) "打开" else "关闭",null,now.toEpochMilli()),
        "port" to OverviewReading(if(active) "打开" else "关闭",null,now.toEpochMilli())))
    private fun session(phase:PreparationPhase,finished:Boolean=false)=PreparationSession("synthetic",now.minusSeconds(60).toEpochMilli(),
        now.plusSeconds(1740).toEpochMilli(),ComfortPreferences(),listOf(ClimateChannel.AC),phase=phase,
        finished=finished,initialTemperature=32.0,lastTemperature=if(phase==PreparationPhase.COOLING) 29.0 else 22.0,lastSource=now.toEpochMilli(),remoteRunningObserved=true)
    private fun render(compact:Boolean,state:VehicleOverview,preparation:PreparationSession?,strip:Boolean=false):View {
        val view=VehicleWidgetProvider.views(context,state,compact,now,"家 · 示例位置",strip=strip,preview=true,preparation=preparation).apply(context,FrameLayout(context))
        // An unattached synthetic view must resolve relative (start/end) drawables as the launcher does.
        view.layoutDirection=View.LAYOUT_DIRECTION_LTR
        val density=context.resources.displayMetrics.density
        val width=(340*density).toInt();val height=((if(strip)100 else if(compact)224 else 340)*density).toInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
        view.layout(0,0,width,height)
        return view
    }
    private fun background(view:View):Int {
        val bitmap=Bitmap.createBitmap(80,80,Bitmap.Config.ARGB_8888)
        val drawable=view.background!!.constantState!!.newDrawable().mutate()
        drawable.setBounds(0,0,80,80);drawable.draw(Canvas(bitmap))
        val color=bitmap.getPixel(40,40);bitmap.recycle();return color
    }
    private fun save(view:View,name:String) {
        val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap))
        val dir=File(context.getExternalFilesDir(null),"qa-widget-state-063").apply { mkdirs() }
        File(dir,name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    }
    @Test fun tailgateReceiptKeepsReportedClosedStateAndShowsGuidanceOnTheCard() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            for (compact in listOf(true, false)) for (result in listOf(CommandResult.ACCEPTED, CommandResult.UNKNOWN)) {
                val feedback = OperationFeedback.message(VehicleCommand.Body(BodyAction.TRUNK_UNLOCK), result)
                val snapshot = state(false).copy(message = feedback)
                val view = render(compact, snapshot, null)
                assertEquals("尾门已关", view.findViewById<TextView>(R.id.widget_trunk).text.toString())
                assertEquals(Color.WHITE, background(view.findViewById(R.id.widget_trunk)))
                assertEquals(R.drawable.ic_widget_trunk, VehicleWidgetProvider.controlIcon("trunk", snapshot))
                assertEquals(feedback, view.findViewById<TextView>(R.id.widget_message).text.toString())
                assertEquals(BodyAction.TRUNK_UNLOCK, CardControl.target("trunk", snapshot.readings["trunk"]?.value))
                assertFalse(OperationFeedback.requiresAcknowledgement(VehicleCommand.Body(BodyAction.TRUNK_UNLOCK), result))
                save(view, "tailgate-${if (compact) "4x2" else "4x3"}-${result.name.lowercase()}.png")
            }
        }
    }
    @Test fun achievedTemperatureAndOrdinaryStatesAreWhiteInEveryWidgetLayout() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            for((compact,strip) in listOf(true to false,false to false,true to true)) {
                val view=render(compact,state(false),session(PreparationPhase.READY,true),strip)
                for(id in listOf(R.id.widget_prepare,R.id.widget_find,R.id.widget_lock,R.id.widget_guard,R.id.widget_trunk,R.id.widget_port))
                    assertEquals("Idle controls must have white backgrounds",Color.WHITE,background(view.findViewById(id)))
                assertTrue(view.findViewById<TextView>(R.id.widget_prepare).text.startsWith("一键备车"))
                save(view,"widget-${if(strip) "strip" else if(compact) "4x2" else "4x3"}-idle.png")
            }
        }
    }
    @Test fun reportedRunningAndAttentionStatesHaveDistinctBackgrounds() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            for(compact in listOf(true,false)) {
                val view=render(compact,state(true),session(PreparationPhase.COOLING))
                val active=background(view.findViewById(R.id.widget_prepare))
                assertNotEquals(Color.WHITE,active)
                assertEquals(active,background(view.findViewById(R.id.widget_guard)))
                assertEquals(Color.WHITE,view.findViewById<TextView>(R.id.widget_prepare).currentTextColor)
                assertEquals(Color.WHITE,view.findViewById<TextView>(R.id.widget_guard).currentTextColor)
                for(id in listOf(R.id.widget_lock,R.id.widget_trunk,R.id.widget_port)) {
                    val attention=background(view.findViewById(id))
                    assertNotEquals(Color.WHITE,attention);assertNotEquals(active,attention)
                }
                assertEquals(Color.WHITE,background(view.findViewById(R.id.widget_find)))
                save(view,"widget-${if(compact) "4x2" else "4x3"}-active.png")
            }
        }
    }
    @Test fun acceptedOrOldTemperatureDoesNotPretendTheAirConditionerIsRunning() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val accepted=render(true,state(false),session(PreparationPhase.ACCEPTED))
            assertEquals(Color.WHITE,background(accepted.findViewById(R.id.widget_prepare)))
            val stale=state(true).let { it.copy(readings=it.readings+("cabin_temperature" to it.readings.getValue("cabin_temperature").copy(source=now.minusSeconds(400).toEpochMilli()))) }
            assertEquals(Color.WHITE,background(render(true,stale,session(PreparationPhase.COOLING)).findViewById(R.id.widget_prepare)))
        }
    }
    @Test fun parkedTimeRemainsVisibleDuringPreparationAndTemperatureTimeIsNamed() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val parked = state(true).copy(motion = MotionReading("Parked", now.minusSeconds(600).toEpochMilli(), now.toEpochMilli(), true))
            for (compact in listOf(true, false)) {
                val view = render(compact, parked, session(PreparationPhase.COOLING))
                assertTrue(view.findViewById<TextView>(R.id.widget_climate).text.startsWith("Parked · "))
                assertTrue(view.findViewById<TextView>(R.id.widget_source).text.contains("车温上报"))
                save(view, "widget-${if (compact) "4x2" else "4x3"}-motion.png")
            }
        }
    }
    @Test fun staleCarTemperatureIsNotRelabelledWithTheNewQueryTime() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val old = state(false).let { it.copy(readings = it.readings + ("cabin_temperature" to it.readings.getValue("cabin_temperature")
                .copy(source = now.minusSeconds(1260).toEpochMilli())), refreshedAt = now.toEpochMilli(),
                message = "已查询云端 · 车温暂无新上报") }
            val view = render(true, old, null)
            val source = view.findViewById<TextView>(R.id.widget_source).text.toString()
            assertTrue(source.contains("车温上报")); assertTrue(source.contains("暂无新上报"))
            assertFalse(view.findViewById<TextView>(R.id.widget_guard).text.contains("暂停"))
            save(view, "widget-4x2-old-source.png")
        }
    }
}
