package com.dante.zeekrcapabilitylab.player

import android.os.Bundle
import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.dante.zeekrcapabilitylab.event.EventLogger
import com.dante.zeekrcapabilitylab.mirror.MirrorSleepTestHostActivity
import com.dante.zeekrcapabilitylab.probe.camera.CameraFormatProfile
import com.dante.zeekrcapabilitylab.probe.camera.ProfileSize
import com.dante.zeekrcapabilitylab.product.AppLanguage
import com.dante.zeekrcapabilitylab.product.AppLanguageMode
import com.dante.zeekrcapabilitylab.service.recorder.*
import com.dante.zeekrcapabilitylab.ui.product.SessionReviewDialog
import com.dante.zeekrcapabilitylab.ui.theme.ZeekrCapabilityLabTheme
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.UUID

/** Three real playback decoders, synthetic footage, no camera or vehicle access. */
class SessionReviewUiAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<MirrorSleepTestHostActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var directory: File
    private lateinit var files: List<File>
    private var visible by mutableStateOf(true)
    private var generation by mutableIntStateOf(0)
    private var eventSequence = 0L
    private val cache get() = RecordingThumbnailCache(File(context.cacheDir, "recording-covers"))
    private fun events(name: String) = EventLogger.events.value.filter { it.sequence > eventSequence && it.eventName == name }

    @Before fun prepare() {
        assumeTrue(android.os.Build.HARDWARE == "ranchu")
        AppLanguage.setMode(context, AppLanguageMode.ENGLISH)
        val session = UUID.randomUUID().toString()
        directory = File(context.cacheDir, "playback-ui/$session").apply { mkdirs() }
        files = (0 until 12).flatMap { index -> RecordingSourceRole.entries.map { role ->
            val channel = when (role) { RecordingSourceRole.SURROUND -> "SURROUND"; RecordingSourceRole.CABIN -> "FRONT_CABIN"; RecordingSourceRole.IR -> "REAR_CABIN" }
            val file = File(directory, "$channel-$index.mp4")
            val surround = role == RecordingSourceRole.SURROUND
            val asset = if (surround) "session-surround.mp4" else "session-single.mp4"
            instrumentation.context.assets.open("playback/$asset").use { input -> file.outputStream().use(input::copyTo) }
            val start = 1_000L + index * 10_000L
            val size = if (surround) ProfileSize(1280, 320) else ProfileSize(640, 480)
            SegmentSidecarIO.writeAtomic(file, SegmentSidecar(file = file.absolutePath, cameraId = "synthetic",
                profile = CameraFormatProfile(size, 1_000_000), sourceRole = role,
                layoutKind = if (surround) RecordingLayoutKind.FOUR_LANE_V1 else RecordingLayoutKind.SINGLE_V1,
                segmentSeconds = 10, segmentNumber = index + 1, processStartId = "fixture", result = "SUCCESS",
                recordingSessionId = session, channelId = channel, sessionStartedElapsedMs = 1_000L,
                startedAtEpochMs = 1_790_000_000_000L + index * 10_000, startedAtElapsedRealtimeMs = start,
                stoppedAtElapsedRealtimeMs = start + 10_000,
                actualTrack = ActualTrackInfo(size.width, size.height, durationMs = 10_000)))
            file
        } }
        eventSequence = EventLogger.events.value.lastOrNull()?.sequence ?: 0
    }

    @After fun clean() {
        compose.runOnIdle { visible = false }
        compose.waitForIdle()
        if (::files.isInitialized) {
            files.forEach { assertFalse("Playback pin leaked: ${it.name}", PlaybackPinRegistry.isPinned(it)); cache.remove(it) }
        }
        if (::directory.isInitialized) directory.deleteRecursively()
        AppLanguage.setMode(context, AppLanguageMode.SYSTEM)
    }

    @Test fun continuousSegmentBoundaryKeepsAllThreeVideoSurfacesAndPlayers() {
        compose.setContent { ZeekrCapabilityLabTheme {
            if (visible) SessionReviewDialog(files, files.first(), onDismiss = { visible = false })
        } }
        compose.waitUntil(25_000) { events("RECORDER_PLAYBACK_FIRST_FRAME").size >= 3 }
        fun textures(): List<android.view.TextureView> {
            fun collect(view: android.view.View): List<android.view.TextureView> =
                if (view is android.view.TextureView) listOf(view)
                else if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { collect(view.getChildAt(it)) }
                else emptyList()
            var result = emptyList<android.view.TextureView>()
            compose.runOnIdle { result = android.view.inspector.WindowInspector.getGlobalWindowViews().flatMap(::collect).distinct() }
            return result
        }
        val before = textures()
        assertEquals(3, before.size)
        // A direct clip seek exercises the same selection boundary as the session clock.
        compose.onNodeWithContentDescription("Play clip SURROUND-1.mp4").performClick()
        compose.waitForIdle()
        SystemClock.sleep(2_000)
        assertEquals("Every channel must retain its native TextureView across minute boundaries", before, textures())
        assertEquals("One player per channel, not one per file", 3, events("RECORDER_PLAYBACK_FIRST_FRAME").size)
        val sampleUntil = SystemClock.elapsedRealtime() + 11_000
        var samples = 0
        while (SystemClock.elapsedRealtime() < sampleUntil) {
            compose.runOnIdle { before.forEach { texture ->
                val bitmap = requireNotNull(texture.getBitmap(32, 32))
                try {
                    val pixels = IntArray(32 * 32); bitmap.getPixels(pixels, 0, 32, 0, 0, 32, 32)
                    assertTrue("Synthetic footage must not turn black at a file boundary", pixels.count {
                        android.graphics.Color.red(it) + android.graphics.Color.green(it) + android.graphics.Color.blue(it) > 60
                    } > 100)
                } finally { bitmap.recycle() }
            } }
            samples++; SystemClock.sleep(100)
        }
        assertTrue("All streams crossed the automatic boundary", events("RECORDER_PLAYBACK_SEGMENT").count { it.payload["index"] == "2" } >= 3)
        assertEquals("Automatic continuous playback also keeps the surfaces", before, textures())
        assertEquals(3, events("RECORDER_PLAYBACK_FIRST_FRAME").size)
        assertTrue(events("RECORDER_PLAYBACK_ERROR").isEmpty())
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nPLAYBACK_BOUNDARY nonBlackSamples=$samples surfaces=3 players=3\n") })
    }

    @Test fun threeStreamsStartThenVisibleCoversLoadAndClipSeekStillWorks() {
        val start = SystemClock.elapsedRealtime()
        compose.setContent { ZeekrCapabilityLabTheme {
            if (visible) key(generation) { SessionReviewDialog(files, files.first(), onDismiss = { visible = false }) }
        } }
        compose.waitUntil(25_000) { events("RECORDER_PLAYBACK_FIRST_FRAME").size >= 3 }
        val readyMs = SystemClock.elapsedRealtime() - start
        compose.onNodeWithText("Session playback").assertIsDisplayed()
        assertTrue(events("RECORDER_PLAYBACK_ERROR").isEmpty())
        val primary = files.filter { it.name.startsWith("SURROUND") }
        fun covers() = primary.count { cache.loadCached(it, RecordingLayoutKind.FOUR_LANE_V1)?.also { it.recycle() } != null }
        compose.waitUntil(15_000) { covers() > 0 }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Recording thumbnail").fetchSemanticsNodes().isNotEmpty() }
        val visibleCovers = covers()
        assertTrue("Invisible rows should not all be decoded", visibleCovers < 12)
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(context.getExternalFilesDir(null), "session-startup-qa.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        val beforeSeek = events("RECORDER_PLAYBACK_SEGMENT").size
        compose.onNodeWithContentDescription("Play clip SURROUND-1.mp4").performClick()
        compose.waitUntil(20_000) { events("RECORDER_PLAYBACK_SEGMENT").size >= beforeSeek + 3 }
        assertTrue(events("RECORDER_PLAYBACK_ERROR").isEmpty())
        compose.onNodeWithText("Close").performClick()
        compose.waitForIdle()
        files.forEach { assertFalse(PlaybackPinRegistry.isPinned(it)) }
        // Re-enter cached playback and close again while its background row jobs are active.
        val beforeReopen = events("RECORDER_PLAYBACK_FIRST_FRAME").size
        compose.runOnIdle { generation++; visible = true }
        compose.waitUntil(20_000) { events("RECORDER_PLAYBACK_FIRST_FRAME").size >= beforeReopen + 3 }
        assertTrue(events("RECORDER_PLAYBACK_ERROR").isEmpty())
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "\nSESSION_UI allThreeFramesMs=$readyMs visibleCovers=$visibleCovers seekAndReopen=PASS\n")
        })
    }
}
