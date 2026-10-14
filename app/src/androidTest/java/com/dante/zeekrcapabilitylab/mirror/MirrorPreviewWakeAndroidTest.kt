package com.dante.zeekrcapabilitylab.mirror

import android.content.BroadcastReceiver
import android.content.Intent
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.SystemClock
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import androidx.test.platform.app.InstrumentationRegistry
import com.dante.zeekrcapabilitylab.BuildConfig
import com.dante.zeekrcapabilitylab.event.EventLogger
import com.dante.zeekrcapabilitylab.product.SettingsStore
import com.dante.zeekrcapabilitylab.product.ProductRecorderConfigFactory
import com.dante.zeekrcapabilitylab.service.CameraRecordingService
import com.dante.zeekrcapabilitylab.service.recorder.CaptureCleanupRuntime
import com.dante.zeekrcapabilitylab.service.recorder.RecordingMode
import com.dante.zeekrcapabilitylab.service.recorder.RecordingModeChoice
import com.dante.zeekrcapabilitylab.service.recorder.RecordingSourceRole
import com.dante.zeekrcapabilitylab.sentry.CanaryCameraInterlock
import com.dante.zeekrcapabilitylab.sentry.CanaryCameraOpenAdapter
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real Camera2 direct preview and recorder handoffs on a disposable emulator. */
class MirrorPreviewWakeAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).readBytes()
        }
    }
    private fun await(message: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeoutMs
        while (!condition() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(100)
        assertTrue("$message; evidence=${MirrorPreviewRuntime.evidence}; controls=${FloatingMirrorService.state.value}", condition())
    }
    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
    private fun service(): Any = StandaloneMirrorService::class.java.getDeclaredField("instance").apply { isAccessible = true }.get(null)!!
    private fun controller(): MirrorPreviewController = (field(MirrorPreviewRuntime, "controller") as WeakReference<*>).get() as MirrorPreviewController

    @Test fun stalledPreviewRetryWaitsForItsLateCloseInsteadOfCancellingAtCleanupWarning() = withPreview {
        val oldService = service()
        val owner = field(oldService, "owner") as String
        val ticket = field(oldService, "handoffToken") as String
        val worker = field(oldService, "handler") as Handler
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        worker.post { held.countDown(); release.await(15, TimeUnit.SECONDS) }
        assertTrue(held.await(3, TimeUnit.SECONDS))
        val since = SystemClock.elapsedRealtime()
        try {
            main { FloatingMirrorService.previewFailed("PREVIEW_SOURCE_STALLED", ticket) }
            await("one bounded retry requested") {
                EventLogger.events.value.any { it.elapsedRealtimeMs >= since && it.eventName == "MIRROR_PREVIEW_REOPEN_REQUESTED" }
            }
            // Reproduce the field report's three-second close warning while the real
            // old producer is still retained. A warning is not a native close ACK.
            main { com.dante.zeekrcapabilitylab.enhancement.CameraWorkCoordinator.publish(owner,
                "Waiting for old device close", "CLEANUP_UNCONFIRMED") }
            repeat(9) {
                SystemClock.sleep(1_000)
                assertNull("A late close must not cancel this preview-only recovery", FloatingMirrorService.state.value.error)
                assertTrue(FloatingMirrorService.state.value.busy)
                assertSame("No overlapping preview owner", oldService, service())
            }
        } finally { release.countDown() }
        await("late real close ACK permits exactly one new preview", 20_000) {
            StandaloneMirrorService.isPreviewing() && !FloatingMirrorService.state.value.busy &&
                field(service(), "owner") != owner && MirrorPreviewRuntime.evidence.frames > 3
        }
        assertEquals(1, EventLogger.events.value.count { it.elapsedRealtimeMs >= since && it.eventName == "MIRROR_PREVIEW_REOPEN_REQUESTED" })
        assertFalse(EventLogger.events.value.any { it.elapsedRealtimeMs >= since && it.eventName == "RECORDER_START" })
    }

    @Test fun hidingThePreviewStillRevokesARetryWaitingForLateCleanup() = withPreview {
        val oldService = service()
        val owner = field(oldService, "owner") as String
        val ticket = field(oldService, "handoffToken") as String
        val worker = field(oldService, "handler") as Handler
        val held = CountDownLatch(1); val release = CountDownLatch(1)
        worker.post { held.countDown(); release.await(8, TimeUnit.SECONDS) }
        assertTrue(held.await(3, TimeUnit.SECONDS))
        try {
            main { FloatingMirrorService.previewFailed("PREVIEW_SOURCE_STALLED", ticket) }
            await("retry pending") { FloatingMirrorService.state.value.busy }
            main {
                com.dante.zeekrcapabilitylab.enhancement.CameraWorkCoordinator.publish(owner, "Waiting", "CLEANUP_UNCONFIRMED")
                FloatingMirrorService.setVideoVisible(false)
            }
        } finally { release.countDown() }
        await("outgoing preview actually closes") { !StandaloneMirrorService.isRunning() && CaptureCleanupRuntime.pendingOwners.value == 0 }
        SystemClock.sleep(700)
        assertFalse("Late cleanup cannot reopen a hidden preview", StandaloneMirrorService.isRunning())
        assertFalse(CameraRecordingService.isRunning())
    }

    @Test fun freshStandalonePreviewRunsForThreeMinutesWithoutStartingAnEncoder() = withPreview {
        val producer = field(service(), "producer") as MirrorCameraSource
        val owner = producer.state.recordingSessionId!!
        val start = SystemClock.elapsedRealtime()
        var previous = MirrorPreviewRuntime.evidence.frames
        var samples = 0
        while (SystemClock.elapsedRealtime() - start < 180_000) {
            SystemClock.sleep(3_000)
            val current = MirrorPreviewRuntime.evidence
            assertEquals("LIVE", current.reason)
            assertTrue("Only new images count", current.frames > previous)
            assertTrue((current.lastFrameAgeMs ?: Long.MAX_VALUE) <= 2_000)
            assertEquals(owner, producer.state.recordingSessionId)
            assertFalse(CameraRecordingService.isRunning())
            assertEquals(0, CaptureCleanupRuntime.pendingOwners.value)
            previous = current.frames; samples++
        }
        val events = EventLogger.events.value.filter { it.payload["previewOwner"] == owner }
        val stages = listOf("OPEN_REQUESTED", "OPENED", "CONFIGURE_REQUESTED", "CONFIGURED", "REQUEST_ACCEPTED", "FIRST_CAPTURE_CALLBACK")
        val observed = stages.map { stage -> events.single { it.eventName == "MIRROR_PREVIEW_$stage" }.elapsedRealtimeMs }
        assertEquals("Startup evidence follows actual Camera2 callbacks", observed.sorted(), observed)
        assertFalse(events.any { it.eventName == "MIRROR_PREVIEW_FIRST_CAPTURE_FAILED" })
        java.io.File(context.filesDir, "stability-preview-three-minute.json").writeText(
            """{"runMs":${SystemClock.elapsedRealtime() - start},"samples":$samples,"displayedFrames":$previous,"captureFrames":${producer.captureFacts()["captureFrames"]},"startupStages":${stages.size},"backend":"DIRECT_CAMERA_TEXTUREVIEW","vehicleHAL":false}""")
    }

    private fun withPreview(attachDisplay: Boolean = true, waitForOpen: Boolean = true,
                            beforeOpen: () -> Unit = {}, test: (MirrorSleepTestHostActivity) -> Unit) {
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
            beforeOpen()
            main { assertTrue(FloatingMirrorService.selectPreviewSource(RecordingSourceRole.CABIN)) }
            if (waitForOpen) await("preview producer opened") { StandaloneMirrorService.isRunning() && field(service(), "producer")?.let {
                field(it, "device") != null
            } == true }
            if (attachDisplay && waitForOpen) await("actual live preview frames") { MirrorPreviewRuntime.evidence.reason == "LIVE" && MirrorPreviewRuntime.evidence.frames > 3 }
            test(activity)
            assertFalse("Preview never starts recording", CameraRecordingService.isRunning())
        } finally {
            main {
                FloatingMirrorService.close()
                if (CameraRecordingService.isRunning()) CameraRecordingService.stop(context)
                activity.finish()
            }
            shell("input keyevent 224"); shell("wm dismiss-keyguard")
            await("camera and GL cleanup acknowledged") { !StandaloneMirrorService.isRunning() && MirrorGlPreview.isIdle() && CaptureCleanupRuntime.pendingOwners.value == 0 }
        }
    }

    @Test fun failedRecordingStartPreservesRequestedPreviewAndRestoresRealFrames() = withPreview {
        // Trigger a real, deterministic recorder failure on this disposable emulator.
        // No camera/GL callbacks are mocked, and no vehicle recording is attempted.
        assumeTrue("Fixture requires less than 30 GiB free", context.filesDir.usableSpace < 30L * 1024 * 1024 * 1024)
        val config = requireNotNull(ProductRecorderConfigFactory.create(context, RecordingSourceRole.CABIN))
            .copy(minFreeBytes = 30L * 1024 * 1024 * 1024, mirrorPreviewEnabled = true)
        val since = SystemClock.elapsedRealtime()
        main { FloatingMirrorService.startRecording(config) }
        await("recording actually attempted and failed") {
            EventLogger.events.value.any { it.elapsedRealtimeMs >= since && it.eventName == "RECORDER_START" } &&
                !CameraRecordingService.isRunning()
        }
        assertTrue("A recorder failure must not overwrite the user's visible-preview choice",
            MirrorPresentation(context).videoVisible)
        await("preview returns with new displayed frames after native cleanup") {
            StandaloneMirrorService.isPreviewing() && !FloatingMirrorService.state.value.busy &&
                MirrorPreviewRuntime.evidence.reason == "LIVE" && MirrorPreviewRuntime.evidence.frames > 3
        }
        assertEquals("Recovery restores preview only; it must not retry recording", 1,
            EventLogger.events.value.count { it.elapsedRealtimeMs >= since && it.eventName == "RECORDER_START" })
    }

    @Test fun closedPreviewOwnerReleasesLateSurfaceWithoutWaitingForAnExitedWorker() = withPreview {
        val previous = service()
        val producer = field(previous, "producer") as MirrorCameraSource
        val destination = field(previous, "handler") as Handler
        val run = producer.state.recordingSessionId!!
        val generation = producer.state.cameraGeneration
        main { FloatingMirrorService.close() }
        await("old preview worker has actually exited") {
            !StandaloneMirrorService.isRunning() && !destination.looper.thread.isAlive &&
                CaptureCleanupRuntime.pendingOwners.value == 0
        }

        // A presentation callback can outlive its service. This buffer was never
        // given to Camera2, so rejecting the submission must return its ownership.
        val texture = SurfaceTexture(false)
        val surface = Surface(texture)
        val acknowledged = CountDownLatch(1)
        val callbacks = AtomicInteger()
        try {
            producer.replace(surface, run, generation) {
                callbacks.incrementAndGet(); acknowledged.countDown()
            }
            assertTrue("Rejected preview surface must acknowledge release", acknowledged.await(2, TimeUnit.SECONDS))
            assertFalse("Rejected surface wrapper must be closed", surface.isValid)
            assertEquals(1, callbacks.get())
            assertFalse(destination.looper.thread.isAlive)
            assertFalse(StandaloneMirrorService.isRunning())
            assertEquals(0, CaptureCleanupRuntime.pendingOwners.value)
        } finally {
            surface.release(); texture.release()
        }
    }

    @Test fun stopRevokesOutputQueuedBeforeItsNativeCloseTask() = withPreview(attachDisplay = false) { activity ->
        val previous = service()
        val producer = field(previous, "producer") as MirrorCameraSource
        val destination = field(previous, "handler") as Handler
        lateinit var view: TextureView
        main {
            view = TextureView(activity)
            activity.setContentView(FrameLayout(activity).apply {
                addView(view, FrameLayout.LayoutParams(640, 480))
            })
        }
        await("real candidate consumer available") { view.isAvailable }
        val surface = Surface(requireNotNull(view.surfaceTexture))
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val inspected = CountDownLatch(1)
        val acknowledged = CountDownLatch(1)
        val callbacks = AtomicInteger()
        var submittedAfterStop = false
        var returnedBeforeCloseTask = false
        try {
            assertTrue(destination.post { entered.countDown(); check(resume.await(8, TimeUnit.SECONDS)) })
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            producer.replace(surface, producer.state.recordingSessionId!!, producer.state.cameraGeneration) {
                callbacks.incrementAndGet(); acknowledged.countDown()
            }
            // This inspection sits between already-queued output admission and the
            // later native close. Stop must revoke authority immediately, even if
            // the owner's worker cannot process that close yet.
            assertTrue(destination.post {
                submittedAfterStop = field(producer, "surface") === surface
                returnedBeforeCloseTask = acknowledged.count == 0L && !surface.isValid
                inspected.countDown()
            })
            main { FloatingMirrorService.close() }
            await("Stop revoked the preview while its worker is held") { field(previous, "stopping") == true }
            assertFalse("Existing native device remains owned until its real close", CanaryCameraInterlock.normalIdle())
            resume.countDown()
            assertTrue(inspected.await(3, TimeUnit.SECONDS))
            await("actual camera close and old worker exit") {
                !StandaloneMirrorService.isRunning() && !destination.looper.thread.isAlive &&
                    CaptureCleanupRuntime.pendingOwners.value == 0 && CanaryCameraInterlock.normalIdle()
            }
            assertFalse("Stop must prevent an older queued output from being submitted to Camera2", submittedAfterStop)
            assertTrue("Never-submitted output is returned without waiting behind native close", returnedBeforeCloseTask)
            assertEquals("Ownership is returned exactly once", 1, callbacks.get())
        } finally { resume.countDown(); surface.release() }
    }

    @Test fun leavingAndReenteringHomeRetainsPreviewAndResumesWithNewFrames() = withPreview { activity ->
        val initialController = controller()
        val initialProducer = field(service(), "producer")!!
        val initialDevice = field(initialProducer, "device")
        val initialTexture = field(initialController, "texture") as SurfaceTexture
        repeat(20) {
            main { activity.setContentView(FrameLayout(activity)) }
            await("foreground page without a preview stops requesting frames") {
                MirrorPreviewRuntime.evidence.reason == "HIDDEN" && field(initialProducer, "enabled") == false
            }
            assertFalse("A page change must retain the camera consumer", initialTexture.isReleased)
            val before = MirrorPreviewRuntime.evidence.frames
            main { activity.setContentView(MirrorHomeHost(activity)) }
            await("returning home receives new frames, not the retained last image") {
                MirrorPreviewRuntime.evidence.reason == "LIVE" && MirrorPreviewRuntime.evidence.frames > before + 3
            }
            assertSame(initialController, controller())
            assertSame(initialDevice, field(initialProducer, "device"))
            assertSame(initialTexture, field(controller(), "texture"))
        }
        main { FloatingMirrorService.close() }
        await("final close releases the retained consumer and the camera") {
            initialController.cleanupConfirmed() && initialTexture.isReleased &&
                !StandaloneMirrorService.isRunning() && CanaryCameraInterlock.normalIdle()
        }
    }

    @Test fun stopBeforeTheFirstOpenCallbackOnlyClosesTheLateDevice() {
        val callbacks = field(CanaryCameraOpenAdapter, "callbacks") as Handler
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        try {
            withPreview(waitForOpen = false, beforeOpen = {
                assertTrue(callbacks.post { entered.countDown(); resume.await(10, TimeUnit.SECONDS) })
                assertTrue(entered.await(1, TimeUnit.SECONDS))
            }) {
                await("real native open submitted but callback held") {
                    StandaloneMirrorService.isRunning() && field(service(), "producer")?.let {
                        field(it, "opening") == true && !CanaryCameraInterlock.normalIdle()
                    } == true
                }
                val previous = service()
                val producer = field(previous, "producer") as MirrorCameraSource
                val worker = field(previous, "worker") as Thread
                main { FloatingMirrorService.close() }
                await("Stop latches without pretending the pending open was released") { field(previous, "stopping") == true }
                assertTrue(worker.isAlive)
                assertFalse(CanaryCameraInterlock.normalIdle())
                assertNull(field(producer, "surface"))
                resume.countDown()
                await("late real device is closed, worker exits and its reservation is returned") {
                    !StandaloneMirrorService.isRunning() && !worker.isAlive && CanaryCameraInterlock.normalIdle() &&
                        CaptureCleanupRuntime.pendingOwners.value == 0
                }
                assertNull("A late open after Stop cannot create a presentation", field(previous, "mirror"))
                assertEquals(0L, producer.captureFacts().getValue("captureFrames").toLong())
            }
        } finally { resume.countDown() }
    }

    @Test fun twentyFreshPreviewStartsLeaveNoRetiredCameraWorkersOrConsumers() {
        repeat(20) {
            lateinit var previous: MirrorPreviewController
            lateinit var texture: SurfaceTexture
            lateinit var worker: Thread
            withPreview {
                previous = controller()
                texture = field(previous, "texture") as SurfaceTexture
                worker = field(service(), "worker") as Thread
                assertTrue(worker.isAlive)
                assertTrue(StandaloneMirrorService.isPreviewing())
            }
            await("each closed preview returns its real resources before the next start") {
                previous.cleanupConfirmed() && texture.isReleased && !worker.isAlive && CanaryCameraInterlock.normalIdle()
            }
            assertEquals("Repeated entry must not accumulate camera workers", 0,
                Thread.getAllStackTraces().keys.count { it.isAlive && it.name == "mirror-only-camera" })
        }
    }

    @Test fun recordingModeSwitchWaitsForOtherCameraCleanup() = modeSwitchWithDelayedAuxiliaryCleanup(cancel = false)

    @Test fun stopDuringModeSwitchRevokesContinuationBeforeLateAuxiliaryCleanup() = modeSwitchWithDelayedAuxiliaryCleanup(cancel = true)

    @Test fun modeSwitchDeadlineEndsServiceWithoutReleasingAnotherCamerasFence() =
        modeSwitchWithDelayedAuxiliaryCleanup(cancel = false, expire = true)

    @Test fun closingPreviewOwnerReleasesASurfaceAlreadyQueuedBehindStopExactlyOnce() = withPreview {
        val previous = service()
        val producer = field(previous, "producer") as MirrorCameraSource
        val destination = field(previous, "handler") as Handler
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val acknowledged = CountDownLatch(1)
        val callbacks = AtomicInteger()
        val texture = SurfaceTexture(false)
        val surface = Surface(texture)
        try {
            assertTrue(destination.post { entered.countDown(); check(resume.await(6, TimeUnit.SECONDS)) })
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            main { FloatingMirrorService.close() }
            await("Stop has revoked the source while native work is queued") { field(previous, "stopping") == true }
            producer.replace(surface, producer.state.recordingSessionId!!, producer.state.cameraGeneration) {
                callbacks.incrementAndGet(); acknowledged.countDown()
            }
            resume.countDown()
            assertTrue(acknowledged.await(3, TimeUnit.SECONDS))
            await("old preview's actual camera close completes") {
                !StandaloneMirrorService.isRunning() && !destination.looper.thread.isAlive &&
                    CaptureCleanupRuntime.pendingOwners.value == 0
            }
            assertFalse(surface.isValid)
            assertEquals(1, callbacks.get())
        } finally {
            resume.countDown(); surface.release(); texture.release()
        }
    }

    private fun modeSwitchWithDelayedAuxiliaryCleanup(cancel: Boolean, expire: Boolean = false) = withPreview {
        assumeTrue("Production reserve available", context.filesDir.usableSpace > 11L * 1024 * 1024 * 1024)
        val base = requireNotNull(ProductRecorderConfigFactory.create(context, RecordingSourceRole.CABIN))
        val config = base.copy(source = base.source.copy(profile = base.profile.copy(
            size = com.dante.zeekrcapabilitylab.probe.camera.ProfileSize(640, 480), bitrateBps = 2_000_000)),
            minFreeBytes = 10L * 1024 * 1024 * 1024, requestedFrameRate = 15, mirrorPreviewEnabled = true)
        val since = SystemClock.elapsedRealtime()
        val auxiliaryHold = Any()
        try {
            main { FloatingMirrorService.startRecording(config) }
            await("initial real Camera2 recording ready") {
                CameraRecordingService.state.value.videoFlowConfirmed && !FloatingMirrorService.state.value.busy
            }
            SystemClock.sleep(1_000)
            val owner = CameraRecordingService::class.java.getDeclaredField("instance").apply { isAccessible = true }.get(null)!!
            val previous = field(owner, "session")!!
            val previousId = requireNotNull(CameraRecordingService.state.value.recordingSessionId)

            // Fault injection at the existing process-wide release fence only.
            // The primary service, Camera2 device, file writer and disposal are real;
            // this does not claim to exercise a second camera or the vehicle HAL.
            CanaryCameraInterlock.beginCleanup(auxiliaryHold, "delayed-auxiliary-test")
            main { CameraRecordingService.switchMode(RecordingModeChoice(RecordingMode.TIME_LAPSE, 30), previousId) }
            await("primary worker disposal completes independently of auxiliary") { field(previous, "disposalFinished") == true }
            SystemClock.sleep(600)
            main {
                assertSame("New session cannot start while another camera is still closing", previous, field(owner, "session"))
                assertEquals(true, CameraRecordingService.modeSwitchProgress.value?.releasing)
            }
            assertEquals(1, EventLogger.events.value.count { it.elapsedRealtimeMs >= since && it.eventName == "RECORDER_START" })
            if (cancel) {
                main { CameraRecordingService.stop(context) }
                await("manual Stop ends the service while auxiliary still owns its fence") { !CameraRecordingService.isRunning() }
            } else if (expire) {
                await("existing 20-second deadline ends the service", timeoutMs = 25_000) { !CameraRecordingService.isRunning() }
            }
            assertFalse("Cancellation/deadline must not clear another owner's fence",
                CanaryCameraInterlock.cameraIdle("delayed-auxiliary-test"))
            CanaryCameraInterlock.cleanupComplete(auxiliaryHold)
            if (cancel || expire) {
                await("late auxiliary release permits preview only") { StandaloneMirrorService.isPreviewing() }
                assertEquals(1, EventLogger.events.value.count { it.elapsedRealtimeMs >= since && it.eventName == "RECORDER_START" })
            } else {
                await("same explicit switch starts once after all camera releases") {
                    CameraRecordingService.state.value.recordingSessionId != previousId &&
                        CameraRecordingService.state.value.recordingMode == RecordingMode.TIME_LAPSE &&
                        CameraRecordingService.state.value.videoFlowConfirmed && CameraRecordingService.modeSwitchProgress.value == null
                }
                assertEquals(2, EventLogger.events.value.count { it.elapsedRealtimeMs >= since && it.eventName == "RECORDER_START" })
                main { CameraRecordingService.stop(context) }
                await("mode switch recording stops") { !CameraRecordingService.isRunning() }
            }
        } finally {
            CanaryCameraInterlock.cleanupComplete(auxiliaryHold)
            if (CameraRecordingService.isRunning()) {
                main { CameraRecordingService.stop(context) }
                await("test recorder stops") { !CameraRecordingService.isRunning() }
            }
        }
    }

    @Test fun repeatedRecordingStopReturnsToNewLivePreviewFrames() = withPreview {
        assumeTrue("Fixture needs room for the production 10 GiB reserve", context.filesDir.usableSpace > 11L * 1024 * 1024 * 1024)
        val base = requireNotNull(ProductRecorderConfigFactory.create(context, RecordingSourceRole.CABIN))
        val size = com.dante.zeekrcapabilitylab.probe.camera.ProfileSize(640, 480)
        val declared = context.getSystemService(CameraManager::class.java).getCameraCharacteristics(base.cameraId)
            .get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            .getOutputSizes(android.media.MediaRecorder::class.java)
        assumeTrue("Declared emulator recording size", declared.any { it.width == size.width && it.height == size.height })
        val config = base.copy(source = base.source.copy(profile = base.profile.copy(size = size, bitrateBps = 2_000_000)),
            minFreeBytes = 10L * 1024 * 1024 * 1024, mirrorPreviewEnabled = true, requestedFrameRate = 15)
        val cycles = InstrumentationRegistry.getArguments().getString("previewCycles")?.toIntOrNull()?.coerceIn(2, 20) ?: 2
        val connectionSince = SystemClock.elapsedRealtime()
        val device = field(requireNotNull(field(service(), "producer")), "device")
        repeat(cycles) {
            val since = SystemClock.elapsedRealtime()
            main { FloatingMirrorService.startRecording(config) }
            await("recording has observed video, not only a start acknowledgement") {
                CameraRecordingService.state.value.videoFlowConfirmed && !FloatingMirrorService.state.value.busy
            }
            SystemClock.sleep(1_000)
            main { CameraRecordingService.stop(context) }
            await("stop destroys recorder and restores actual preview frames") {
                !CameraRecordingService.isRunning() && StandaloneMirrorService.isPreviewing() &&
                    !FloatingMirrorService.state.value.busy && MirrorPreviewRuntime.evidence.reason == "LIVE" &&
                    MirrorPreviewRuntime.evidence.frames > 3
            }
            if (BuildConfig.RETAINED_CAMERA_HANDOFF_ENABLED) {
                assertSame("The product button flow must retain the actual CameraDevice", device,
                    field(requireNotNull(field(service(), "producer")), "device"))
                assertEquals(0, EventLogger.events.value.count { it.elapsedRealtimeMs >= connectionSince &&
                    it.eventName in setOf("CAMERA_CONNECTION_NATIVE_OPEN_REQUESTED", "CAMERA_CONNECTION_NATIVE_CLOSED") })
            }
            assertTrue(MirrorPresentation(context).videoVisible)
            assertEquals("Only the explicit recording start", 1,
                EventLogger.events.value.count { it.elapsedRealtimeMs >= since && it.eventName == "RECORDER_START" })
        }
    }

    @Test fun openedCameraWithoutFramesIsNotACompletedPreviewStart() = withPreview(attachDisplay = false) {
        SystemClock.sleep(500)
        assertFalse("Opening CameraDevice does not prove a visible picture", StandaloneMirrorService.isPreviewing())
        assertTrue("Still preparing until a displayed frame arrives", FloatingMirrorService.state.value.busy)
    }

    @Test fun explicitRecordingCanReplaceWaitingForAPreviewFrame() = withPreview(attachDisplay = false) {
        assumeTrue("Production reserve available", context.filesDir.usableSpace > 11L * 1024 * 1024 * 1024)
        assertTrue(FloatingMirrorService.state.value.previewPreparing)
        assertTrue(FloatingMirrorService.canStartWhilePreviewPrepares())
        val base = requireNotNull(ProductRecorderConfigFactory.create(context, RecordingSourceRole.CABIN))
        val config = base.copy(source = base.source.copy(profile = base.profile.copy(
            size = com.dante.zeekrcapabilitylab.probe.camera.ProfileSize(640, 480), bitrateBps = 2_000_000)),
            minFreeBytes = 10L * 1024 * 1024 * 1024, requestedFrameRate = 15, mirrorPreviewEnabled = true)
        val since = SystemClock.elapsedRealtime()
        main { FloatingMirrorService.startRecording(config) }
        await("explicit recording replaces the preview wait after camera release") {
            CameraRecordingService.state.value.videoFlowConfirmed && !FloatingMirrorService.state.value.busy
        }
        assertEquals(1, EventLogger.events.value.count { it.elapsedRealtimeMs >= since && it.eventName == "RECORDER_START" })
        SystemClock.sleep(1_000)
        main { CameraRecordingService.stop(context) }
        await("explicit recording stopped") { !CameraRecordingService.isRunning() }
    }

    @Test fun directPreviewRetainsCameraAndOutputAcrossHomeOverlayMoves() = withPreview { activity ->
        val initialController = controller()
        val initialTexture = field(initialController, "texture")
        val initialProducer = field(service(), "producer")!!
        val initialDevice = field(initialProducer, "device")
        assertEquals("Use the direct Camera2 preview backend", true, field(initialController, "directPreview"))
        repeat(2) {
            var before = MirrorPreviewRuntime.evidence.frames
            main { activity.moveTaskToBack(true) }
            await("new displayed frames in overlay") {
                MirrorPreviewRuntime.evidence.destination == "OVERLAY" &&
                    MirrorPreviewRuntime.evidence.reason == "LIVE" && MirrorPreviewRuntime.evidence.frames > before + 3
            }
            before = MirrorPreviewRuntime.evidence.frames
            main { activity.startActivity(Intent(activity, MirrorSleepTestHostActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
            await("new displayed frames after returning home") {
                MirrorPreviewRuntime.evidence.destination == "HOME" &&
                    MirrorPreviewRuntime.evidence.reason == "LIVE" && MirrorPreviewRuntime.evidence.frames > before + 3
            }
            assertSame(initialController, controller())
            assertSame("Same camera consumer across windows", initialTexture, field(controller(), "texture"))
            assertSame(initialDevice, field(initialProducer, "device"))
        }
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
