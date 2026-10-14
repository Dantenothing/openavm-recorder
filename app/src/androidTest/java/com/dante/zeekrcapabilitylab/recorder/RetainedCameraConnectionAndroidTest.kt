package com.dante.zeekrcapabilitylab.recorder

import android.content.Intent
import android.hardware.camera2.*
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.os.*
import android.view.Surface
import android.view.TextureView
import androidx.test.platform.app.InstrumentationRegistry
import com.dante.zeekrcapabilitylab.BuildConfig
import com.dante.zeekrcapabilitylab.event.EventLogger
import com.dante.zeekrcapabilitylab.mirror.MirrorSleepTestHostActivity
import com.dante.zeekrcapabilitylab.probe.camera.CameraFormatProfile
import com.dante.zeekrcapabilitylab.probe.camera.ProfileSize
import com.dante.zeekrcapabilitylab.product.SettingsStore
import com.dante.zeekrcapabilitylab.sentry.CanaryCameraInterlock
import com.dante.zeekrcapabilitylab.service.recorder.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Actual Camera2 -> product RecorderSession -> preview, on one native connection.
 * This establishes Android ownership/finalization only, not proprietary AVM compatibility. */
class RetainedCameraConnectionAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun await(label: String, timeout: Long = 15_000, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeout
        while (!condition() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(25)
        assertTrue(label, condition())
    }
    @Test fun previewRecordStopPreviewKeepsOneConnectionAndEveryFilePlayable() = runTransfers(false)
    @Test fun retainedPrimaryAndNewCabinGroupCanStartAgainWithoutOverlappingOldOwners() = runTransfers(true)
    private fun runTransfers(auxiliary: Boolean) {
        assumeTrue("Disposable emulator only", Build.HARDWARE == "ranchu")
        assertTrue(BuildConfig.RETAINED_CAMERA_HANDOFF_ENABLED)
        assertTrue("Fixture needs product storage reserve", context.filesDir.usableSpace > (11L shl 30))
        val activity = instrumentation.startActivitySync(Intent(context, MirrorSleepTestHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MirrorSleepTestHostActivity
        lateinit var view: TextureView
        instrumentation.runOnMainSync { view = TextureView(activity); activity.setContentView(view) }
        await("texture ready") { view.isAvailable }
        instrumentation.runOnMainSync { view.surfaceTexture!!.setDefaultBufferSize(640, 480) }
        val manager = context.getSystemService(CameraManager::class.java)
        val cameraId = manager.cameraIdList.first()
        // A declared emulator raster exercises the normal primary direct-codec route.
        // It is not a measurement of the vehicle's proprietary surround dimensions.
        val config = RecorderConfig(SessionSourceSnapshot(RecordingSourceRole.SURROUND, cameraId,
            CameraFormatProfile(ProfileSize(640, 480), 2_000_000), RecordingLayoutKind.FOUR_LANE_V1),
            10, 5L shl 30, 10L shl 30, RecordingStoragePreference.INTERNAL_ONLY, mirrorPreviewEnabled = true,
            auxiliaryChannels = if (auxiliary) listOf(RecordingSourceRole.CABIN) else emptyList())
        val codec = requireNotNull(MediaCodecList(MediaCodecList.REGULAR_CODECS)
            .findEncoderForFormat(ContinuousVideoRecorder.format(config)))
        val since = SystemClock.elapsedRealtime()
        val filesBefore = File(context.filesDir, "recordings/segments").list().orEmpty().toSet()
        var preview: Preview? = null
        var recorder: RecorderSession? = null
        val settings = SettingsStore.get(context)
        val originalReserve = settings.minFreeBytes
        val originalCabin = settings.cameraMapping(RecordingSourceRole.CABIN)
        try {
            settings.setMinFreeBytes(10L shl 30)
            if (auxiliary) settings.setCameraMapping(RecordingSourceRole.CABIN, manager.cameraIdList.single { it != cameraId })
            preview = Preview(manager, cameraId, Surface(view.surfaceTexture!!), "initial")
            await("initial preview frames") { preview!!.frames > 10 }
            val nativeDevice = preview.device
            repeat(6) { cycle ->
                val recordTicket = "record-$cycle"
                preview!!.finish(recordTicket)
                await("preview producer settled") { preview!!.finished }
                await("record transfer ready") { RetainedCameraConnection.ready(recordTicket, cameraId) }
                val state = AtomicReference(RecorderState())
                val stopped = CountDownLatch(1)
                val disposed = CountDownLatch(1)
                lateinit var session: RecorderSession
                session = RecorderSession(context, { state.set(it)
                    if (auxiliary && it.recordingSessionId != null) RecordingChannels.follow(context, config, it, session)
                }, { stopped.countDown() },
                    externallyManagedPresence = true,
                    selectDirectCodec = { _, _ -> ContinuousEncoderSelection("DEVELOPMENT_SOFTWARE_CODEC", codec, true) },
                    cameraHandoffTicket = { recordTicket })
                recorder = session
                if (auxiliary) RecordingChannels.start(context, config, session) else session.start(config)
                await("product records real frames: ${state.get()}") {
                    state.get().status == RecorderStatus.RECORDING && (session.captureEvidence().encoder?.writtenFrames ?: 0) >= 20
                }
                if (auxiliary) await("both actual cameras writing") {
                    RecordingChannels.state.value.filter { it.recordingSessionId == state.get().recordingSessionId }
                        .let { it.size == 2 && it.all { it.writtenFrames >= 20 && it.errorCode == null } }
                }
                val previewTicket = "preview-$cycle"
                assertTrue(RetainedCameraConnection.expect(cameraId, previewTicket, SystemClock.elapsedRealtime() + 30_000))
                if (auxiliary) RecordingChannels.stop(state.get().recordingSessionId)
                session.stop()
                assertTrue("product Stop completed: ${state.get()}", stopped.await(30, TimeUnit.SECONDS))
                session.releaseForModeHandoff { disposed.countDown() }
                assertTrue("worker/IO disposal", disposed.await(15, TimeUnit.SECONDS))
                recorder = null
                assertNull(state.get().lastError)
                await("preview transfer ready") { RetainedCameraConnection.ready(previewTicket, cameraId) }
                assertFalse("Retained connection is never falsely native-idle", CanaryCameraInterlock.normalIdle())
                preview = Preview(manager, cameraId, Surface(view.surfaceTexture!!), previewTicket)
                await("preview resumes after Stop $cycle") { preview!!.frames > 10 }
                assertSame(nativeDevice, preview!!.device)
                await("stopped recording leaves no encoder, file-writer or recorder-camera worker") {
                    Thread.getAllStackTraces().keys.none { it.isAlive && it.name in
                        setOf("recorder-camera", "openavm-encoder", "openavm-mp4", "product-codec-drain", "product-file-writer") }
                }
                val recordingDescriptors = File("/proc/self/fd").list().orEmpty().mapNotNull {
                    runCatching { android.system.Os.readlink("/proc/self/fd/$it") }.getOrNull()
                }.filter { it.startsWith(File(context.filesDir, "recordings/segments").absolutePath + "/") }
                assertTrue("Closed files retain no descriptors: $recordingDescriptors", recordingDescriptors.isEmpty())
            }
            preview!!.finish(null)
            await("last preview fully closed") { preview!!.finished && CanaryCameraInterlock.normalIdle() }
            val events = EventLogger.events.value.filter { it.elapsedRealtimeMs >= since }
            assertEquals(1, events.count { it.eventName == "CAMERA_CONNECTION_NATIVE_OPEN_REQUESTED" })
            assertEquals(1, events.count { it.eventName == "CAMERA_CONNECTION_NATIVE_CLOSED" })
            assertEquals(12, events.count { it.eventName == "CAMERA_CONNECTION_LEASE_ATTACHED" })
            val files = File(context.filesDir, "recordings/segments").listFiles().orEmpty()
                .filter { it.name !in filesBefore && it.extension == "mp4" }
            assertEquals(if (auxiliary) 12 else 6, files.size)
            files.forEach { file ->
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(file.absolutePath); assertEquals(1, extractor.trackCount); extractor.selectTrack(0)
                    assertEquals(0L, extractor.sampleTime)
                    var count = 0; var previous = -1L
                    while (extractor.sampleTime >= 0) {
                        assertTrue(extractor.sampleTime > previous); previous = extractor.sampleTime
                        count++; extractor.advance()
                    }
                    assertTrue("Playable samples in ${file.name}", count >= 20)
                } finally { extractor.release() }
            }
            assertEquals(0, CaptureCleanupRuntime.pendingOwners.value)
            val evidenceFiles = File(context.filesDir, "retained-camera-${if (auxiliary) "group" else "single"}-files").apply { mkdirs() }
            files.forEach { it.copyTo(File(evidenceFiles, it.name), overwrite = true) }
            File(context.filesDir, "retained-camera-${if (auxiliary) "group" else "single"}-result.txt").writeText(
                "cycles=6\nnativeOpens=1\nnativeCloses=1\nleaseTransfers=12\nplayableFiles=${files.size}\n" +
                    "cameraIdle=true\ncleanupOwners=0\nsource=EMULATOR_CAMERA2\n")
        } finally {
            settings.setMinFreeBytes(originalReserve)
            if (auxiliary) settings.setCameraMapping(RecordingSourceRole.CABIN, originalCabin)
            recorder?.let { File(context.filesDir, "retained-camera-last-state.txt").writeText(it.stateSnapshot().toString() + "\n" + it.captureEvidence()) }
            File(context.filesDir, "retained-camera-events.txt").writeText(EventLogger.events.value.filter {
                it.elapsedRealtimeMs >= since && (it.eventName.startsWith("CAMERA_CONNECTION_") || it.eventName.startsWith("RECORDER_"))
            }.joinToString("\n"))
            RetainedCameraConnection.cancel()
            if (auxiliary) RecordingChannels.stop(null)
            recorder?.stop(); recorder?.release()
            preview?.finish(null)
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private inner class Preview(manager: CameraManager, private val cameraId: String, private val output: Surface, ticket: String) {
        private val worker = HandlerThread("retained-preview-fixture").apply { start() }
        private val handler = Handler(worker.looper)
        @Volatile var device: CameraDevice? = null; private set
        @Volatile var frames = 0; private set
        @Volatile var finished = false; private set
        private var session: CameraCaptureSession? = null
        private val sequences = mutableSetOf<Int>()
        private var transaction: CaptureCloseTransaction? = null
        init {
            RetainedCameraConnection.open(manager, cameraId, ticket, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    camera.createCaptureSession(listOf(output), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(value: CameraCaptureSession) {
                            session = value
                            val sequence = value.setRepeatingRequest(camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                .apply { addTarget(output) }.build(), object : CameraCaptureSession.CaptureCallback() {
                                    override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) { frames++ }
                                    override fun onCaptureSequenceCompleted(session: CameraCaptureSession, sequenceId: Int, frameNumber: Long) {
                                        sequences.remove(sequenceId); transaction?.sequenceEnded(sequenceId)
                                    }
                                    override fun onCaptureSequenceAborted(session: CameraCaptureSession, sequenceId: Int) {
                                        sequences.remove(sequenceId); transaction?.sequenceEnded(sequenceId)
                                    }
                                }, handler)
                            sequences += sequence
                        }
                        override fun onConfigureFailed(session: CameraCaptureSession) { error("Preview configure failed") }
                        override fun onReady(session: CameraCaptureSession) { transaction?.sessionReady() }
                        override fun onClosed(session: CameraCaptureSession) { transaction?.sessionClosed() }
                    }, handler)
                }
                override fun onDisconnected(camera: CameraDevice) { error("Unexpected disconnect") }
                override fun onError(camera: CameraDevice, error: Int) { error("Unexpected camera error $error") }
                override fun onClosed(camera: CameraDevice) { transaction?.deviceClosed() }
            }, handler)
        }
        fun finish(ticket: String?) {
            if (finished) return
            if (ticket != null) assertTrue(RetainedCameraConnection.expect(cameraId, ticket, SystemClock.elapsedRealtime() + 30_000))
            handler.post {
                if (transaction != null) return@post
                val camera = requireNotNull(device)
                val transfer = if (ticket == null) null else requireNotNull(RetainedCameraConnection.prepare(camera))
                val hold = Any()
                val tx = CaptureCloseTransaction(CaptureCleanupRuntime.control, HandlerCloseDispatcher(handler),
                    HandlerCloseDispatcher(handler), object : CaptureCloseResources {
                        override fun stopRepeating() { session?.stopRepeating() }
                        override fun abortCaptures() { session?.abortCaptures() }
                        override fun closeSession() { session?.close() }
                        override fun closeDevice() { camera.close() }
                        override fun stopRecorder() = Unit
                        override fun resetRecorder() = Unit
                        override fun releaseRecorder() = Unit
                        override fun closeOutput(lost: Boolean) { output.release() }
                    }, true, true, false, sequences.toSet(), transfer == null, false,
                    trace = { _, _ -> }, unconfirmed = { error(it) }, completed = {
                        check(it.safeToContinue)
                        if (transfer != null) check(RetainedCameraConnection.producerReleased(camera, transfer, !it.terminal && it.errors.isEmpty()))
                        CaptureCleanupRuntime.settled(hold, true); finished = true; worker.quitSafely()
                    }, requireSessionCloseAck = transfer != null)
                transaction = tx; CaptureCleanupRuntime.retain(hold, cameraId, camera, tx); tx.begin()
            }
        }
    }
}
