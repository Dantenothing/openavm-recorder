package com.dante.zeekrcheck

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import com.dante.zeekrcheck.core.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.Instant

/** Only renders synthetic RemoteViews; never starts a service or touches production account state. */
class PreparationUiTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val now=Instant.parse("2026-09-20T01:00:00Z")
    private fun overview()=VehicleOverview(vehicleKey="synthetic",nickname="7X · 示例车况",acOn=true,climateSource=now.toEpochMilli(),climateFetched=now.toEpochMilli(),readings=mapOf(
        "cabin_temperature" to OverviewReading("29.0 °C",now.toEpochMilli(),now.toEpochMilli()),
        "battery" to OverviewReading("68%",null,now.toEpochMilli()),"range" to OverviewReading("412 km",null,now.toEpochMilli()),
        "lock" to OverviewReading("已锁",null,now.toEpochMilli()),"trunk" to OverviewReading("关闭",null,now.toEpochMilli()),
        "sentry" to OverviewReading("开启",null,now.toEpochMilli())),message="备车进度会自动更新")
    private fun session(phase:PreparationPhase)=PreparationSession("synthetic",now.minusSeconds(60).toEpochMilli(),now.plusSeconds(1740).toEpochMilli(),
        ComfortPreferences(),listOf(ClimateChannel.AC),phase=phase,initialTemperature=32.0,lastTemperature=29.0,lastSource=now.toEpochMilli(),remoteRunningObserved=true)
    private fun render(compact:Boolean,session:PreparationSession?,state:VehicleOverview=overview()):View {
        val view=VehicleWidgetProvider.views(context,state,compact,now,"家 · 示例位置",preview=true,preparation=session).apply(context,FrameLayout(context))
        view.layoutDirection=View.LAYOUT_DIRECTION_LTR
        val density=context.resources.displayMetrics.density
        val width=(320*density).toInt(); val height=((if(compact)224 else 340)*density).toInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
        view.layout(0,0,width,height)
        return view
    }
    @Test fun explicitOpenEntryOpensMainApplicationWithoutAnExecutionPayload() {
        val intent=VehicleWidgetProvider.openAppIntent(context,9061)
        assertEquals(MainActivity::class.java.name,intent.component!!.className)
        assertEquals(Intent.ACTION_MAIN,intent.action)
        assertTrue(intent.categories.contains(Intent.CATEGORY_LAUNCHER))
        assertFalse(intent.hasExtra("executeOnOpen")); assertFalse(intent.hasExtra("cardAction")); assertFalse(intent.hasExtra("widgetAction"))
        assertEquals("打开充电口",VehicleWidgetProvider.controlLabel("port",VehicleOverview()))
    }
    @Test fun bothGridSizesShowProgressOnTheButtonAndKeepAllActionsAccessible() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            for(compact in listOf(true,false)) for(phase in listOf(PreparationPhase.READING,PreparationPhase.ACCEPTED,PreparationPhase.RUNNING,PreparationPhase.COOLING,PreparationPhase.READY)) {
                val view=render(compact,session(phase))
                assertEquals("打开应用 ↗",view.findViewById<TextView>(R.id.widget_open).text.toString())
                val button=view.findViewById<TextView>(R.id.widget_prepare)
                val expected = if (phase == PreparationPhase.RUNNING) "预冷至 22°C" else PreparationProgress.title(phase)
                assertTrue("29°C toward 22°C must show the cooling target before measured progress",button.text.startsWith(expected))
                assertEquals(2,button.lineCount)
                assertEquals("29.0°C",view.findViewById<TextView>(R.id.widget_cabin).text.toString())
                assertEquals(if(phase in setOf(PreparationPhase.READING,PreparationPhase.ACCEPTED)) View.VISIBLE else View.GONE,view.findViewById<View>(R.id.widget_prepare_progress).visibility)
                val density=context.resources.displayMetrics.density
                for(id in listOf(R.id.widget_prepare,R.id.widget_find,R.id.widget_lock,R.id.widget_guard,R.id.widget_trunk,R.id.widget_port)) {
                    val field=view.findViewById<View>(id)
                    assertTrue(field.height>=44*density); assertTrue(field.width>=44*density)
                }
                assertEquals("progress text must fit",0,(0 until button.lineCount).sumOf { button.layout.getEllipsisCount(it) })
                val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                val directory=File(context.getExternalFilesDir(null),"qa-preparation-061").apply { mkdirs() }
                File(directory,"widget-${if(compact) "4x2" else "4x3"}-${phase.name.lowercase()}-synthetic.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                bitmap.recycle()
            }
        }
    }
    @Test fun immediateTapFeedbackPrecedesTheNetworkAndStaleProgressNeverClaimsCooling() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val connecting=render(true,null,overview().copy(actionInFlight="prepare",actionAt=now.toEpochMilli()))
            assertTrue(connecting.findViewById<TextView>(R.id.widget_prepare).text.startsWith("正在准备"))
            assertEquals(View.VISIBLE,connecting.findViewById<View>(R.id.widget_prepare_progress).visibility)
            val stale=render(true,session(PreparationPhase.COOLING).copy(lastSource=now.minusSeconds(400).toEpochMilli()))
            assertTrue(stale.findViewById<TextView>(R.id.widget_prepare).text.startsWith("备车已受理"))
            assertFalse(stale.findViewById<TextView>(R.id.widget_prepare).text.contains("正在降温"))
        }
    }
    @Test fun bothGridSizesReturnToOneTapAfterAllKnownEndingsWithoutAProgressSpinner() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            for(compact in listOf(true,false)) for(phase in listOf(PreparationPhase.STOPPED,PreparationPhase.HANDED_OVER,PreparationPhase.EXPIRED,PreparationPhase.FAILED,PreparationPhase.READY)) {
                val ended=session(phase).copy(finished=true,endedAt=now.minusSeconds(60).toEpochMilli())
                val view=render(compact,ended,overview().copy(acOn=true))
                val button=view.findViewById<TextView>(R.id.widget_prepare)
                assertEquals("一键备车\n目标 22°C",button.text.toString())
                assertEquals(CardTone.NEUTRAL.foreground.toInt(),button.currentTextColor)
                assertEquals(View.GONE,view.findViewById<View>(R.id.widget_prepare_progress).visibility)
                assertFalse(view.findViewById<TextView>(R.id.widget_message).text.contains("备车进度会自动更新"))
                assertEquals(0,(0 until button.lineCount).sumOf { button.layout.getEllipsisCount(it) })
                if(phase==PreparationPhase.HANDED_OVER) {
                    val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
                    view.draw(Canvas(bitmap))
                    val directory=File(context.getExternalFilesDir(null),"qa-preparation-072").apply { mkdirs() }
                    File(directory,"widget-${if(compact) "4x2" else "4x3"}-handover-synthetic.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                    bitmap.recycle()
                }
            }
        }
    }
    @Test fun unknownResultKeepsItsWarningAndRecentHandoverHasBriefFeedback() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val unknown=render(true,session(PreparationPhase.UNKNOWN).copy(finished=true))
            assertTrue(unknown.findViewById<TextView>(R.id.widget_prepare).text.startsWith("备车结果待核实"))
            val handover=render(false,session(PreparationPhase.HANDED_OVER).copy(finished=true,endedAt=now.toEpochMilli()))
            assertTrue(handover.findViewById<TextView>(R.id.widget_message).text.contains("车辆进入行驶模式"))
            val strip=VehicleWidgetProvider.views(context,overview(),true,now,strip=true,preview=true,
                preparation=session(PreparationPhase.HANDED_OVER).copy(finished=true)).apply(context,FrameLayout(context))
            assertEquals("一键备车",strip.findViewById<TextView>(R.id.widget_prepare).text.toString())
        }
    }
}
