package com.dante.zeekrcheck

import android.graphics.Bitmap
import android.widget.FrameLayout
import android.view.View
import androidx.compose.foundation.layout.*
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

/** Isolated synthetic profiles only. Does not open a cloud client or write real user preferences. */
class AppearanceUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun encryptedAppearanceSurvivesReloadAndSeparatesTwoCars() {
        val name="appearance-instrumented"
        val disk=SecureRecordStore(context,name,SealedConfig.Purpose.APPEARANCE) { AppearanceData.parse(it) }
        disk.clear()
        try {
            val store=AppearanceStore(context,name)
            val a="a".repeat(64);val b="b".repeat(64)
            store.bind(991,a,"Synthetic A");store.bind(992,b,"Synthetic B")
            store.save(a,VehicleAppearance(bodyColor="#356EAD",plateEnabled=true,plateText="DEMO 123"),"Synthetic A",mapOf(991 to true))
            store.save(b,VehicleAppearance(bodyColor="#B83F3F"),"Synthetic B",mapOf(992 to false))
            val restored=AppearanceStore(context,name).state.value
            assertEquals("#356EAD",restored.vehicles[a]!!.bodyColor)
            assertEquals("#B83F3F",restored.vehicles[b]!!.bodyColor)
            assertTrue(restored.widgets[991]!!.showPlateText);assertFalse(restored.widgets[992]!!.showPlateText)
            assertFalse(File(context.noBackupFilesDir,"$name.sealed").readBytes().toString(Charsets.UTF_8).contains("DEMO 123"))
        } finally { disk.clear() }
    }
    @Test fun hiddenPlatePixelsContainNoTextAndAllOtherPixelsStayIdentical() {
        val a=VehicleAppearance(bodyColor="#356EAD",plateEnabled=true,plateText="DEMO 123")
        val shown=AppearanceRenderer.render(context,a,1000,true)
        val hidden=AppearanceRenderer.render(context,a,1000,false)
        val another=AppearanceRenderer.render(context,a.copy(plateText="TEST-456"),1000,false)
        assertTrue(hidden.sameAs(another));assertFalse(shown.sameAs(hidden))
        var changed=0
        for(y in 0 until shown.height)for(x in 0 until shown.width)if(shown.getPixel(x,y)!=hidden.getPixel(x,y)) {
            changed++; assertTrue("Plate escaped its mounting region",x in 69..154 && y in 324..367)
        }
        assertTrue(changed>30)
        save(shown,"7x-blue-plate-synthetic.png");save(hidden,"7x-blue-private-synthetic.png")
        val cached=AppearanceRenderer.load(context,a,520,false)
        assertSame(cached,AppearanceRenderer.load(context,a.copy(plateText="TEST-456"),520,false))
    }
    @Test fun editorChangesAreDraftOnlyAndCancelDoesNotSave() {
        var saved:VehicleAppearance?=null; var closed=false
        val original=VehicleAppearance()
        compose.setContent { AssistantTheme { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            AppearanceEditor(original,"示例 7X",emptyMap(),{closed=true}){a,_->saved=a}
        } } }
        compose.onNodeWithTag("paint_蓝").performScrollTo().performClick()
        compose.onNodeWithTag("appearance_cancel").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(closed);assertNull(saved);assertEquals("#F2F3EF",original.bodyColor) }
    }
    @Test fun editorSavesLastColorAndRejectsLongPlateWithoutTruncation() {
        var saved:VehicleAppearance?=null
        compose.setContent { AssistantTheme { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            AppearanceEditor(VehicleAppearance(),"示例 7X",mapOf(991 to false),{}){a,_->saved=a}
        } } }
        compose.onNodeWithTag("paint_蓝").performScrollTo().performClick()
        compose.onNodeWithTag("paint_红").performScrollTo().performClick()
        compose.onNodeWithTag("plate_text").performScrollTo().performTextReplacement("ABCDEFGHIJKLM")
        compose.onNodeWithTag("appearance_save").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("plate_text").performScrollTo().assertTextContains("ABCDEFGHIJKLM").performTextReplacement("DEMO 123")
        compose.onNodeWithTag("appearance_save").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("#B83F3F",saved!!.bodyColor);assertEquals("DEMO 123",saved!!.plateText) }
    }
    @Test fun homeSettingsReuseExistingCenterWithoutVehicleCommands() {
        val initial=CarLocation(-35.0,138.0,null,"Synthetic home",true)
        var saved:CarLocation?=null
        compose.setContent { AssistantTheme { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            HomeLocationCard(AssistantState(home=initial,homeRadius=250)) { place,radius -> saved=place;assertEquals(250,radius) }
        } } }
        compose.onNodeWithTag("saved_home").assertTextEquals("Synthetic home")
        compose.onNodeWithTag("save_home").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(initial,saved) }
        compose.onNodeWithTag("home_saved").assertExists()
    }
    @Test fun actualWidgetsKeepSixControlsAndPreviewHasNoPendingActions() {
        val art=AppearanceRenderer.render(context,VehicleAppearance(bodyColor="#356EAD"),520,false)
        compose.runOnUiThread {
            for((compact,height) in listOf(true to 224,false to 340)) {
                val views=VehicleWidgetProvider.views(context,VehicleOverview(nickname="示例 7X"),compact,preview=true)
                views.setImageViewBitmap(R.id.widget_car,art)
                val view=views.apply(context,FrameLayout(context))
                val density=context.resources.displayMetrics.density;val w=(320*density).toInt();val h=(height*density).toInt()
                view.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));view.layout(0,0,w,h)
                for(id in listOf(R.id.widget_prepare,R.id.widget_find,R.id.widget_lock,R.id.widget_guard,R.id.widget_trunk,R.id.widget_port)) {
                    val button=view.findViewById<View>(id);assertTrue(button.height>=44*density-1);assertFalse(button.hasOnClickListeners())
                }
                val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);view.draw(android.graphics.Canvas(bitmap));save(bitmap,"widget-${if(compact)"4x2" else "4x3"}-appearance-synthetic.png")
            }
        }
    }
    private fun save(bitmap:Bitmap,name:String) {
        val dir=File(context.filesDir,"qa-050-synthetic").apply{mkdirs()}
        File(dir,name).outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
    }
}
