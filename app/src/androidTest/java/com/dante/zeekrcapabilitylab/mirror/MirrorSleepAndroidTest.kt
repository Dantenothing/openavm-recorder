package com.dante.zeekrcapabilitylab.mirror

import android.content.Intent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.dante.zeekrcapabilitylab.BuildConfig
import com.dante.zeekrcapabilitylab.diagnostic.AwayJournal
import com.dante.zeekrcapabilitylab.event.EventLogger
import com.dante.zeekrcapabilitylab.product.SettingsStore
import com.dante.zeekrcapabilitylab.service.CameraRecordingService
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Explicitly run on a disposable emulator. Never targets a vehicle or the connected phone. */
class MirrorSleepAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).readBytes()
        }
    }
    private fun await(message: String, timeoutMs: Long = 12_000, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeoutMs
        while (!condition() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(100)
        assertTrue(message, condition())
    }
    private fun host(): MirrorSleepTestHostActivity {
        assumeTrue("Only the opted-in emulator trial", BuildConfig.MIRROR_RETURN_ENABLED &&
            (android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.HARDWARE == "ranchu"))
        shell("input keyevent 224"); shell("wm dismiss-keyguard")
        return instrumentation.startActivitySync(Intent(context, MirrorSleepTestHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MirrorSleepTestHostActivity
    }

    @Test fun controlsSurviveScreenOffAndLateOpenCannotReopenCapture() {
        val activity = host()
        try {
            main {
                SettingsStore.get(context).setMirrorPreviewEnabled(true)
                MirrorReturnSettings(context).save(MirrorReturnMode.LOGO)
                MirrorPresentation(context).videoVisible = false
                context.startForegroundService(Intent(context, FloatingMirrorService::class.java).setAction("openavm.mirror.FOLLOW"))
            }
            await("controls started") { FloatingMirrorService.active() }
            assertFalse(StandaloneMirrorService.isRunning()); assertFalse(CameraRecordingService.isRunning())
            val since = SystemClock.elapsedRealtime()
            shell("input keyevent 223")
            await("release and attached entry observed while dark") { EventLogger.events.value.any {
                it.elapsedRealtimeMs >= since && it.eventName == "MIRROR_SLEEP_CAPTURE_RELEASED" && it.payload["screenUsable"] == "false" && it.payload["overlayAttached"] == "true"
            } }
            shell("input keyevent 224"); shell("wm dismiss-keyguard")
            await("return observed") { EventLogger.events.value.any { it.elapsedRealtimeMs >= since && it.eventName == "MIRROR_SLEEP_RETURN_OBSERVED" } }
            main { context.startForegroundService(Intent(context, FloatingMirrorService::class.java).setAction("openavm.mirror.OPEN")) }
            instrumentation.waitForIdleSync()
            assertTrue(FloatingMirrorService.active())
            assertFalse("screen-on / OPEN must not start preview", StandaloneMirrorService.isRunning())
            assertFalse("screen-on must not record", CameraRecordingService.isRunning())
            val report = AwayJournal.report()
            assertEquals("SAME_PROCESS_AND_WINDOW_RETURNED_CAPTURE_RELEASED", report["mirrorSleep"]!!.jsonObject["classification"]!!.jsonPrimitive.content)
            File(context.getExternalFilesDir(null), "sleep-emulator-report.json").writeText(report.toString())
            screenshot("sleep-paused.png")
            main { FloatingMirrorService.close() }
            await("manual close is terminal") { !FloatingMirrorService.active() && !FloatingMirrorService.visible() }
            shell("input keyevent 223"); shell("input keyevent 224"); shell("wm dismiss-keyguard")
            instrumentation.waitForIdleSync()
            assertFalse(FloatingMirrorService.active())
        } finally {
            main { FloatingMirrorService.close(); activity.finish() }
            shell("input keyevent 224"); shell("wm dismiss-keyguard")
        }
    }

    @Test fun disablingTrialPreservesTheOriginalScreenOffExit() {
        val activity = host()
        try {
            main {
                SettingsStore.get(context).setMirrorPreviewEnabled(true)
                MirrorReturnSettings(context).save(MirrorReturnMode.OFF)
                MirrorPresentation(context).videoVisible = false
                context.startForegroundService(Intent(context, FloatingMirrorService::class.java).setAction("openavm.mirror.FOLLOW"))
            }
            await("controls started") { FloatingMirrorService.active() }
            shell("input keyevent 223")
            await("feature disabled exits on screen off") { !FloatingMirrorService.active() }
        } finally {
            main { FloatingMirrorService.close(); MirrorReturnSettings(context).save(MirrorReturnMode.LOGO); activity.finish() }
            shell("input keyevent 224"); shell("wm dismiss-keyguard")
        }
    }
    @Test fun optedInFullWindowDispatchesPreviewOnlyOnceThenReportsUnavailableCamera() = automatic(MirrorReturnMode.PREVIEW)
    @Test fun optedInRecordingDispatchesRecordOnlyOnceThenReportsUnavailableCamera() = automatic(MirrorReturnMode.RECORD)

    /** Emulator has no cameras: exercises real dispatch/failure fencing, not successful vehicle recording. */
    private fun automatic(mode: MirrorReturnMode) {
        val activity = host()
        assumeTrue("Disposable emulator with cameras disabled", context.getSystemService(android.hardware.camera2.CameraManager::class.java).cameraIdList.isEmpty())
        try {
            main {
                SettingsStore.get(context).setMirrorPreviewEnabled(true)
                MirrorReturnSettings(context).save(mode)
                MirrorPresentation(context).videoVisible = false
                context.startForegroundService(Intent(context, FloatingMirrorService::class.java).setAction("openavm.mirror.FOLLOW"))
            }
            await("controls started") { FloatingMirrorService.active() }
            val since = SystemClock.elapsedRealtime()
            shell("input keyevent 223")
            await("dark entry attached") { EventLogger.events.value.any { it.elapsedRealtimeMs >= since &&
                it.eventName == "MIRROR_SLEEP_CAPTURE_RELEASED" && it.payload["screenUsable"] == "false" && it.payload["overlayAttached"] == "true" } }
            shell("input keyevent 224"); shell("wm dismiss-keyguard")
            await("automatic request") { EventLogger.events.value.any { it.elapsedRealtimeMs >= since && it.eventName == "MIRROR_SLEEP_AUTO_REQUESTED" } }
            await("metadata readiness is visible before any camera service") { EventLogger.events.value.any { it.elapsedRealtimeMs >= since && it.eventName == "MIRROR_SLEEP_PROFILE_WAITING" } }
            assertFalse(StandaloneMirrorService.isRunning()); assertFalse(CameraRecordingService.isRunning())
            await("missing camera reported after bounded metadata wait", 22_000) { EventLogger.events.value.any { it.elapsedRealtimeMs >= since && it.eventName == "MIRROR_SLEEP_AUTO_FAILED" } }
            await("camera service stopped after failure") { !StandaloneMirrorService.isRunning() && !CameraRecordingService.isRunning() }
            main { context.startForegroundService(Intent(context, FloatingMirrorService::class.java).setAction("openavm.mirror.FOLLOW")) }
            SystemClock.sleep(2_000)
            val events = EventLogger.events.value.filter { it.elapsedRealtimeMs >= since }
            assertEquals(1, events.count { it.eventName == "MIRROR_SLEEP_AUTO_REQUESTED" })
            val requests = events.filter { it.eventName == "RECORDER_MIRROR_HANDOFF_REQUESTED" }
            assertEquals(1, requests.size)
            assertEquals(if (mode == MirrorReturnMode.RECORD) "RECORD" else "PREVIEW", requests.single().payload["target"])
            assertFalse(CameraRecordingService.isRunning())
            val report = AwayJournal.report()
            assertEquals("a late power hint must not replace the observed sleep cycle",
                "SAME_PROCESS_AND_WINDOW_RETURNED_CAPTURE_RELEASED", report["mirrorSleep"]!!.jsonObject["classification"]!!.jsonPrimitive.content)
            File(context.getExternalFilesDir(null), "sleep-auto-${mode.name.lowercase()}-report.json").writeText(report.toString())
        } finally {
            main { FloatingMirrorService.close(); MirrorReturnSettings(context).save(MirrorReturnMode.LOGO); activity.finish() }
            shell("input keyevent 224"); shell("wm dismiss-keyguard")
        }
    }

    @Test fun changingSettingsDuringSleepPreventsThePreviouslyArmedRecording() {
        val activity = host()
        try {
            main {
                SettingsStore.get(context).setMirrorPreviewEnabled(true)
                MirrorReturnSettings(context).save(MirrorReturnMode.RECORD)
                MirrorPresentation(context).videoVisible = false
                context.startForegroundService(Intent(context, FloatingMirrorService::class.java).setAction("openavm.mirror.FOLLOW"))
            }
            await("controls started") { FloatingMirrorService.active() }
            val since = SystemClock.elapsedRealtime()
            shell("input keyevent 223")
            await("sleep observed") { EventLogger.events.value.any { it.elapsedRealtimeMs >= since && it.eventName == "MIRROR_SLEEP_SUSPENDED" } }
            main { MirrorReturnSettings(context).save(MirrorReturnMode.LOGO); FloatingMirrorService.returnSettingsChanged() }
            shell("input keyevent 224"); shell("wm dismiss-keyguard")
            SystemClock.sleep(2_500)
            assertFalse(EventLogger.events.value.any { it.elapsedRealtimeMs >= since && it.eventName == "MIRROR_SLEEP_AUTO_REQUESTED" })
            assertTrue(FloatingMirrorService.active()); assertFalse(CameraRecordingService.isRunning()); assertFalse(StandaloneMirrorService.isRunning())
        } finally { main { FloatingMirrorService.close(); activity.finish() }; shell("input keyevent 224") }
    }

    @Test fun closingDuringWakeMetadataWaitCancelsItWithoutOpeningACamera() = cancelProfileWait(close = true)
    @Test fun disablingAutoRecordDuringWakeMetadataWaitCancelsItWithoutRecording() = cancelProfileWait(close = false)

    private fun cancelProfileWait(close: Boolean) {
        val activity = host()
        assumeTrue("Disposable emulator with cameras disabled", context.getSystemService(android.hardware.camera2.CameraManager::class.java).cameraIdList.isEmpty())
        try {
            main {
                SettingsStore.get(context).setMirrorPreviewEnabled(true)
                MirrorReturnSettings(context).save(MirrorReturnMode.RECORD)
                MirrorPresentation(context).videoVisible = false
                context.startForegroundService(Intent(context, FloatingMirrorService::class.java).setAction("openavm.mirror.FOLLOW"))
            }
            await("controls started") { FloatingMirrorService.active() }
            val since = SystemClock.elapsedRealtime()
            shell("input keyevent 223")
            await("observed actual sleep") { EventLogger.events.value.any { it.elapsedRealtimeMs >= since && it.eventName == "MIRROR_SLEEP_SUSPENDED" } }
            shell("input keyevent 224"); shell("wm dismiss-keyguard")
            await("wake metadata wait started") { EventLogger.events.value.any { it.elapsedRealtimeMs >= since && it.eventName == "MIRROR_SLEEP_PROFILE_WAITING" } }
            main {
                if (close) FloatingMirrorService.close()
                else { MirrorReturnSettings(context).save(MirrorReturnMode.LOGO); FloatingMirrorService.returnSettingsChanged() }
            }
            SystemClock.sleep(500)
            val probes = EventLogger.events.value.count { it.elapsedRealtimeMs >= since && it.eventName == "MIRROR_SLEEP_PROFILE_WAITING" }
            SystemClock.sleep(2_000)
            assertEquals(probes, EventLogger.events.value.count { it.elapsedRealtimeMs >= since && it.eventName == "MIRROR_SLEEP_PROFILE_WAITING" })
            assertFalse(StandaloneMirrorService.isRunning()); assertFalse(CameraRecordingService.isRunning())
            assertFalse(EventLogger.events.value.any { it.elapsedRealtimeMs >= since && it.eventName == "RECORDER_START" })
            assertEquals(!close, FloatingMirrorService.active())
        } finally {
            main { FloatingMirrorService.close(); MirrorReturnSettings(context).save(MirrorReturnMode.LOGO); activity.finish() }
            shell("input keyevent 224"); shell("wm dismiss-keyguard")
        }
    }
    private fun screenshot(name: String) {
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(context.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
