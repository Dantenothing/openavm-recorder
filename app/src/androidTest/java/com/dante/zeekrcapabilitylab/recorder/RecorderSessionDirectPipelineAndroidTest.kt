package com.dante.zeekrcapabilitylab.recorder

import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.dante.zeekrcapabilitylab.mirror.MirrorSleepTestHostActivity
import com.dante.zeekrcapabilitylab.event.EventLogger
import com.dante.zeekrcapabilitylab.probe.camera.CameraFormatProfile
import com.dante.zeekrcapabilitylab.probe.camera.ProfileSize
import com.dante.zeekrcapabilitylab.service.recorder.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real session, Camera2, codec, muxers, publication and close acknowledgements on the emulator.
 * Only codec selection is supplied: the emulator advertises a software AVC codec, not car hardware. */
class RecorderSessionDirectPipelineAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun sessionPublishesAllSegmentsAndTailBeforeDisposal() = runSession(blockNextOpen = false)

    @Test fun stopDuringSlowNextOutputOpenKeepsCurrentFileAndDiscardsUnusedOutput() = runSession(blockNextOpen = true)

    @Test fun slowDestinationPreparationDoesNotStopHealthyCurrentRecording() = runSession(blockNextOpen = false, slowCut = true)
    @Test fun thirteenMinuteNaturalCutSoak() = runSession(blockNextOpen = false, soak = true)
    @Test fun changedCabinFormatRequestsAndPublishesAnEarlyBoundaryThroughTheRealSession() =
        runSession(blockNextOpen = false, formatTransition = true)

    private fun runSession(blockNextOpen: Boolean, slowCut: Boolean = false, soak: Boolean = false, formatTransition: Boolean = false) {
        assumeTrue("Disposable emulator only", Build.HARDWARE == "ranchu")
        assumeTrue("Production storage reserve", context.filesDir.usableSpace > 11L * 1024 * 1024 * 1024)
        val manager = context.getSystemService(CameraManager::class.java)
        val camera = manager.cameraIdList.first { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
                .getOutputSizes(MediaCodec::class.java).any { it.width == 640 && it.height == 480 }
        }
        val config = RecorderConfig(SessionSourceSnapshot(RecordingSourceRole.CABIN, camera,
            CameraFormatProfile(ProfileSize(640, 480), 2_000_000), RecordingLayoutKind.SINGLE_V1),
            segmentSeconds = 10, storageLimitBytes = 5L shl 30, minFreeBytes = 10L shl 30,
            storagePreference = RecordingStoragePreference.INTERNAL_ONLY)
        val codec = requireNotNull(MediaCodecList(MediaCodecList.REGULAR_CODECS)
            .findEncoderForFormat(ContinuousVideoRecorder.format(config)))
        val activity = instrumentation.startActivitySync(Intent(context, MirrorSleepTestHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MirrorSleepTestHostActivity
        val state = AtomicReference(RecorderState())
        val stopped = CountDownLatch(1)
        val released = CountDownLatch(1)
        val allowIo = CountDownLatch(1)
        val sessionId = UUID.randomUUID().toString()
        val session = RecorderSession(context, { state.set(it) }, { stopped.countDown() },
            externallyManagedPresence = true, auxiliaryChannel = true,
            selectDirectCodec = { _, _ -> ContinuousEncoderSelection("DEVELOPMENT_SOFTWARE_CODEC", codec, true) })
        val directory = File(context.filesDir, "stability-session/$sessionId").apply { mkdirs() }
        val segments = File(context.filesDir, "recordings/segments")
        val filesBefore = segments.list().orEmpty().toSet()
        val since = SystemClock.elapsedRealtime()
        val settings = com.dante.zeekrcapabilitylab.product.SettingsStore.get(context)
        val previousCleanup = settings.autoCleanupEnabled
        // This test validates every file. The emulator's global 20 GiB reserve can otherwise
        // correctly evict prior files even though this run explicitly reserves only 10 GiB.
        settings.setAutoCleanupEnabled(false)
        try {
            session.start(config, sharedIdentity = RecordingChannelIdentity(sessionId,
                SystemClock.elapsedRealtime(), RecordingChannelId.FRONT_CABIN))
            await("observed encoded frames", state) { (session.captureEvidence().encoder?.writtenFrames ?: 0) >= 20 }
            if (formatTransition) {
                val recorder = RecorderSession::class.java.getDeclaredField("recordingEncoder").apply { isAccessible = true }
                    .get(session) as ContinuousVideoRecorder
                ContinuousRecorderPipelineAndroidTest().injectSameLayoutFormat(recorder)
                await("format callback requested and wrote an early second file", state, 7_000) { state.get().segmentNumber >= 2 }
                assertNull(state.get().lastError)
                assertFalse(recorder.formatBoundaryNeeded)
                assertEquals("ROLLOVER_APPLIED", recorder.evidence().formatDecision)
                SystemClock.sleep(600)
                session.stop()
            } else if (blockNextOpen || slowCut) {
                val entered = CountDownLatch(1)
                val io = RecorderSession::class.java.getDeclaredField("ioExecutor").apply { isAccessible = true }
                    .get(session) as ExecutorService
                io.execute { entered.countDown(); check(allowIo.await(40, TimeUnit.SECONDS)) }
                assertTrue(entered.await(1, TimeUnit.SECONDS))
                val rotating = RecorderSession::class.java.getDeclaredField("continuousRotationPending").apply { isAccessible = true }
                await("real segment timer requested rotation", state) { rotating.getBoolean(session) }
                if (slowCut) {
                    val before = requireNotNull(session.captureEvidence().encoder!!.writtenFrames)
                    SystemClock.sleep(9_000)
                    assertEquals("Preparing a destination must not spend the keyframe/writer deadline", RecorderStatus.RECORDING, state.get().status)
                    assertNull(state.get().lastError)
                    assertTrue("Current file still accepts frames during slow preparation", requireNotNull(session.captureEvidence().encoder!!.writtenFrames) > before + 100)
                    allowIo.countDown()
                    await("prepared destination becomes a decodable second file", state) { state.get().segmentNumber >= 2 }
                    SystemClock.sleep(600)
                }
                session.stop()
                // Stop is on the camera handler before IO is allowed to return the prepared descriptor.
                await("stop reached the session", state) { state.get().status != RecorderStatus.RECORDING }
                allowIo.countDown()
            } else {
                val count = if (soak) 79 else 3
                await("$count natural 10-second segments", state, if (soak) 850_000 else 30_000) { state.get().segmentNumber >= count || state.get().lastError != null }
                assertNull(state.get().lastError)
                SystemClock.sleep(600)
                session.stop()
            }
            assertTrue("Stop acknowledgement: ${state.get()}", stopped.await(12, TimeUnit.SECONDS))
            session.releaseForModeHandoff { released.countDown() }
            assertTrue("IO and native cleanup acknowledgement", released.await(12, TimeUnit.SECONDS))
            assertFalse(state.get().cleanupUnconfirmed)
            assertNull(state.get().lastError)
            val evidence = requireNotNull(session.captureEvidence().encoder)
            assertTrue("Unused destinations must be removed after Stop", segments.list().orEmpty().none {
                it !in filesBefore && SegmentNaming.isPartial(it)
            })
            val sidecars = segments.listFiles().orEmpty().filter { it.name.endsWith(".json") }
                .mapNotNull { file -> SegmentSidecarIO.read(file) }.filter { it.recordingSessionId == sessionId }
                .sortedBy { it.segmentNumber }
            assertEquals(if (blockNextOpen) 1 else if (slowCut || formatTransition) 2 else if (soak) 79 else 3, sidecars.size)
            assertEquals(evidence.frames, evidence.writtenFrames)
            assertEquals(evidence.frames, sidecars.sumOf { requireNotNull(it.continuousTimeline).frames })
            var samples = 0L
            sidecars.forEach { sidecar ->
                assertEquals(SegmentSidecar.RESULT_SUCCESS, sidecar.result)
                assertFalse(sidecar.provisional)
                val file = File(sidecar.file)
                assertEquals(segments.canonicalPath, file.parentFile!!.canonicalPath)
                assertTrue(file.exists())
                val originalPartial = File(sidecar.file + SegmentNaming.PARTIAL_SUFFIX)
                assertFalse(InternalRecordingOutputOwners.owns(originalPartial))
                assertFalse(originalPartial.exists())
                val count = samples(file)
                assertEquals(requireNotNull(sidecar.continuousTimeline).frames, count)
                samples += count
                file.copyTo(File(directory, file.name))
                SegmentSidecarIO.sidecarFileFor(file).copyTo(File(directory, file.name + ".json"))
            }
            assertEquals(evidence.frames, samples)
            assertEquals(0, evidence.queueItems)
            assertEquals(0, CaptureCleanupRuntime.pendingOwners.value)
            val retainedRecordingFds = File("/proc/self/fd").list().orEmpty().mapNotNull { fd ->
                runCatching { android.system.Os.readlink("/proc/self/fd/$fd") }.getOrNull()
            }.filter { it.startsWith(segments.absolutePath + "/front_cabin-") }
            assertTrue("Recording descriptors must be closed: $retainedRecordingFds", retainedRecordingFds.isEmpty())
            assertEquals("File rotation must not reinstall the camera's repeating request", 0,
                EventLogger.events.value.count { it.elapsedRealtimeMs >= since && it.eventName == "RECORDER_PREVIEW_TARGET_CHANGED" })
            File(directory, "result.txt").writeText("session=$sessionId\nmode=${if (blockNextOpen) "STOP_DURING_OPEN" else "THREE_SEGMENTS"}\n" +
                "codec=$codec\nsource=EMULATOR_CAMERA2\nfiles=${sidecars.size}\nencoded=${evidence.frames}\n" +
                "written=${evidence.writtenFrames}\nextracted=$samples\nrelease=CONFIRMED\n")
        } finally {
            allowIo.countDown()
            if (released.count != 0L) {
                session.stop(); session.releaseForModeHandoff { released.countDown() }
                released.await(12, TimeUnit.SECONDS)
            }
            File(directory, "state.txt").writeText(state.get().toString())
            settings.setAutoCleanupEnabled(previousCleanup)
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun await(message: String, state: AtomicReference<RecorderState>, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeoutMs
        while (!condition() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(30)
        assertTrue("$message; ${state.get()}", condition())
    }

    private fun samples(file: File): Long {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath); assertEquals(1, extractor.trackCount); extractor.selectTrack(0)
            assertEquals(0L, extractor.sampleTime)
            assertTrue(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
            var total = 0L; var last = -1L
            while (extractor.sampleTime >= 0) {
                assertTrue(extractor.sampleTime > last); last = extractor.sampleTime; total++; extractor.advance()
            }
            return total
        } finally { extractor.release() }
    }
}
