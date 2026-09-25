package com.dante.zeekrcheck

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.dante.zeekrcheck.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant

class CardControlsUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val now = Instant.parse("2026-09-19T12:00:00Z")
    private fun overview(open: Boolean) = VehicleOverview(nickname="7X · 示例车况", readings=mapOf(
        "cabin_temperature" to OverviewReading("25.4 °C",now.minusSeconds(3600).toEpochMilli(),now.toEpochMilli()),
        "battery" to OverviewReading("68%",null,now.toEpochMilli()),"range" to OverviewReading("412 km",null,now.toEpochMilli()),
        "lock" to OverviewReading(if(open)"未锁" else "已锁",null,now.toEpochMilli()),
        "trunk" to OverviewReading(if(open)"打开" else "关闭",null,now.toEpochMilli()),
        "sentry" to OverviewReading(if(open)"开启" else "关闭",null,now.toEpochMilli()),
        "port" to OverviewReading(if(open)"打开" else "关闭",null,now.toEpochMilli())),message="已刷新 · 温度暂无新上报")

    @Test fun everyControlUsesAServicePendingIntentWithoutLaunchingAnActivity() {
        for ((index,action) in CardControl.actions.withIndex()) {
            val pending = VehicleWidgetProvider.cardPendingIntent(context, 9060+index,action,"a".repeat(64),"已锁",9060)
            assertFalse(pending.isActivity)
            assertTrue(pending.isForegroundService)
            pending.cancel()
        }
    }
    @Test fun lockAndSideTailgateIconsFollowReportedState() {
        assertNotEquals(VehicleWidgetProvider.controlIcon("lock",overview(false)),VehicleWidgetProvider.controlIcon("lock",overview(true)))
        assertNotEquals(VehicleWidgetProvider.controlIcon("trunk",overview(false)),VehicleWidgetProvider.controlIcon("trunk",overview(true)))
        assertEquals("尾门未知",VehicleWidgetProvider.controlLabel("trunk",VehicleOverview()))
        assertEquals("已锁车",VehicleWidgetProvider.controlLabel("lock",overview(false)))
        assertEquals("已解锁",VehicleWidgetProvider.controlLabel("lock",overview(true)))
    }
    @Test fun oldTemperatureStaysReadableInBothWidgetSizesAndAllSixButtonsFit() {
        compose.runOnUiThread {
            for (compact in listOf(true,false)) for (open in listOf(false,true)) {
                val state=overview(open)
                val view=VehicleWidgetProvider.views(context,state,compact,now,"家 · 示例位置",preview=true).apply(context,FrameLayout(context))
                val density=context.resources.displayMetrics.density
                val width=(320*density).toInt();val height=((if(compact)224 else 340)*density).toInt()
                view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));view.layout(0,0,width,height)
                assertEquals("25.4°C",view.findViewById<TextView>(R.id.widget_cabin).text.toString())
                assertEquals("上次车温",view.findViewById<TextView>(R.id.widget_cabin_caption).text.toString())
                val source=view.findViewById<TextView>(R.id.widget_source)
                assertTrue(source.text.contains("车温上报"));assertTrue(source.textSize/density>=11)
                for(id in listOf(R.id.widget_cabin_caption,R.id.widget_cabin,R.id.widget_energy)) {
                    val field=view.findViewById<View>(id); val parent=field.parent as View
                    assertTrue("temperature must fit",field.bottom<=parent.height-parent.paddingBottom)
                }
                for(id in listOf(R.id.widget_prepare,R.id.widget_find,R.id.widget_lock,R.id.widget_guard,R.id.widget_trunk,R.id.widget_port)) {
                    val button=view.findViewById<View>(id);assertTrue(button.height>=44*density);assertTrue(button.width>=44*density)
                }
                val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap))
                save(bitmap,"widget-${if(compact)"4x2" else "4x3"}-${if(open)"open" else "closed"}-synthetic.png");bitmap.recycle()
            }
        }
    }
    @Test fun suppliedPaletteSavesExactSwatchesWithoutAColorPickerDialog() {
        var saved:VehicleAppearance?=null
        compose.setContent { AssistantTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            AppearanceEditor(VehicleAppearance(plateEnabled=true,plateText="DEMO 123"),"示例车辆",emptyMap(),{}) { value,_ -> saved=value }
        } } }
        compose.onNodeWithTag("plate_bg_000000").performScrollTo().performClick()
        compose.onNodeWithTag("plate_fg_EC7700").performScrollTo().performClick()
        compose.onNodeWithText("自定义色值（可选）").assertDoesNotExist()
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { save(it,"plate-palette-synthetic.png");it.recycle() }
        compose.onNodeWithTag("appearance_save").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("#000000",saved!!.plateBackground);assertEquals("#EC7700",saved!!.plateForeground) }
    }
    private fun save(bitmap:Bitmap,name:String) {
        val directory=File(context.getExternalFilesDir(null),"qa-card-060").apply{mkdirs()}
        File(directory,name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
    }
}
