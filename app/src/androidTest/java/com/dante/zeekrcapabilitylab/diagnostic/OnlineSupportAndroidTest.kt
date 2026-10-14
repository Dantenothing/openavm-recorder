package com.dante.zeekrcapabilitylab.diagnostic

import android.os.Build
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.dante.zeekrcapabilitylab.BuildConfig
import com.dante.zeekrcapabilitylab.event.EventLogger
import com.dante.zeekrcapabilitylab.preflight.PreflightActivity
import com.dante.zeekrcapabilitylab.preflight.PreflightStore
import com.dante.zeekrcapabilitylab.preflight.cloud.DiagnosticPolicy
import com.dante.zeekrcapabilitylab.preflight.cloud.DiagnosticSettings
import com.dante.zeekrcapabilitylab.preflight.remote.LabUi
import com.dante.zeekrcapabilitylab.preflight.remote.flag
import com.dante.zeekrcapabilitylab.product.AppLanguage
import com.dante.zeekrcapabilitylab.product.AppLanguageMode
import com.dante.zeekrcapabilitylab.service.CameraRecordingService
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.File

/** Disposable emulator only: actual page, Binder service and redacted report serialization. */
class OnlineSupportAndroidTest {
    @get:Rule val compose=createAndroidComposeRule<PreflightActivity>()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun before() {
        assumeTrue(Build.HARDWARE=="ranchu" && BuildConfig.RECORDER_SUPPORT_ENABLED)
        AppLanguage.setMode(context,AppLanguageMode.ENGLISH)
        assertNull("Never reuse a real vehicle's credentials",DiagnosticSettings(context).load())
        assertFalse(CameraRecordingService.isRunning())
    }
    @Test fun freshRoundPreservesOldEvidenceButExcludesItsEventsFromTheNewRound() {
        SupportRound.begin()
        EventLogger.logEvent("DIAGNOSTIC","RECORDER_SUPPORT_OLD",payload=mapOf("reason" to "OLD_ROUND"))
        val first=SupportRound.save(context)
        SystemClock.sleep(5)
        val boundary=SupportRound.begin()
        EventLogger.logEvent("DIAGNOSTIC","RECORDER_GROUP_STOP",payload=mapOf("reason" to "AUXILIARY_NO_WRITTEN_FRAMES", "password" to "DO_NOT_EXPORT"))
        val second=SupportRound.save(context)
        val report=PreflightStore(context).latestExport().first
        val identity=DiagnosticPolicy.identity(report)
        assertEquals(BuildConfig.VERSION_NAME,identity.version)
        assertEquals(first["runId"],PreflightStore(context).read("previous.json")!!["runId"])
        assertEquals(boundary.id,second["round"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        val events=second["round"]!!.jsonObject["events"]!!.jsonArray.toString()
        assertFalse(events.contains("OLD_ROUND"));assertFalse(events.contains("DO_NOT_EXPORT"))
        assertTrue(events.contains("AUXILIARY_NO_WRITTEN_FRAMES"))
        assertTrue(EventLogger.events.value.any {it.eventName=="RECORDER_SUPPORT_OLD"})
        File(context.filesDir,"support-round-test.json").writeBytes(report)
        assertFalse(CameraRecordingService.isRunning())
    }
    @Test fun unpairedPageCannotArmAndPassiveCollectionDoesNotStartTheCamera() {
        compose.onNodeWithText("Online recording diagnostics").assertExists()
        compose.onNodeWithText("开启远程实验会话 · 4 小时").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Start a fresh diagnostic round").performScrollTo().performClick()
        compose.onNodeWithText("Collect current diagnostics").performScrollTo().performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("Snapshot saved. If upload is enabled, it is queued for the private receiver.").fetchSemanticsNodes().isNotEmpty() }
        assertFalse(CameraRecordingService.isRunning())
        assertFalse(LabUi.state.value.flag("armed"))
        assertTrue(ProductDiagnosticRun.idle())
    }
}
