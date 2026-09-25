package com.dante.zeekrcapabilitylab.mirror

import android.content.BroadcastReceiver
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.dante.zeekrcapabilitylab.BuildConfig
import com.dante.zeekrcapabilitylab.event.EventLogger
import com.dante.zeekrcapabilitylab.product.SettingsStore
import com.dante.zeekrcapabilitylab.service.CameraRecordingService
import com.dante.zeekrcapabilitylab.service.recorder.CaptureCleanupRuntime
import com.dante.zeekrcapabilitylab.service.recorder.RecordingSourceRole
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.lang.ref.WeakReference

/** Real Camera2 -> GL -> TextureView on a disposable emulator; no car or phone is touched. */
class MirrorPreviewWakeAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).readBytes()
        }
    }
    private fun await(message: String, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 15_000
        while (!condition() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(100)
        assertTrue("$message; evidence=${MirrorPreviewRuntime.evidence}; controls=${FloatingMirrorService.state.value}", condition())
    }
    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
    private fun service(): Any = StandaloneMirrorService::class.java.getDeclaredField("instance").apply { isAccessible = true }.get(null)!!
    private fun controller(): MirrorPreviewController = (field(MirrorPreviewRuntime, "controller") as WeakReference<*>).get() as MirrorPreviewController

    private fun withPreview(attachDisplay: Boolean = true, test: () -> Unit) {
        assumeTrue("Disposable emulator trial only", BuildConfig.MIRROR_RETURN_ENABLED && android.os.Build.HARDWARE == "ranchu")
        val cameras = context.getSystemService(CameraManager::class.java).cameraIdList
        assumeTrue("Requires the emulator's emulated camera", cameras.isNotEmpty())
        shell("input keyevent 224"); shell("wm dismiss-keyguard")
        val activity = instrumentation.startActivitySync(Intent(context, MirrorSleepTestHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MirrorSleepTestHostActivity
        try {
            main {
                val settings = SettingsStore.get(context)
                settings.setMirrorPreviewEnabled(true); settings.setMirrorRearLane(1)
                settings.setCameraMapping(RecordingSourceRole.CABIN, cameras.first())
                MirrorReturnSettings(context).save(MirrorReturnMode.LOGO)
                MirrorPresentation(context).videoVisible = true
                if (attachDisplay) activity.setContentView(MirrorHomeHost(activity))
                context.startForegroundService(Intent(context, FloatingMirrorService::class.java).setAction("openavm.mirror.FOLLOW"))
            }
            await("controls active") { FloatingMirrorService.active() }
            main { assertTrue(FloatingMirrorService.selectPreviewSource(RecordingSourceRole.CABIN)) }
            await("preview producer opened") { StandaloneMirrorService.isRunning() && field(service(), "producer")?.let {
                field(it, "device") != null
            } == true }
            if (attachDisplay) await("actual live preview frames") { MirrorPreviewRuntime.evidence.reason == "LIVE" && MirrorPreviewRuntime.evidence.frames > 3 }
            test()
            assertFalse("Preview never starts recording", CameraRecordingService.isRunning())
        } finally {
            main { FloatingMirrorService.close(); activity.finish() }
            shell("input keyevent 224"); shell("wm dismiss-keyguard")
            await("camera and GL cleanup acknowledged") { !StandaloneMirrorService.isRunning() && MirrorGlPreview.isIdle() && CaptureCleanupRuntime.pendingOwners.value == 0 }
        }
    }

    @Test fun openedCameraWithoutFramesIsNotACompletedPreviewStart() = withPreview(attachDisplay = false) {
        SystemClock.sleep(500)
        assertFalse("Opening CameraDevice does not prove a visible picture", StandaloneMirrorService.isPreviewing())
        assertTrue("Still preparing until a displayed frame arrives", FloatingMirrorService.state.value.busy)
    }

    @Test fun lateScreenOffCannotStopAnAlreadyAwakePreviewProducer() = withPreview {
        val before = MirrorPreviewRuntime.evidence.frames
        main { (field(service(), "screen") as BroadcastReceiver).onReceive(context, Intent(Intent.ACTION_SCREEN_OFF)) }
        SystemClock.sleep(750)
        assertTrue("Fresh screen state is ON; a delayed OFF broadcast must not close the camera", StandaloneMirrorService.isRunning())
        await("frames continue after stale power hint") { MirrorPreviewRuntime.evidence.frames > before + 3 && MirrorPreviewRuntime.evidence.reason == "LIVE" }
    }

    @Test fun lateScreenOffCannotLatchDisplayHiddenUntilAnotherScreenOn() = withPreview {
        val before = MirrorPreviewRuntime.evidence.frames
        main { (field(controller(), "displayReceiver") as BroadcastReceiver).onReceive(context, Intent(Intent.ACTION_SCREEN_OFF)) }
        SystemClock.sleep(750)
        assertNotEquals("An obsolete event cannot override the current display snapshot", "DISPLAY_HIDDEN", MirrorPreviewRuntime.evidence.reason)
        await("new frames without another SCREEN_ON broadcast") { MirrorPreviewRuntime.evidence.frames > before + 3 && MirrorPreviewRuntime.evidence.reason == "LIVE" }
    }

    @Test fun fullWindowActuallyRestoresFramesAfterTwoSleepCyclesWithoutRecording() = withPreview {
        main { MirrorReturnSettings(context).save(MirrorReturnMode.PREVIEW); FloatingMirrorService.returnSettingsChanged() }
        repeat(2) {
            val since = SystemClock.elapsedRealtime()
            shell("input keyevent 223")
            await("real screen off releases camera and GL") { !StandaloneMirrorService.isRunning() && MirrorGlPreview.isIdle() }
            assertFalse(CameraRecordingService.isRunning())
            shell("input keyevent 224"); shell("wm dismiss-keyguard")
            await("auto return has actual visible frames") { StandaloneMirrorService.isPreviewing() &&
                MirrorPreviewRuntime.evidence.reason == "LIVE" && !FloatingMirrorService.state.value.busy }
            val events = EventLogger.events.value.filter { it.elapsedRealtimeMs >= since }
            assertEquals("one request per screen cycle", 1, events.count { it.eventName == "MIRROR_SLEEP_AUTO_REQUESTED" })
            val firstFrame = events.first { it.eventName == "MIRROR_PREVIEW_FIRST_DISPLAYED_FRAME" }
            val started = events.first { it.eventName == "MIRROR_SLEEP_AUTO_STARTED" }
            assertTrue("Ready is announced after a picture, not merely an open camera", started.elapsedRealtimeMs >= firstFrame.elapsedRealtimeMs)
            assertFalse(CameraRecordingService.isRunning())
        }
    }
}
