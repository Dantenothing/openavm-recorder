package com.dante.zeekrcapabilitylab.mirror

import android.content.Intent
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.dante.zeekrcapabilitylab.BuildConfig
import com.dante.zeekrcapabilitylab.diagnostic.ProductDiagnosticRun
import com.dante.zeekrcapabilitylab.event.EventLogger
import com.dante.zeekrcapabilitylab.product.ProductRecorderConfigFactory
import com.dante.zeekrcapabilitylab.product.SettingsStore
import com.dante.zeekrcapabilitylab.service.CameraRecordingService
import com.dante.zeekrcapabilitylab.service.recorder.*
import com.dante.zeekrcapabilitylab.sentry.CanaryCameraInterlock
import com.dante.zeekrcapabilitylab.sentry.CanaryCameraOpenAdapter
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Actual production service/TextureView trials. No fake frames, native close ACKs or vehicle access. */
class PreviewOrderAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(label: String, timeout: Long = 20_000, predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (!predicate() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(100)
        assertTrue("$label; ${FloatingMirrorService.state.value}; ${MirrorPreviewRuntime.evidence}", predicate())
    }

    @Test fun closingControlsBeforeQueuedPreviewServiceStartsDoesNotOpenCameraOrCrash() = withHost {
        repeat(5) {
            main { context.startForegroundService(Intent(context, FloatingMirrorService::class.java)
                .setAction("openavm.mirror.FOLLOW")) }
            await("controls active") { FloatingMirrorService.active() }
            val controls = FloatingMirrorService::class.java.getDeclaredField("instance")
                .apply { isAccessible = true }.get(null)!!
            fun field(name: String) = controls.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(controls)
            // Hold only the controls poll. Profile lookup and the real Android service lifecycle
            // still run. One main-thread turn submits a valid FGS start then revokes its token,
            // exactly the window between Stop -> preview restoration and closing the controls.
            main {
                (field("main") as Handler).removeCallbacks(field("tick") as Runnable)
                assertTrue(FloatingMirrorService.selectPreviewSource(RecordingSourceRole.CABIN))
            }
            await("real profile ready for handoff") { field("pendingConfig") != null }
            val since = SystemClock.elapsedRealtime()
            main {
                controls.javaClass.getDeclaredMethod("advance").apply { isAccessible = true }.invoke(controls)
                assertTrue(com.dante.zeekrcapabilitylab.enhancement.CameraWorkCoordinator.state.value.active)
                assertFalse("Android has not delivered the queued service start", StandaloneMirrorService.isRunning())
                FloatingMirrorService.close()
            }
            await("cancelled service destroyed and reservation released") {
                ProductDiagnosticRun.idle() && !com.dante.zeekrcapabilitylab.enhancement.CameraWorkCoordinator.state.value.active
            }
            SystemClock.sleep(500) // Also observe asynchronous system-server lifecycle failure delivery.
            assertFalse(EventLogger.events.value.any {
                it.elapsedRealtimeMs >= since && it.eventName == "MIRROR_PREVIEW_OPEN_REQUESTED"
            })
            assertEquals(0, Thread.getAllStackTraces().keys.count { it.isAlive && it.name == "mirror-only-camera" })
        }
    }

    @Test fun cancelledDiagnosticControlsStartDoesNotOpenCameraOrCrash() = withHost { config ->
        repeat(5) {
            val id = java.util.UUID.randomUUID().toString()
            val since = SystemClock.elapsedRealtime()
            main {
                DiagnosticPreviewOrder.arm(PreviewOrderTrial(id, config.cameraId,
                    PreviewInitializationOrder.CAMERA_FIRST, config.source.sourceRole))
                assertTrue(FloatingMirrorService.openDiagnosticPreview(context, id))
                DiagnosticPreviewOrder.finish(id)
            }
            await("revoked diagnostic start leaves no controls or camera") { !FloatingMirrorService.active() && ProductDiagnosticRun.idle() }
            SystemClock.sleep(500)
            assertFalse(EventLogger.events.value.any {
                it.elapsedRealtimeMs >= since && it.eventName == "MIRROR_PREVIEW_OPEN_REQUESTED"
            })
        }
    }

    @Test fun closingControlsBeforeQueuedRecorderStartsDoesNotRecordOrCrash() = withHost { config ->
        repeat(5) {
            main { context.startForegroundService(Intent(context, FloatingMirrorService::class.java)
                .setAction("openavm.mirror.FOLLOW")) }
            await("controls active") { FloatingMirrorService.active() }
            val controls = FloatingMirrorService::class.java.getDeclaredField("instance")
                .apply { isAccessible = true }.get(null)!!
            fun field(name: String) = controls.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(controls)
            val since = SystemClock.elapsedRealtime()
            main {
                (field("main") as Handler).removeCallbacks(field("tick") as Runnable)
                FloatingMirrorService.startRecording(config)
                controls.javaClass.getDeclaredMethod("advance").apply { isAccessible = true }.invoke(controls)
                val token = field("startingToken") as String
                assertTrue(FloatingMirrorService.admitsRecordingStart(token))
                assertFalse("Android has not delivered the queued recorder start", CameraRecordingService.isRunning())
                FloatingMirrorService.close()
                assertFalse(FloatingMirrorService.admitsRecordingStart(token))
            }
            await("cancelled recorder exits without camera work") { !FloatingMirrorService.active() && ProductDiagnosticRun.idle() }
            SystemClock.sleep(500)
            assertFalse(EventLogger.events.value.any { it.elapsedRealtimeMs >= since && it.eventName == "RECORDER_START" })
        }
    }

    @Test fun terminalCloseRecipesDoNotMistakeLegacyCaptureCallbacksForWrittenFrames() = withHost { original ->
        val config = original.copy(source = original.source.copy(profile =
            com.dante.zeekrcapabilitylab.probe.camera.CameraFormatProfile(
                com.dante.zeekrcapabilitylab.probe.camera.ProfileSize(640, 480), 2_000_000)),
            segmentSeconds = 60, minFreeBytes = 10L shl 30, storagePreference = RecordingStoragePreference.INTERNAL_ONLY)
        for (order in TerminalCloseOrder.entries) {
            com.dante.zeekrcapabilitylab.diagnostic.SupportRound.begin()
            lateinit var run: ProductDiagnosticRun
            main { run = ProductDiagnosticRun(context, config, "PRODUCT_CLOSE_${order.name}") { true }; run.start() }
            try {
                await("unsupported write-counter route fails with real cleanup", 110_000) { !run.active }
                assertEquals(run.view().detail.toString(), "FAIL", run.view().outcome)
                assertEquals("PRODUCT_CHANNEL_START_TIMEOUT", run.view().detail["reason"]?.jsonPrimitive?.content)
                assertEquals(0, run.view().detail["completedRecordings"]?.jsonPrimitive?.int)
                assertEquals("CONFIRMED", run.view().cleanup)
                val events = EventLogger.events.value.filter { it.payload["trialId"] == run.id }
                assertEquals(1, events.count { it.eventName == "MIRROR_PREVIEW_FIRST_DISPLAYED_FRAME" })
                assertEquals(1, events.count { it.eventName == "RECORDER_CLOSE_SETTLED" })
                assertFalse(events.any { it.eventName == "MIRROR_PREVIEW_REQUEST_REISSUED" })
                assertTrue(events.filter { it.eventName == "RECORDER_CLOSE_REQUESTED" }.all { it.payload["effectiveCloseOrder"] == order.name })
                val report = requireNotNull(com.dante.zeekrcapabilitylab.preflight.PreflightRuntime.report)
                val samples = report["round"]!!.jsonObject["samples"]!!.jsonArray
                assertTrue(samples.any { sample -> sample.jsonObject["channels"]!!.jsonArray.any { channel ->
                    val c = channel.jsonObject
                    c["capturedFrames"]!!.jsonPrimitive.long > 0 && c["writtenFrames"]!!.jsonPrimitive.long == 0L
                } })
                File(context.filesDir, "terminal-close-legacy-${order.name}.json").writeText(report.toString())
            } finally { main { run.cancel() }; await("trial ends with actual cleanup", 70_000) { !run.active } }
            await("no live resources") { ProductDiagnosticRun.idle() }
        }
    }

    @Test fun bothOrdersUseOneRealCameraAndTheSameRequestAndReleaseIt() = withHost { config ->
        val facts = mutableListOf<Map<String, String>>()
        for (order in PreviewInitializationOrder.entries) {
            val since = SystemClock.elapsedRealtime()
            lateinit var run: ProductDiagnosticRun
            main { run = ProductDiagnosticRun(context, config, "PRODUCT_PREVIEW_${order.name}") { true }; run.start() }
            try {
                await("real displayed frames before duplicate delivery") { StandaloneMirrorService.isPreviewing() }
                val before = MirrorPreviewRuntime.evidence.frames
                main { context.startForegroundService(Intent(context, FloatingMirrorService::class.java)
                    .setAction("openavm.mirror.OPEN").putExtra("diagnosticPreviewOrder", run.id)) }
                await("duplicate consumed intent cannot close the active trial") { MirrorPreviewRuntime.evidence.frames > before + 5 }
                await("30 seconds of new pictures and final cleanup", 75_000) { !run.active }
                assertEquals(run.view().detail.toString(), "PASS", run.view().outcome)
                assertEquals("CONFIRMED", run.view().cleanup)
                val events = EventLogger.events.value.filter { it.payload["trialId"] == run.id }
                fun index(stage: String) = events.indexOfFirst { it.eventName == "MIRROR_PREVIEW_$stage" }.also { assertTrue("$stage", it >= 0) }
                assertEquals(1, events.count { it.eventName == "MIRROR_PREVIEW_OPEN_REQUESTED" })
                assertEquals(1, events.count { it.eventName == "MIRROR_PREVIEW_REQUEST_ACCEPTED" })
                if (order == PreviewInitializationOrder.SURFACE_FIRST) assertTrue(index("SURFACE_READY") < index("OPEN_REQUESTED"))
                else assertTrue(index("OPENED") < index("SURFACE_READY"))
                assertTrue(index("CONFIGURE_REQUESTED") < index("FIRST_CAPTURE_CALLBACK"))
                assertTrue(index("FIRST_DISPLAYED_FRAME") < index("CLOSE_SETTLED"))
                assertFalse(events.any { it.eventName == "MIRROR_PREVIEW_REQUEST_REISSUED" })
                assertFalse(EventLogger.events.value.any { it.elapsedRealtimeMs >= since && it.eventName == "RECORDER_START" })
                facts += events.single { it.eventName == "MIRROR_PREVIEW_REQUEST_ACCEPTED" }.payload
                    .filterKeys { it !in setOf("trialId", "initializationOrder", "previewOwner") }
                assertEquals("PREVIEW", facts.last()["requestTemplate"])
                assertTrue("Record effective request defaults", facts.last().containsKey("controlMode"))
                val report = File(context.filesDir, "preview-order-${order.name}.json")
                report.writeText(buildJsonObject {
                    put("order", order.name); put("outcome", run.view().outcome)
                    put("cleanup", run.view().cleanup)
                    put("stages", JsonArray(events.map { e -> buildJsonObject {
                        put("name", e.eventName); put("elapsedMs", e.elapsedRealtimeMs - since)
                        put("facts", JsonObject(e.payload.mapValues { JsonPrimitive(it.value) }))
                    } }))
                }.toString())
            } finally { main { run.cancel() }; await("trial ends") { !run.active } }
            await("native owner released") { ProductDiagnosticRun.idle() }
        }
        assertEquals("The order is the only intentional request difference", facts.first(), facts.last())
    }

    @Test fun cancelWhileSurfaceFirstOpenCallbackIsPendingWaitsForRealClose() = withHost { config ->
        val callbacks = CanaryCameraOpenAdapter::class.java.getDeclaredField("callbacks").apply { isAccessible = true }.get(null) as Handler
        val entered = CountDownLatch(1); val resume = CountDownLatch(1)
        lateinit var run: ProductDiagnosticRun
        var created: ProductDiagnosticRun? = null
        try {
            callbacks.post { entered.countDown(); resume.await(15, TimeUnit.SECONDS) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            main { run = ProductDiagnosticRun(context, config, "PRODUCT_PREVIEW_SURFACE_FIRST") { true }; created = run; run.start() }
            await("actual open submitted with its callback still queued") { !CanaryCameraInterlock.normalIdle() }
            main { run.cancel() }
            SystemClock.sleep(400)
            assertTrue("Cancellation is not a close acknowledgement", run.active)
            assertFalse(CanaryCameraInterlock.normalIdle())
            resume.countDown()
            await("late real open is closed and the trial ends") { !run.active }
            assertEquals("CANCELLED", run.view().outcome)
            assertEquals("CONFIRMED", run.view().cleanup)
            val events = EventLogger.events.value.filter { it.payload["trialId"] == run.id }
            assertEquals(1, events.count { it.eventName == "MIRROR_PREVIEW_OPEN_REQUESTED" })
            assertFalse(events.any { it.eventName == "MIRROR_PREVIEW_REQUEST_ACCEPTED" })
        } finally {
            resume.countDown()
            created?.let { current -> main { current.cancel() }; await("cancel cleanup") { !current.active } }
        }
    }

    @Test fun previewComparisonCannotTurnIntoRecording() = withHost { config ->
        lateinit var run: ProductDiagnosticRun
        val since = SystemClock.elapsedRealtime()
        main { run = ProductDiagnosticRun(context, config, "PRODUCT_PREVIEW_SURFACE_FIRST") { true }; run.start() }
        try {
            await("actual preview") { StandaloneMirrorService.isPreviewing() }
            main { FloatingMirrorService.startRecording(config) }
            await("record request fails this comparison and closes it") { !run.active }
            assertEquals("FAIL", run.view().outcome)
            assertEquals("CONFIRMED", run.view().cleanup)
            assertFalse(EventLogger.events.value.any { it.elapsedRealtimeMs >= since && it.eventName == "RECORDER_START" })
        } finally { main { run.cancel() }; await("cleanup") { !run.active } }
    }

    private fun withHost(test: (RecorderConfig) -> Unit) {
        assumeTrue("Explicit disposable-emulator private diagnostic test", Build.HARDWARE == "ranchu" &&
            BuildConfig.RECORDER_SUPPORT_ENABLED && InstrumentationRegistry.getArguments().getString("previewOrderTest") == "true")
        assertTrue("Grant SYSTEM_ALERT_WINDOW on the disposable emulator", android.provider.Settings.canDrawOverlays(context))
        val camera = context.getSystemService(CameraManager::class.java).cameraIdList.first()
        val settings = SettingsStore.get(context)
        val previous = settings.cameraMapping(RecordingSourceRole.CABIN)
        val activity = instrumentation.startActivitySync(Intent(context, MirrorSleepTestHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MirrorSleepTestHostActivity
        try {
            main {
                settings.setMirrorPreviewEnabled(true); settings.setMirrorRearLane(1)
                settings.setCameraMapping(RecordingSourceRole.CABIN, camera)
                MirrorReturnSettings(context).save(MirrorReturnMode.LOGO)
                activity.setContentView(MirrorHomeHost(activity))
            }
            val config = requireNotNull(ProductRecorderConfigFactory.create(context, RecordingSourceRole.CABIN))
                .copy(mirrorPreviewEnabled = true)
            test(config)
        } finally {
            main { FloatingMirrorService.close(); StandaloneMirrorService.stopForHandoff(); activity.finish() }
            await("no live producer, native owner or GL pending") { ProductDiagnosticRun.idle() }
            settings.setCameraMapping(RecordingSourceRole.CABIN, previous)
        }
    }
}
