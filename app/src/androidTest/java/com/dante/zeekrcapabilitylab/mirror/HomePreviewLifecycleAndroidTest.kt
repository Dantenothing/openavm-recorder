package com.dante.zeekrcapabilitylab.mirror

import android.content.Intent
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import androidx.test.platform.app.InstrumentationRegistry
import com.dante.zeekrcapabilitylab.probe.camera.CameraFormatProfile
import com.dante.zeekrcapabilitylab.probe.camera.ProfileSize
import com.dante.zeekrcapabilitylab.service.recorder.*
import com.dante.zeekrcapabilitylab.service.CameraRecordingService
import com.dante.zeekrcapabilitylab.sentry.CanaryCameraInterlock
import com.dante.zeekrcapabilitylab.sentry.CanaryCameraOpenAdapter
import com.dante.zeekrcapabilitylab.ui.product.SafeManualPreviewController
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.io.File
import kotlinx.coroutines.runBlocking

/** The home page's non-floating preview owner, using real Camera2 and TextureView.
 * Delays are injected at callback delivery, never by fabricating a native release. */
class HomePreviewLifecycleAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner)
    private fun await(message: String, timeout: Long = 10_000, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (!condition() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(30)
        assertTrue(message, condition())
    }

    private fun withHome(test: (SafeManualPreviewController, TextureView, MirrorSleepTestHostActivity, SessionSourceSnapshot) -> Unit) {
        assumeTrue("Disposable emulator only", Build.HARDWARE == "ranchu")
        val camera = context.getSystemService(CameraManager::class.java).cameraIdList.first()
        val activity = instrumentation.startActivitySync(Intent(context, MirrorSleepTestHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MirrorSleepTestHostActivity
        val controller = SafeManualPreviewController(context)
        lateinit var view: TextureView
        try {
            main {
                view = TextureView(activity)
                controller.attach(view)
                activity.setContentView(FrameLayout(activity).apply {
                    addView(view, FrameLayout.LayoutParams(640, 480))
                })
            }
            await("actual TextureView consumer available") { view.isAvailable }
            test(controller, view, activity, SessionSourceSnapshot(RecordingSourceRole.CABIN, camera,
                CameraFormatProfile(ProfileSize(640, 480), 2_000_000), RecordingLayoutKind.SINGLE_V1))
        } finally {
            if (CameraRecordingService.isRunning()) {
                main { CameraRecordingService.stop(context) }
                await("recorder released", 15_000) { !CameraRecordingService.isRunning() }
            }
            main { controller.release(); activity.finish() }
            await("home owner actually released its camera and worker", 15_000) {
                CanaryCameraInterlock.normalIdle() && CaptureCleanupRuntime.pendingOwners.value == 0 &&
                    (field(controller, "cameraThread") as? Thread)?.isAlive != true
            }
            // Activity.finish() schedules window removal; an attached TextureView
            // legitimately retains its consumer even after the camera is closed.
            await("window removal retires the now-unowned consumers") {
                activity.isDestroyed && !view.isAttachedToWindow && (field(controller, "textures") as Map<*, *>).isEmpty()
            }
            assertEquals("No recorder leases after native cleanup", 0, (field(controller, "recorderTextures") as Map<*, *>).size)
        }
    }

    @Test fun duplicateAttachmentWhileOpenIsPendingDoesNotCancelTheOnlyCamera() = withHome { controller, view, _, source ->
        val callbacks = field(CanaryCameraOpenAdapter, "callbacks") as Handler
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        try {
            assertTrue(callbacks.post { entered.countDown(); check(resume.await(12, TimeUnit.SECONDS)) })
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            main { controller.startPreview(source) }
            await("native open is admitted but its callback is delayed") {
                field(controller, "opening") == true && !CanaryCameraInterlock.normalIdle()
            }
            main { controller.attach(view) }
            val drained = CountDownLatch(1)
            (field(controller, "cameraHandler") as Handler).post { drained.countDown() }
            assertTrue(drained.await(1, TimeUnit.SECONDS))
            resume.countDown()
            await("same visible preview intent receives real frames after duplicate attachment") {
                controller.state.value.firstFrame
            }
            assertTrue(controller.state.value.active)
            assertNull(controller.state.value.error)
        } finally { resume.countDown() }
    }

    @Test fun removingHomeWaitsForActualCameraReleaseBeforeDestroyingItsConsumer() = withHome { controller, view, activity, source ->
        main { controller.startPreview(source) }
        await("initial preview has actual frames") { controller.state.value.firstFrame }
        val texture = requireNotNull(view.surfaceTexture)
        val worker = field(controller, "cameraHandler") as Handler
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        try {
            assertTrue(worker.post { entered.countDown(); check(resume.await(12, TimeUnit.SECONDS)) })
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            main { activity.setContentView(FrameLayout(activity)) }
            await("old view detached") { !view.isAttachedToWindow }
            assertFalse("Removing a page must not abandon a still-owned camera buffer queue", texture.isReleased)
            assertFalse("The actual camera is still owned while its worker is held", CanaryCameraInterlock.normalIdle())
            resume.countDown()
            await("native close then consumer release") { texture.isReleased && CanaryCameraInterlock.normalIdle() }
        } finally { resume.countDown() }
    }

    @Test fun pageDisposalDuringPendingOpenWaitsForLateNativeClose() = withHome { controller, view, activity, source ->
        val callbacks = field(CanaryCameraOpenAdapter, "callbacks") as Handler
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val texture = requireNotNull(view.surfaceTexture)
        try {
            assertTrue(callbacks.post { entered.countDown(); check(resume.await(12, TimeUnit.SECONDS)) })
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            main { controller.startPreview(source) }
            await("pending native open") { field(controller, "opening") == true && !CanaryCameraInterlock.normalIdle() }
            main { controller.release(); activity.setContentView(FrameLayout(activity)) }
            assertFalse("Pending Camera2 open still owns its consumer", texture.isReleased)
            assertTrue((field(controller, "cameraThread") as Thread).isAlive)
            resume.countDown()
            await("late onOpened is closed and its consumer is retired") {
                texture.isReleased && CanaryCameraInterlock.normalIdle() &&
                    !(field(controller, "cameraThread") as Thread).isAlive
            }
            assertFalse(controller.state.value.active)
        } finally { resume.countDown() }
    }

    @Test fun recorderKeepsDetachedPreviewUntilReplacementAndStopActuallyReleaseIt() = withHome { controller, view, activity, source ->
        assumeTrue("Production storage reserve", context.filesDir.usableSpace > 11L shl 30)
        main { controller.startPreview(source) }
        await("first preview frames") { controller.state.value.firstFrame }
        assertTrue(runBlocking { controller.stopAndAwait() })
        val firstTexture = requireNotNull(view.surfaceTexture)
        val initialReleased = AtomicInteger()
        lateinit var initialSurface: Surface
        main {
            initialSurface = requireNotNull(controller.acquireRecorderPreviewSurface(android.util.Size(640, 480)))
            CameraRecordingService.start(context, config(source), initialSurface, previewReleased = {
                initialReleased.incrementAndGet(); controller.recorderPreviewReleased(initialSurface)
            })
        }
        await("actual recorder and preview ready", 15_000) {
            CameraRecordingService.state.value.videoFlowConfirmed && controller.state.value.firstFrame
        }
        val sessionId = requireNotNull(CameraRecordingService.state.value.recordingSessionId)
        main { activity.setContentView(FrameLayout(activity)) }
        assertFalse("Recording still owns the old preview", firstTexture.isReleased)
        assertEquals(0, initialReleased.get())
        // This is the same replacement used when RecordScreen is composed again.
        lateinit var replacementView: TextureView
        main {
            replacementView = TextureView(activity)
            controller.attach(replacementView)
            activity.setContentView(FrameLayout(activity).apply {
                addView(replacementView, FrameLayout.LayoutParams(640, 480))
            })
        }
        await("replacement view ready") { replacementView.isAvailable }
        val nextReleased = AtomicInteger()
        val nextTexture = requireNotNull(replacementView.surfaceTexture)
        lateinit var replacement: Surface
        main {
            replacement = requireNotNull(controller.acquireRecorderPreviewSurface(android.util.Size(640, 480)))
            CameraRecordingService.replacePreviewSurface(replacement) {
                nextReleased.incrementAndGet(); controller.recorderPreviewReleased(replacement)
            }
        }
        await("replacement receives frames and old native output is released") {
            controller.state.value.firstFrame && firstTexture.isReleased && initialReleased.get() == 1
        }
        assertFalse(initialSurface.isValid)
        assertFalse(nextTexture.isReleased)
        assertNull(CameraRecordingService.state.value.lastError)
        SystemClock.sleep(1_000)
        main { controller.release(); activity.setContentView(FrameLayout(activity)) }
        assertFalse("Disposed controller cannot destroy recorder-owned consumer", nextTexture.isReleased)
        main { CameraRecordingService.stop(context) }
        await("Stop acknowledges the second output exactly once", 15_000) {
            !CameraRecordingService.isRunning() && nextTexture.isReleased && nextReleased.get() == 1 &&
                CanaryCameraInterlock.normalIdle()
        }
        assertFalse(replacement.isValid)
        assertEquals(1, initialReleased.get())
        assertNull(CameraRecordingService.state.value.lastError)
        val sidecars = File(context.filesDir, "recordings/segments").listFiles().orEmpty()
            .filter { it.name.endsWith(".json") }.mapNotNull(SegmentSidecarIO::read)
            .filter { it.recordingSessionId == sessionId }
        assertEquals("Preview replacement must preserve the current recording", 1, sidecars.size)
        val saved = sidecars.single()
        assertEquals(SegmentSidecar.RESULT_SUCCESS, saved.result)
        assertFalse(saved.provisional)
        val output = File(context.filesDir, "stability-home-preview/$sessionId").apply { mkdirs() }
        val video = File(saved.file)
        video.copyTo(File(output, video.name))
        SegmentSidecarIO.sidecarFileFor(video).copyTo(File(output, video.name + ".json"))
        File(output, "result.txt").writeText("scope=EMULATOR_CAMERA2_HOME_PREVIEW_HANDOFF\n" +
            "session=$sessionId\ninitialReleaseCallbacks=${initialReleased.get()}\nreplacementReleaseCallbacks=${nextReleased.get()}\n" +
            "oldConsumerReleased=${firstTexture.isReleased}\nnewConsumerReleased=${nextTexture.isReleased}\n" +
            "cleanupOwners=${CaptureCleanupRuntime.pendingOwners.value}\n")
    }

    @Test fun rejectedRecordingReturnsAnUnsubmittedPreviewConsumer() = withHome { controller, view, activity, source ->
        val texture = requireNotNull(view.surfaceTexture)
        val released = AtomicInteger()
        lateinit var surface: Surface
        main {
            surface = requireNotNull(controller.acquireRecorderPreviewSurface(android.util.Size(640, 480)))
            activity.setContentView(FrameLayout(activity))
            assertFalse(texture.isReleased)
            CameraRecordingService.start(context, config(source).copy(segmentSeconds = 0), surface, previewReleased = {
                released.incrementAndGet(); controller.recorderPreviewReleased(surface)
            })
        }
        await("configuration rejection returns the unsent output") {
            released.get() == 1 && texture.isReleased && !CameraRecordingService.isRunning()
        }
        assertFalse(surface.isValid)
        assertTrue(CanaryCameraInterlock.normalIdle())
    }

    @Test fun repeatedHomeStartsAndStopsKeepOneWorkerAndNoRetiredConsumers() = withHome { controller, view, _, source ->
        repeat(20) {
            val before = requireNotNull(view.surfaceTexture).timestamp
            main { controller.startPreview(source); controller.attach(view) }
            await("cycle $it has new real frames") {
                controller.state.value.firstFrame && requireNotNull(view.surfaceTexture).timestamp > before
            }
            assertTrue("cycle $it actual release", runBlocking { controller.stopAndAwait() })
            assertFalse("Still-attached view is reusable", requireNotNull(view.surfaceTexture).isReleased)
            assertEquals(0, CaptureCleanupRuntime.pendingOwners.value)
            assertNull(controller.state.value.error)
            assertEquals(1, (field(controller, "textures") as Map<*, *>).size)
            assertEquals(0, (field(controller, "recorderTextures") as Map<*, *>).size)
        }
    }

    private fun config(source: SessionSourceSnapshot) = RecorderConfig(source,
        segmentSeconds = 60, storageLimitBytes = 5L shl 30, minFreeBytes = 10L shl 30,
        storagePreference = RecordingStoragePreference.INTERNAL_ONLY, requestedFrameRate = 15)
}
