package com.dante.zeekrcapabilitylab.recorder

import android.content.Intent
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraDevice
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.view.Surface
import androidx.test.platform.app.InstrumentationRegistry
import com.dante.zeekrcapabilitylab.mirror.*
import com.dante.zeekrcapabilitylab.event.EventLogger
import com.dante.zeekrcapabilitylab.diagnostic.ProcessExitReport
import com.dante.zeekrcapabilitylab.diagnostic.RecorderSupportReport
import com.dante.zeekrcapabilitylab.product.SettingsStore
import com.dante.zeekrcapabilitylab.probe.camera.CameraFormatProfile
import com.dante.zeekrcapabilitylab.probe.camera.ProfileSize
import com.dante.zeekrcapabilitylab.service.CameraRecordingService
import com.dante.zeekrcapabilitylab.service.recorder.*
import com.dante.zeekrcapabilitylab.sentry.CanaryCameraInterlock
import com.dante.zeekrcapabilitylab.sentry.CanaryCameraOpenAdapter
import com.dante.zeekrcapabilitylab.usbexport.UsbExportVolumeResolver
import com.dante.zeekrcapabilitylab.usbexport.UsbSegmentCatalog
import com.dante.zeekrcapabilitylab.usbexport.UsbMutationCoordinator
import com.dante.zeekrcapabilitylab.usbexport.UsbMediaStoreBackend
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.io.File

/** Real Camera2 service admission. Delays the existing group control queue, not a HAL callback. */
class RecordingGroupStartAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun twoRealCamerasOpenBeforeConfigurationAndSaveEverySegment() = recordMinuteBoundaries(false)

    @Test fun twoRecordingCamerasAndLiveMirrorReturnToFreshStandalonePreviewAfterStop() = recordMinuteBoundaries(true)

    @Test fun privateDrainFirstClosesBothChannelsWithValidTailsAndFreshPreview() {
        assumeTrue(com.dante.zeekrcapabilitylab.BuildConfig.RECORDER_SUPPORT_ENABLED)
        recordMinuteBoundaries(true, closeOrder = TerminalCloseOrder.DRAIN_FIRST)
    }

    @Test fun privateDeviceFirstClosesBothChannelsWithValidTailsAndFreshPreview() {
        assumeTrue(com.dante.zeekrcapabilitylab.BuildConfig.RECORDER_SUPPORT_ENABLED)
        recordMinuteBoundaries(true, closeOrder = TerminalCloseOrder.DEVICE_FIRST)
    }

    @Test fun oneHourTwoCameraRecordingKeepsLivePreviewAndReturnsToStandalone() {
        assumeTrue("Explicit one-hour developer endurance run",
            InstrumentationRegistry.getArguments().getString("stabilityOneHour") == "true")
        recordMinuteBoundaries(previewRoundTrip = true, boundaryCount = 60)
    }

    private fun recordMinuteBoundaries(previewRoundTrip: Boolean, boundaryCount: Int = 2,
                                       closeOrder: TerminalCloseOrder? = null) = withGroup(
        segmentSeconds = 60, previewRoundTrip = previewRoundTrip,
        closeTrial = closeOrder?.let { TerminalCloseTrial(java.util.UUID.randomUUID().toString(), it) },
    ) { session, state ->
        await("both channels writing", 20_000) { channels(state).size == 2 && channels(state).all { it.writtenFrames > 20 } }
        val id = requireNotNull(state.get().recordingSessionId)
        val evidence = File(context.filesDir, "stability-group/$id").apply { mkdirs() }
        val events = EventLogger.events.value.filter { it.payload["recordingSessionId"] == id }
        val allOpened = events.single { it.eventName == "RECORDER_GROUP_ALL_OPENED" }
        val configured = events.filter { it.eventName == "RECORDER_GROUP_CONFIGURE" }
        assertEquals(2, configured.size)
        assertEquals(2, events.count { it.eventName == "RECORDER_GROUP_MEMBER_OPENED" })
        assertTrue(configured.all { it.elapsedRealtimeMs >= allOpened.elapsedRealtimeMs })
        if (boundaryCount == 2) {
            await("both channels crossed two natural minute boundaries", 140_000) { channels(state).all { it.completedFiles >= 2 } }
        } else {
            val started = SystemClock.elapsedRealtime()
            var nextSample = started
            var previousFrames = MirrorPreviewRuntime.evidence.frames
            while (channels(state).any { it.completedFiles < boundaryCount }) {
                val now = SystemClock.elapsedRealtime()
                assertTrue("One-hour run exceeded its natural-cut deadline", now - started < boundaryCount * 65_000L + 30_000)
                assertNull("A channel failure must fail the endurance run", state.get().lastError)
                assertEquals(2, channels(state).size)
                assertTrue("Both streams must remain live: ${channels(state)}", channels(state).all {
                    it.status == "WRITING" && it.errorCode == null && !it.cleanupPending && !it.cleanupUnconfirmed
                })
                if (now >= nextSample) {
                    val current = MirrorPreviewRuntime.evidence
                    assertEquals("Preview remains live throughout recording", "LIVE", current.reason)
                    assertTrue((current.lastFrameAgeMs ?: Long.MAX_VALUE) <= 2_000)
                    if (now > started + 1_000) assertTrue("Preview must advance, not retain a last image", current.frames > previousFrames)
                    previousFrames = current.frames
                    appendResources(evidence, "recording", now - started, session, state)
                    nextSample = now + 30_000
                }
                SystemClock.sleep(1_000)
            }
            assertTrue("No accelerated timer is used for the hour", SystemClock.elapsedRealtime() - started >= 3_590_000)
            appendResources(evidence, "before_stop", SystemClock.elapsedRealtime() - started, session, state)
        }
        val beforeTail = if (previewRoundTrip) MirrorPreviewRuntime.evidence.frames else 0L
        SystemClock.sleep(700)
        if (previewRoundTrip) {
            val tail = MirrorPreviewRuntime.evidence
            assertEquals("Live preview survives both minute boundaries", "LIVE", tail.reason)
            assertTrue("The final preview is a new image, not a retained frame", tail.frames > beforeTail)
            assertTrue((tail.lastFrameAgeMs ?: Long.MAX_VALUE) <= 2_000)
        }
        session.stop()
        await("both stopped", 20_000) { channels(state).all { it.status == "STOPPED" && !it.cleanupPending } }
        assertNull(state.get().lastError)
        if (closeOrder != null) {
            val closes = EventLogger.events.value.filter { it.payload["recordingSessionId"] == id && it.eventName.startsWith("RECORDER_CLOSE_") }
            assertEquals(2, closes.count { it.eventName == "RECORDER_CLOSE_SETTLED" })
            for (channel in listOf(RecordingChannelId.SURROUND, RecordingChannelId.FRONT_CABIN)) {
                val edges = closes.filter { it.payload["channelId"] == channel }
                assertTrue(edges.isNotEmpty())
                assertTrue(edges.all { it.payload["requestedCloseOrder"] == closeOrder.name && it.payload["effectiveCloseOrder"] == closeOrder.name })
                fun edge(step: String) = edges.single { it.payload["closeStep"] == step }.elapsedRealtimeMs
                if (closeOrder == TerminalCloseOrder.DEVICE_FIRST) {
                    assertTrue(edge("DEVICE_CLOSE_ACK_PROCESSED") <= edge("RECORDER_STOP_NATIVE_ENTER"))
                    assertTrue(edge("DEVICE_CLOSE_ACK_PROCESSED") <= edge("CODEC_INPUT_RELEASE_ENTER"))
                } else assertTrue(edge("CODEC_INPUT_RELEASE_RETURN") <= edge("DEVICE_CLOSE_CALL_ENTER"))
            }
        }
        val saved = File(context.filesDir, "recordings/segments").listFiles().orEmpty()
            .filter { it.name.endsWith(".json") }.mapNotNull(SegmentSidecarIO::read).filter { it.recordingSessionId == id }
        val health = channels(state)
        assertEquals(setOf(RecordingChannelId.SURROUND, RecordingChannelId.FRONT_CABIN), health.map { it.channelId }.toSet())
        health.forEach { channel ->
            val files = saved.filter { it.channelId == channel.channelId }
            assertEquals(boundaryCount + 1, files.size)
            assertEquals(channel.encodedFrames, channel.writtenFrames)
            assertEquals(channel.writtenFrames, files.sumOf { requireNotNull(it.continuousTimeline).frames })
            var extracted = 0L
            files.forEach { sidecar ->
                assertEquals(SegmentSidecar.RESULT_SUCCESS, sidecar.result)
                assertFalse(sidecar.provisional)
                val file = File(sidecar.file)
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(file.absolutePath); assertEquals(1, extractor.trackCount); extractor.selectTrack(0)
                    assertEquals(0L, extractor.sampleTime)
                    assertTrue(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                    var count = 0L; var last = -1L
                    while (extractor.sampleTime >= 0) {
                        assertTrue(extractor.sampleTime > last); last = extractor.sampleTime; count++; extractor.advance()
                    }
                    assertEquals(requireNotNull(sidecar.continuousTimeline).frames, count)
                    extracted += count
                } finally { extractor.release() }
                file.copyTo(File(evidence, file.name))
                SegmentSidecarIO.sidecarFileFor(file).copyTo(File(evidence, file.name + ".json"))
            }
            assertEquals(channel.writtenFrames, extracted)
        }
        File(evidence, "health.txt").writeText(health.joinToString("\n"))
        File(evidence, "events.txt").writeText(EventLogger.events.value.filter { it.payload["recordingSessionId"] == id }
            .joinToString("\n") { "${it.elapsedRealtimeMs} ${it.eventName} ${it.payload}" })
        appendResources(evidence, "stopped", null, session, state)
    }

    /** Low-frequency developer-only observation; no new production worker or polling loop. */
    private fun appendResources(directory: File, phase: String, runMs: Long?, session: RecorderSession,
                                state: AtomicReference<RecorderState>) {
        val memory = android.os.Debug.MemoryInfo().also(android.os.Debug::getMemoryInfo)
        val runtime = Runtime.getRuntime()
        val encoder = session.captureEvidence().encoder
        val row = buildJsonObject {
            put("phase", phase); put("elapsedMs", SystemClock.elapsedRealtime()); put("runMs", runMs)
            put("processCpuMs", android.os.Process.getElapsedCpuTime())
            put("pssKiB", memory.totalPss); put("nativeHeapBytes", android.os.Debug.getNativeHeapAllocatedSize())
            put("javaHeapUsedBytes", runtime.totalMemory() - runtime.freeMemory())
            put("fdCount", File("/proc/self/fd").list()!!.size); put("threadCount", File("/proc/self/task").list()!!.size)
            put("cleanupOwners", CaptureCleanupRuntime.pendingOwners.value)
            put("mirrorFrames", MirrorPreviewRuntime.evidence.frames)
            put("mirrorFrameAgeMs", MirrorPreviewRuntime.evidence.lastFrameAgeMs)
            put("primaryQueueItems", encoder?.queueItems); put("primaryQueueBytes", encoder?.queueBytes)
            put("ownedWorkers", buildJsonObject {
                Thread.getAllStackTraces().keys.filter { it.isAlive &&
                    (it.name.startsWith("recorder-") || it.name.startsWith("continuous-") ||
                        it.name.startsWith("mirror-") || it.name.startsWith("product-")) }
                    .groupingBy { it.name }.eachCount().toSortedMap().forEach { (name, count) -> put(name, count) }
            })
            put("channels", JsonArray(channels(state).map { channel -> buildJsonObject {
                put("id", channel.channelId); put("status", channel.status)
                put("encoded", channel.encodedFrames); put("written", channel.writtenFrames)
                put("files", channel.completedFiles); put("error", channel.errorCode)
            } }))
        }
        File(directory, "resources.jsonl").appendText("$row\n")
    }

    @Test fun stalledMetadataEndsTheIntentWithoutOpeningAnyCamera() {
        val proceed = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val metadata = RecordingChannels::class.java.getDeclaredField("metadata").apply { isAccessible = true }
            .get(null) as Handler
        try {
            metadata.post { entered.countDown(); proceed.await(30, TimeUnit.SECONDS) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            withGroup { session, state ->
                await("the real preparation deadline ended the intent", 25_000) { state.get().lastError == "GROUP_START_TIMEOUT" }
                assertEquals(0L, state.get().cameraGeneration)
                assertTrue(CanaryCameraInterlock.normalIdle())
                proceed.countDown()
                SystemClock.sleep(300)
                assertEquals("Late metadata must not restart a cancelled intent", 0L, state.get().cameraGeneration)
                assertEquals(0L, session.captureEvidence().frames.count)
            }
        } finally { proceed.countDown() }
    }

    @Test fun losingOneRealCameraEndsTheGroupAndPreservesTheHealthyTail() = withGroup { session, state ->
        await("both channels writing", 20_000) { channels(state).size == 2 && channels(state).all { it.writtenFrames > 25 } }
        val id = requireNotNull(state.get().recordingSessionId)
        val control = RecordingChannels::class.java.getDeclaredField("control").apply { isAccessible = true }.get(null) as Handler
        val closed = CountDownLatch(1)
        control.post {
            @Suppress("UNCHECKED_CAST")
            val owners = RecordingChannels::class.java.getDeclaredField("owners").apply { isAccessible = true }
                .get(null) as Map<String, Any>
            val owner = owners.values.single()
            val auxiliary = owner.javaClass.getDeclaredField("session").apply { isAccessible = true }.get(owner) as RecorderSession
            val handler = RecorderSession::class.java.getDeclaredField("cameraHandler").apply { isAccessible = true }.get(auxiliary) as Handler
            handler.post {
                val device = RecorderSession::class.java.getDeclaredField("cameraDevice").apply { isAccessible = true }.get(auxiliary) as CameraDevice
                // Deliberate device loss only on the emulator. The real stale-source/cleanup
                // path must end both members; no fake codec samples or close ACKs are supplied.
                device.close()
                closed.countDown()
            }
        }
        assertTrue(closed.await(3, TimeUnit.SECONDS))
        await("group ended after real auxiliary input stopped", 20_000) {
            state.get().status == RecorderStatus.STOPPED && channels(state).none { it.cleanupPending }
        }
        assertNotNull("The interrupted group is visible", state.get().lastError)
        assertEquals("No independent reopen", 1, EventLogger.events.value.count {
            it.payload["recordingSessionId"] == id && it.eventName == "RECORDER_GROUP_OPEN_REQUEST"
        })
        val primaryFiles = File(context.filesDir, "recordings/segments").listFiles().orEmpty()
            .filter { it.name.endsWith(".json") }.mapNotNull(SegmentSidecarIO::read)
            .filter { it.recordingSessionId == id && it.channelId == RecordingChannelId.SURROUND }
        assertTrue(primaryFiles.isNotEmpty())
        assertTrue(primaryFiles.all { it.result == SegmentSidecar.RESULT_SUCCESS && !it.provisional })
        val encoder = requireNotNull(session.captureEvidence().encoder)
        assertEquals(encoder.frames, encoder.writtenFrames)
        assertEquals(encoder.frames, primaryFiles.sumOf { requireNotNull(it.continuousTimeline).frames })
        val correlation = ProcessExitReport.sessionCorrelation(id)
        await("first group failure persisted before teardown evidence replaces it") {
            RecordingChannelJournal.readFailure(context)["data"]?.jsonObject
                ?.get("sessionCorrelation")?.jsonPrimitive?.content == correlation
        }
        val first = RecordingChannelJournal.readFailure(context)["data"]!!.jsonObject
        val members = first["channels"]!!.jsonArray.map { it.jsonObject }
        assertEquals(setOf("SURROUND", "FRONT_CABIN"), members.map { it["channelId"]!!.jsonPrimitive.content }.toSet())
        val primary = members.single { it["channelId"]!!.jsonPrimitive.content == "SURROUND" }
        assertEquals("Snapshot must precede the group Stop", "RECORDING", primary["status"]!!.jsonPrimitive.content)
        assertTrue(primary["captureEvidenceMatches"]!!.jsonPrimitive.boolean)
        listOf("capturedFrames", "encodedFrames", "writtenFrames").forEach {
            assertTrue("Healthy primary $it must survive auxiliary failure", primary[it]!!.jsonPrimitive.long > 25)
        }
        assertFalse(first.toString().contains(id))
        val exported = Json.parseToJsonElement(RecorderSupportReport.collect(context)).jsonObject
        assertEquals(first, exported["lastGroupFailure"]!!.jsonObject["data"])
        File(context.filesDir, "stability-group/$id").apply { mkdirs() }
            .resolve("first-group-failure.json").writeText(first.toString())
    }

    @Test fun stopBeforeLateOpenCallbacksDoesNotConfigureOrReleaseTheirReservations() {
        val proceed = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val callbacks = CanaryCameraOpenAdapter::class.java.getDeclaredField("callbacks").apply { isAccessible = true }
            .get(null) as Handler
        try {
            callbacks.post { entered.countDown(); proceed.await(10, TimeUnit.SECONDS) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            withGroup { session, state ->
                await("both real opens submitted") { EventLogger.events.value.any {
                    it.payload["recordingSessionId"] == state.get().recordingSessionId && it.eventName == "RECORDER_GROUP_OPEN_REQUEST"
                } }
                SystemClock.sleep(300)
                session.stop()
                await("Stop revoked the intent") { state.get().status in setOf(RecorderStatus.STOPPED, RecorderStatus.FINALIZING) }
                assertFalse("Unanswered native opens must remain reserved", CanaryCameraInterlock.normalIdle())
                assertEquals(0L, session.captureEvidence().frames.count)
                proceed.countDown()
                await("late device callbacks actually closed", 15_000) { CanaryCameraInterlock.normalIdle() }
                val pending = RecorderSession::class.java.getDeclaredField("cameraOpenInFlight").apply { isAccessible = true }
                assertFalse("The completed old open must not leave disposal waiting", pending.getBoolean(session))
                assertFalse(EventLogger.events.value.any { it.payload["recordingSessionId"] == state.get().recordingSessionId &&
                    it.eventName == "RECORDER_GROUP_CONFIGURE" })
            }
        } finally { proceed.countDown() }
    }

    private fun channels(state: AtomicReference<RecorderState>) = RecordingChannels.state.value.filter {
        it.recordingSessionId == state.get().recordingSessionId
    }

    @Test fun twoMediaStoreChannelsSaveAcrossTwoMinuteBoundaries() = withGroup(
        segmentSeconds = 60, storage = RecordingStoragePreference.USB_PREFERRED,
    ) { session, state ->
        val target = UsbExportVolumeResolver.mountedTargets(context).single()
        await("both removable-volume writers", 20_000) {
            channels(state).size == 2 && channels(state).all { it.storageKind == "USB_MEDIASTORE" && it.writtenFrames > 25 }
        }
        // Removal hints are not proof: an unrelated path and a still-mounted target must stay active.
        val before = channels(state).sumOf { it.writtenFrames }
        session.onRemovableStorageUnavailable(Intent.ACTION_MEDIA_REMOVED, "/storage/unrelated-development-volume")
        session.onRemovableStorageUnavailable(Intent.ACTION_MEDIA_REMOVED, target.directoryPath)
        await("mounted target still writing") { channels(state).sumOf { it.writtenFrames } > before + 20 }
        await("two natural USB minute boundaries", 140_000) { channels(state).all { it.completedFiles >= 2 } }
        SystemClock.sleep(700)
        session.stop()
        verifyUsbFiles(state, 3, "minute")
    }

    @Test fun stopWhileUsbAdmissionWaitsForVolumeLockKeepsTheRecordedTail() = withGroup(
        storage = RecordingStoragePreference.USB_PREFERRED,
    ) { session, state ->
        val target = UsbExportVolumeResolver.mountedTargets(context).single()
        await("both removable-volume writers", 20_000) {
            channels(state).size == 2 && channels(state).all { it.storageKind == "USB_MEDIASTORE" && it.writtenFrames > 25 }
        }
        val locked = CountDownLatch(1); val unlock = CountDownLatch(1)
        val holder = Thread {
            UsbMutationCoordinator.withTarget(target.storageUuid) { locked.countDown(); unlock.await(8, TimeUnit.SECONDS) }
        }.apply { start() }
        try {
            assertTrue(locked.await(2, TimeUnit.SECONDS))
            val entered = CountDownLatch(1)
            val handler = RecorderSession::class.java.getDeclaredField("cameraHandler").apply { isAccessible = true }.get(session) as Handler
            handler.post {
                val encoder = RecorderSession::class.java.getDeclaredField("recordingEncoder").apply { isAccessible = true }.get(session)
                RecorderSession::class.java.getDeclaredMethod("rotateContinuousFile", ContinuousVideoRecorder::class.java)
                    .apply { isAccessible = true }.invoke(session, encoder)
                entered.countDown()
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            // The real volume mutation lock keeps admission queued while Camera2 keeps writing.
            SystemClock.sleep(300)
            session.stop()
            SystemClock.sleep(300)
        } finally { unlock.countDown(); holder.join(3_000); assertFalse(holder.isAlive) }
        verifyUsbFiles(state, 1, "stop-during-admission")
    }

    @Test fun unusedClosedMediaStoreDestinationIsRemovedEvenBeforeProviderKnowsItsSize() {
        assumeTrue("Disposable emulator only", Build.HARDWARE == "ranchu")
        val target = UsbExportVolumeResolver.mountedTargets(context).single()
        val id = java.util.UUID.randomUUID().toString()
        val journal = UsbRecordingRecoveryJournal(context)
        val sink = UsbMediaStoreRecordingOutputSink(context, target, id, RecordingMode.NORMAL,
            File(context.filesDir, "recordings/usb-tokens"), journal)
        val output = sink.openSegment(1, CameraFormatProfile(ProfileSize(640, 480), 2_000_000),
            System.currentTimeMillis()) as UsbMediaStoreRecordingOutputHandle
        val uri = android.net.Uri.parse(output.pendingVideo.itemUri)
        val backend = UsbMediaStoreBackend(context)
        assertNull("Exercise a real unindexed pending row", backend.metadata(uri)!!.sizeBytes)
        output.close()
        UsbPendingRecordingRecoveryEngine.recoverMounted(context, output.pendingVideo.operationId)
        assertTrue("Generic recovery must keep its conservative identity check", journal.entries().any { it.operationId == output.pendingVideo.operationId })
        val session = RecorderSession(context, {}, {}, externallyManagedPresence = true)
        val released = CountDownLatch(1)
        try {
            val original = journal.entries().single { it.operationId == output.pendingVideo.operationId }
            journal.put(original.copy(assets = original.assets.map { it.copy(requestedName = "unrelated.mp4") }))
            RecorderSession::class.java.getDeclaredMethod("discardUnusedContinuousOutput", RecordingOutputHandle::class.java)
                .apply { isAccessible = true }.invoke(session, output)
            assertNotNull("Unused status must never bypass file identity", backend.metadata(uri))
            assertTrue(journal.entries().any { it.operationId == output.pendingVideo.operationId })
            journal.put(original)
            RecorderSession::class.java.getDeclaredMethod("discardUnusedContinuousOutput", RecordingOutputHandle::class.java)
                .apply { isAccessible = true }.invoke(session, output)
            assertTrue(output.nativeReleaseConfirmed)
            assertTrue("The exact unused output must not accumulate in the recovery journal", journal.entries().none { it.operationId == output.pendingVideo.operationId })
            assertNull(backend.metadata(uri))
            assertFalse(output.localWorkingFile.exists())
        } finally {
            output.close()
            session.releaseForModeHandoff { released.countDown() }
            assertTrue(released.await(5, TimeUnit.SECONDS))
        }
    }

    private fun verifyUsbFiles(state: AtomicReference<RecorderState>, perChannel: Int, label: String) {
        val id = requireNotNull(state.get().recordingSessionId)
        val target = UsbExportVolumeResolver.mountedTargets(context).single()
        val catalog = UsbSegmentCatalog(context)
        await("all camera/encoder cleanup acknowledged", 20_000) {
            channels(state).all { it.status == "STOPPED" && !it.cleanupPending && !it.cleanupUnconfirmed }
        }
        await("all USB manifests published", 20_000) {
            catalog.snapshot(target).segments.count { it.manifestData.recordingSessionId == id } == perChannel * 2
        }
        assertNull(state.get().lastError)
        val bundles = catalog.snapshot(target).segments.filter { it.manifestData.recordingSessionId == id }
        val evidence = File(context.filesDir, "stability-usb/$label-$id").apply { mkdirs() }
        val sidecars = bundles.map { bundle ->
            val document = requireNotNull(context.contentResolver.openInputStream(bundle.sidecar.uri)).use { it.readBytes() }
            val sidecar = SegmentSidecarIO.json.decodeFromString(SegmentSidecar.serializer(), document.toString(Charsets.UTF_8))
            val video = File(evidence, requireNotNull(bundle.video.displayName))
            requireNotNull(context.contentResolver.openInputStream(bundle.video.uri)).use { input -> video.outputStream().use(input::copyTo) }
            File(evidence, video.name + ".json").writeBytes(document)
            File(evidence, video.name + ".manifest.json").writeText(SegmentSidecarIO.json.encodeToString(bundle.manifestData))
            assertEquals(SegmentSidecar.RESULT_SUCCESS, sidecar.result)
            assertFalse(sidecar.provisional)
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, bundle.video.uri, null)
                assertEquals(1, extractor.trackCount); extractor.selectTrack(0)
                assertEquals(0L, extractor.sampleTime)
                assertTrue(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                var count = 0L; var last = -1L
                while (extractor.sampleTime >= 0) { assertTrue(extractor.sampleTime > last); last = extractor.sampleTime; count++; extractor.advance() }
                assertEquals(requireNotNull(sidecar.continuousTimeline).frames, count)
            } finally { extractor.release() }
            sidecar
        }
        channels(state).forEach { channel ->
            assertEquals("USB_MEDIASTORE", channel.storageKind)
            assertEquals(perChannel, sidecars.count { it.channelId == channel.channelId })
            assertEquals(channel.encodedFrames, channel.writtenFrames)
            assertEquals(channel.writtenFrames, sidecars.filter { it.channelId == channel.channelId }.sumOf { it.continuousTimeline!!.frames })
        }
        assertTrue(UsbRecordingRecoveryJournal(context).entries().none { it.recordingSessionId == id })
        File(evidence, "health.json").writeText(SegmentSidecarIO.json.encodeToString(channels(state)))
    }

    @Test fun nativeOpenDeadlineKeepsTheFenceUntilLateDeviceClose() {
        val proceed = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val callbacks = CanaryCameraOpenAdapter::class.java.getDeclaredField("callbacks").apply { isAccessible = true }
            .get(null) as Handler
        try {
            callbacks.post { entered.countDown(); proceed.await(25, TimeUnit.SECONDS) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            withGroup { session, state ->
                await("actual native-open deadline", 18_000) { state.get().lastError == "CAMERA_OPEN_TIMEOUT" }
                assertFalse("A native timeout is not an onClosed acknowledgement", CanaryCameraInterlock.normalIdle())
                val pending = RecorderSession::class.java.getDeclaredField("cameraOpenInFlight").apply { isAccessible = true }
                assertTrue("Pending native open must survive the deadline", pending.getBoolean(session))
                assertEquals(0L, session.captureEvidence().frames.count)
                proceed.countDown()
                await("timed-out open eventually acknowledged real device close", 10_000) {
                    CanaryCameraInterlock.normalIdle() && !pending.getBoolean(session)
                }
                assertFalse(EventLogger.events.value.any { it.payload["recordingSessionId"] == state.get().recordingSessionId &&
                    it.eventName == "RECORDER_GROUP_CONFIGURE" })
            }
        } finally { proceed.countDown() }
    }

    @Test fun confirmedStorageLossMarksEveryMemberBeforeBlockedCameraWorkerCanStop() = storageLoss(false)

    @Test fun confirmedStorageLossStillReachesTheGroupAfterManualStop() = storageLoss(true)

    private fun storageLoss(stopFirst: Boolean) {
        assumeTrue("Disposable emulator only", Build.HARDWARE == "ranchu")
        // Dedicated emulator with a 16 GiB removable SD image. This uses real MediaStore
        // descriptors, Camera2, codecs and muxers; only the confirmed-removal signal is injected.
        assertTrue("Removable development volume required", UsbExportVolumeResolver.mountedTargets(context)
            .single().freeBytes!! > (11L shl 30))
        withGroup(storage = RecordingStoragePreference.USB_PREFERRED) { primary, state ->
            await("both channels writing to removable MediaStore", 20_000) {
                channels(state).size == 2 && channels(state).all { it.writtenFrames > 25 && it.storageKind == "USB_MEDIASTORE" }
            }
            val group = RecordingChannels::class.java.getDeclaredField("startGroup").apply { isAccessible = true }.get(null)
            @Suppress("UNCHECKED_CAST")
            val members = group.javaClass.getDeclaredField("members").apply { isAccessible = true }.get(group) as Map<String, Any>
            val sessions = members.values.map { it.javaClass.getDeclaredField("session").apply { isAccessible = true }.get(it) as RecorderSession }
            val auxiliary = sessions.single { it !== primary }
            val encoderField = RecorderSession::class.java.getDeclaredField("recordingEncoder").apply { isAccessible = true }
            val encoders = sessions.map { encoderField.get(it) as ContinuousVideoRecorder }
            val handlerField = RecorderSession::class.java.getDeclaredField("cameraHandler").apply { isAccessible = true }
            val entered = CountDownLatch(1); val proceed = CountDownLatch(1)
            try {
                (handlerField.get(auxiliary) as Handler).post { entered.countDown(); proceed.await(10, TimeUnit.SECONDS) }
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                val loss = RecorderSession::class.java.getDeclaredMethod("abandonUsbSegmentWithoutStop",
                    UsbMediaStoreRecordingOutputHandle::class.java, String::class.java, String::class.java).apply { isAccessible = true }
                val primaryHandler = handlerField.get(primary) as Handler
                val primaryBlocked = CountDownLatch(1); val resumePrimary = CountDownLatch(1)
                if (stopFirst) {
                    primaryHandler.post { primaryBlocked.countDown(); resumePrimary.await(3, TimeUnit.SECONDS) }
                    assertTrue(primaryBlocked.await(2, TimeUnit.SECONDS))
                    primary.stop()
                }
                primaryHandler.post {
                    val output = RecorderSession::class.java.getDeclaredField("currentOutput").apply { isAccessible = true }.get(primary)
                    loss.invoke(primary, output, "USB_VOLUME_UNAVAILABLE:DEVELOPMENT_INJECTION", "DEVELOPMENT_INJECTION")
                }
                resumePrimary.countDown()
                await("the group has received the loss", 8_000) { EventLogger.events.value.any {
                    it.eventName == "RECORDER_GROUP_STOP" && it.payload["recordingSessionId"] == state.get().recordingSessionId
                } }
                val abandoned = ContinuousVideoRecorder::class.java.getDeclaredField("abandoned").apply { isAccessible = true }
                await("Every same-volume encoder must be marked lost before the blocked worker can finalize", 2_000) {
                    encoders.all { abandoned.getBoolean(it) }
                }
            } finally { proceed.countDown() }
            await("both loss cleanups acknowledged", 20_000) { channels(state).none { it.cleanupPending } && CanaryCameraInterlock.normalIdle() }
            assertTrue("No silent fallback to internal storage", channels(state).all { it.storageKind == "USB_MEDIASTORE" })
            val id = state.get().recordingSessionId
            val closes = EventLogger.events.value.filter { it.eventName == "RECORDER_CLOSE_SETTLED" && it.payload["recordingSessionId"] == id }
            assertEquals(2, closes.size)
            assertTrue(closes.all { it.payload["outputLost"] == "true" && it.payload["safeToContinue"] == "true" })
            assertEquals(2, UsbRecordingRecoveryJournal(context).entries().count { it.recordingSessionId == id })
            assertTrue(UsbSegmentCatalog(context).snapshot(UsbExportVolumeResolver.mountedTargets(context).single()).segments.none {
                it.manifestData.recordingSessionId == id
            })
        }
    }

    private fun withGroup(segmentSeconds: Int = 10, storage: RecordingStoragePreference = RecordingStoragePreference.INTERNAL_ONLY,
                          previewRoundTrip: Boolean = false,
                          closeTrial: TerminalCloseTrial? = null,
                          test: (RecorderSession, AtomicReference<RecorderState>) -> Unit) {
        assumeTrue("Disposable emulator only", Build.HARDWARE == "ranchu")
        assumeTrue("Production storage reserve", context.filesDir.usableSpace > (11L shl 30))
        val cameras = context.getSystemService(CameraManager::class.java).cameraIdList
        assertEquals("Two emulated Camera2 devices required", 2, cameras.size)
        val settings = SettingsStore.get(context)
        val previous = settings.cameraMapping(RecordingSourceRole.CABIN)
        val previousReserve = settings.minFreeBytes
        // Startup uses the frozen config, while final-file retention reads SettingsStore.
        // Keep the emulator's two reserve inputs consistent, then restore its prior choice.
        settings.setMinFreeBytes(10L shl 30)
        settings.setCameraMapping(RecordingSourceRole.CABIN, cameras[1])
        // The emulator cannot expose the car's 1280x5140 sensor. SURROUND selects the
        // real product group route; its test input is explicitly a 640x480 emulated scene.
        val config = RecorderConfig(SessionSourceSnapshot(RecordingSourceRole.SURROUND, cameras[0],
            CameraFormatProfile(ProfileSize(640, 480), 2_000_000), RecordingLayoutKind.FOUR_LANE_V1),
            segmentSeconds = segmentSeconds, storageLimitBytes = 5L shl 30, minFreeBytes = 10L shl 30,
            storagePreference = storage, mirrorPreviewEnabled = true,
            auxiliaryChannels = listOf(RecordingSourceRole.CABIN))
        val activity = instrumentation.startActivitySync(Intent(context, MirrorSleepTestHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MirrorSleepTestHostActivity
        val state = AtomicReference(RecorderState())
        val released = CountDownLatch(1)
        var mirror: MirrorPreviewController? = null
        lateinit var session: RecorderSession
        session = RecorderSession(context, { value ->
            state.set(value)
            if (value.recordingSessionId != null) RecordingChannels.follow(context, config, value, session)
        }, {}, externallyManagedPresence = true, selectDirectCodec = { _, cfg ->
            val codec = requireNotNull(MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(ContinuousVideoRecorder.format(cfg)))
            ContinuousEncoderSelection("DEVELOPMENT_SOFTWARE_CODEC", codec, true)
        })
        try {
            assertTrue(CanaryCameraInterlock.normalIdle())
            RecordingChannels.start(context, config, session, closeTrial = closeTrial)
            await("group identity") { state.get().recordingSessionId != null }
            if (previewRoundTrip) {
                await("actual recording is ready for its production GL preview", 20_000) {
                    state.get().videoFlowConfirmed && channels(state).size == 2 && channels(state).all { it.writtenFrames > 20 }
                }
                // Fixture connects the real session's callbacks to the real presentation;
                // it supplies no fake frames, fake release ACKs or alternate render path.
                val observedState = state
                val source = object : MirrorCameraSource {
                    override val state get() = observedState.get()
                    override fun enable(enabled: Boolean, runId: String, generation: Long) =
                        session.setPreviewOutputEnabled(enabled, runId, generation)
                    override fun replace(surface: Surface, runId: String, generation: Long, released: () -> Unit) =
                        session.replacePreviewSurface(surface, runId, generation, released)
                    override fun stop() = session.stop()
                    override fun bookmark() = Unit
                }
                instrumentation.runOnMainSync {
                    settings.setMirrorPreviewEnabled(true); settings.setMirrorRearLane(1)
                    activity.setContentView(MirrorHomeHost(activity))
                    mirror = MirrorPreviewController(context, config, source,
                        requireNotNull(state.get().recordingSessionId), state.get().cameraGeneration)
                }
                await("actual GL mirror displays frames while both cameras record", 15_000) {
                    MirrorPreviewRuntime.evidence.reason == "LIVE" && MirrorPreviewRuntime.evidence.frames > 3
                }
            }
            test(session, state)
            if (previewRoundTrip) {
                val recordingFrames = MirrorPreviewRuntime.evidence.frames
                assertTrue("Preview continued through the minute boundaries", recordingFrames > 100)
                instrumentation.runOnMainSync { mirror?.close() }
                // In production onStopped() destroys CameraRecordingService and
                // onDestroy() requests session.release(). A raw RecorderSession
                // fixture must request that same disposal before a new owner starts.
                session.releaseForModeHandoff { released.countDown() }
                assertTrue("Recorder disposal acknowledged before preview handoff", released.await(20, TimeUnit.SECONDS))
                await("recording camera and GL resources really released before preview", 20_000) {
                    CanaryCameraInterlock.normalIdle() && CaptureCleanupRuntime.pendingOwners.value == 0 &&
                        mirror?.cleanupConfirmed() == true && MirrorGlPreview.isIdle()
                }
                // Reopen the same actual Camera2 device via the complete standalone
                // service path; CABIN selects a valid ordinary emulator preview profile.
                settings.setCameraMapping(RecordingSourceRole.CABIN, config.cameraId)
                instrumentation.runOnMainSync {
                    MirrorReturnSettings(context).save(MirrorReturnMode.LOGO)
                    MirrorPresentation(context).videoVisible = true
                    context.startForegroundService(Intent(context, FloatingMirrorService::class.java)
                        .setAction("openavm.mirror.FOLLOW"))
                }
                await("floating controls ready") { FloatingMirrorService.active() }
                instrumentation.runOnMainSync { assertTrue(FloatingMirrorService.selectPreviewSource(RecordingSourceRole.CABIN)) }
                await("same device returns real displayed frames after both recorders stopped", 15_000) {
                    StandaloneMirrorService.isPreviewing() && !FloatingMirrorService.state.value.busy &&
                        MirrorPreviewRuntime.evidence.reason == "LIVE" && MirrorPreviewRuntime.evidence.frames > 3
                }
                val returned = MirrorPreviewRuntime.evidence
                File(context.filesDir, "stability-group/${state.get().recordingSessionId}/preview-round-trip.json")
                    .writeText("""{"recordingPreviewFrames":$recordingFrames,"returnedPreviewFrames":${returned.frames},"returnedBackend":"${returned.gl.backend}","sameCameraId":"${config.cameraId}","returnedFrameAgeMs":${returned.lastFrameAgeMs},"vehicleHAL":false}""")
                appendResources(File(context.filesDir, "stability-group/${state.get().recordingSessionId}"), "returned_preview", null, session, state)
            }
        } finally {
            if (previewRoundTrip) instrumentation.runOnMainSync { mirror?.close(); FloatingMirrorService.close() }
            session.stop()
            RecordingChannels.stop(state.get().recordingSessionId)
            if (released.count != 0L) session.releaseForModeHandoff { released.countDown() }
            assertTrue("Primary disposal acknowledged; ${state.get()}", released.await(20, TimeUnit.SECONDS))
            await("every group owner released", 20_000) {
                CanaryCameraInterlock.normalIdle() && CaptureCleanupRuntime.pendingOwners.value == 0 &&
                    channels(state).none { it.cleanupPending || it.cleanupUnconfirmed } &&
                    (!previewRoundTrip || !StandaloneMirrorService.isRunning() && MirrorGlPreview.isIdle())
            }
            settings.setCameraMapping(RecordingSourceRole.CABIN, previous)
            settings.setMinFreeBytes(previousReserve)
            instrumentation.runOnMainSync { activity.finish() }
            val evidence = File(context.filesDir, "stability-group/${state.get().recordingSessionId}")
            if (evidence.isDirectory) appendResources(evidence, "disposed", null, session, state)
        }
    }

    @Test fun primaryDoesNotOpenBeforeTheRequestedCameraSetIsPrepared() {
        assumeTrue("Disposable emulator only", Build.HARDWARE == "ranchu")
        val camera = context.getSystemService(CameraManager::class.java).cameraIdList.first()
        val config = RecorderConfig(SessionSourceSnapshot(RecordingSourceRole.SURROUND, camera,
            CameraFormatProfile(ProfileSize(640, 480), 2_000_000), RecordingLayoutKind.SINGLE_V1),
            segmentSeconds = 10, storageLimitBytes = 5L shl 30, minFreeBytes = 10L shl 30,
            storagePreference = RecordingStoragePreference.INTERNAL_ONLY, auxiliaryChannels = listOf(RecordingSourceRole.CABIN))
        val activity = instrumentation.startActivitySync(Intent(context, MirrorSleepTestHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MirrorSleepTestHostActivity
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val control = RecordingChannels::class.java.getDeclaredField("control").apply { isAccessible = true }
            .get(null) as Handler
        try {
            assertTrue(CanaryCameraInterlock.normalIdle())
            control.post { entered.countDown(); check(proceed.await(10, TimeUnit.SECONDS)) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { CameraRecordingService.start(context, config) }
            await("primary preparation") { CameraRecordingService.state.value.recordingSessionId != null }
            SystemClock.sleep(1_000)
            assertEquals("The primary must not configure before the requested auxiliary set is frozen", 0L,
                CameraRecordingService.state.value.cameraGeneration)
            assertTrue("No native camera reservation before group preparation", CanaryCameraInterlock.normalIdle())
        } finally {
            instrumentation.runOnMainSync { CameraRecordingService.stop(context) }
            proceed.countDown()
            await("all native cleanup", 20_000) { !CameraRecordingService.isRunning() && CanaryCameraInterlock.normalIdle() &&
                CaptureCleanupRuntime.pendingOwners.value == 0 }
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun await(message: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeoutMs
        while (!condition() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(25)
        assertTrue("$message; ${CameraRecordingService.state.value}", condition())
    }
}
