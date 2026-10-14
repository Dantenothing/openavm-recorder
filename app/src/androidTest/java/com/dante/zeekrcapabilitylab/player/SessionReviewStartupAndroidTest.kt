package com.dante.zeekrcapabilitylab.player

import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.dante.zeekrcapabilitylab.service.recorder.*
import com.dante.zeekrcapabilitylab.ui.product.loadSessionReview
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.UUID

/** Local MP4s only. Startup must not open twelve thumbnail decoders before video can prepare. */
class SessionReviewStartupAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var directory: File
    private lateinit var files: List<File>
    private val cache get() = RecordingThumbnailCache(File(context.cacheDir, "recording-covers"))

    @Before fun prepare() {
        assumeTrue(android.os.Build.HARDWARE == "ranchu")
        val session = UUID.randomUUID().toString()
        directory = File(context.cacheDir, "playback-startup/$session").apply { mkdirs() }
        val original = SegmentSidecarIO.json.decodeFromString<SegmentSidecar>(
            instrumentation.context.assets.open("playback/stability-upgrade.mp4.json").bufferedReader().use { it.readText() })
        files = (0 until 12).map { index ->
            val file = File(directory, "clip-$index.mp4")
            instrumentation.context.assets.open("playback/stability-upgrade.mp4").use { input ->
                file.outputStream().use(input::copyTo)
            }
            val start = 1_000L + index * 2_143L
            SegmentSidecarIO.writeAtomic(file, original.copy(file = file.absolutePath,
                recordingSessionId = session, sessionStartedElapsedMs = 1_000L,
                segmentNumber = index + 1, startedAtElapsedRealtimeMs = start,
                stoppedAtElapsedRealtimeMs = start + 2_143L))
            file
        }
    }

    @After fun clean() {
        if (::files.isInitialized) files.forEach(cache::remove)
        if (::directory.isInitialized) directory.deleteRecursively()
    }

    @Test fun openingSessionDoesNotWaitForUnseenThumbnailExtraction() = runBlocking {
        val start = SystemClock.elapsedRealtime()
        val first = requireNotNull(loadSessionReview(context, files).data)
        val coldMs = SystemClock.elapsedRealtime() - start
        val cached = files.count { cache.loadCached(it, RecordingLayoutKind.SINGLE_V1)?.also { it.recycle() } != null }
        val again = SystemClock.elapsedRealtime()
        val second = requireNotNull(loadSessionReview(context, files).data)
        val warmMs = SystemClock.elapsedRealtime() - again
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "\nSESSION_STARTUP coldMs=$coldMs warmMs=$warmMs generatedCovers=$cached files=${files.size}\n")
        })
        assertEquals(12, first.entries.size)
        assertEquals(first.timeline.durationMs, second.timeline.durationMs)
        assertEquals(25_716L, first.timeline.durationMs)
        assertEquals("Opening a session must not decode unrequested thumbnail frames", 0, cached)
    }
}
