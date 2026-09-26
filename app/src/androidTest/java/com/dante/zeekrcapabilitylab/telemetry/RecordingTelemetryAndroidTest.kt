package com.dante.zeekrcapabilitylab.telemetry

import android.os.Build
import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger

/** Deterministic provider/lifecycle tests. Synthetic samples do NOT establish vehicle GPS support. */
class RecordingTelemetryAndroidTest {
    private lateinit var directory: File
    private val now = AtomicLong(1_000)
    private val enabled = AtomicBoolean(true)
    private val state = AtomicReference(TelemetryCaptureState())
    private val emissions = AtomicInteger()
    private val source = FakeSource()
    private var capture: RecordingTelemetryCapture? = null
    private val id = "00000000-0000-4000-8000-000000000002"

    @Before fun prepare() {
        assumeTrue(Build.HARDWARE == "ranchu")
        directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "telemetry-test-" + UUID.randomUUID()).apply { mkdirs() }
    }
    @After fun cleanup() {
        closeCapture()
        directory.deleteRecursively()
    }
    private fun closeCapture() {
        capture?.close()
        await { !Thread.getAllStackTraces().keys.any { it.name == "RecordingLocation" && it.isAlive } }
    }
    private fun start(store: TelemetryStore = TelemetryStore(directory), foreground: Boolean = true) {
        capture = RecordingTelemetryCapture(source, store, enabled::get, now::get,
            wall = { 1_790_000_000_000 }, publish = { state.set(it); emissions.incrementAndGet() })
        capture!!.follow(id, true, foreground)
    }
    private fun point(at: Long) = TelemetryFix(at, 1_790_000_000_000 + at, -34.9, 138.6, 5f, "gps", 10f)
    private fun await(block: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000
        while (!block() && System.nanoTime() < deadline) Thread.sleep(15)
        assertTrue("Timed out; status=" + state.get(), block())
    }
    private fun ready() = await { source.callbacks.size == 1 && state.get().status == TelemetryStatus.WAITING }

    @Test fun disabledStartsNoProviderAndCreatesNoFile() {
        enabled.set(false); start()
        await { emissions.get() > 0 }
        assertEquals(TelemetryStatus.DISABLED, state.get().status)
        assertEquals(0, source.callbacks.size)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }
    @Test fun permissionAndProviderFailuresDoNotSubscribeOrWrite() {
        for (reason in listOf(TelemetryStatus.NO_PERMISSION, TelemetryStatus.LOCATION_OFF, TelemetryStatus.NO_PROVIDER)) {
            source.problem = reason; state.set(TelemetryCaptureState()); start()
            await { state.get().status == reason }
            assertTrue(directory.listFiles().orEmpty().isEmpty()); assertEquals(0, source.callbacks.size)
            closeCapture()
        }
    }
    @Test fun foregroundRejectionIsAnExplicitState() {
        start(foreground = false)
        await { state.get().status == TelemetryStatus.FOREGROUND_REQUIRED }
        assertEquals(0, source.callbacks.size)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }
    @Test fun stopResumeAndLateCallbacksCannotContaminateTheNextTrack() {
        start(); ready()
        source.callbacks[0].receive(point(1_000), false)
        await { state.get().accepted == 1 }
        capture!!.follow(id, false, true)
        await { state.get().status == TelemetryStatus.IDLE && source.callbacks[0].closed.get() }
        now.set(3_000); capture!!.follow(id, true, true)
        await { source.callbacks.size == 2 }
        source.callbacks[0].receive(point(3_000), false) // old listener is intentionally misbehaving
        now.set(4_000); source.callbacks[1].receive(point(4_000), false)
        await { state.get().accepted == 1 && state.get().lastElapsedMs == 4_000L }
        capture!!.follow(id, false, true)
        await { source.callbacks[1].closed.get() }
        val points = TelemetryStore(directory).load(id)!!.points
        assertEquals(listOf(1_000L, 4_000L), points.map { it.elapsedMs })
        assertTrue(points[1].breakBefore)
    }
    @Test fun sessionChangeClosesOldWriterAndRejectsOldCallbacks() {
        start(); ready()
        source.callbacks[0].receive(point(1_000), false)
        await { state.get().accepted == 1 }
        now.set(2_000)
        val second = UUID.randomUUID().toString()
        capture!!.follow(second, true, true)
        await { source.callbacks.size == 2 }
        source.callbacks[0].receive(point(2_000), false)
        source.callbacks[1].receive(point(2_000), false)
        await { state.get().lastElapsedMs == 2_000L }
        assertEquals(listOf(1_000L), TelemetryStore(directory).load(id)!!.points.map { it.elapsedMs })
        assertEquals(listOf(2_000L), TelemetryStore(directory).load(second)!!.points.map { it.elapsedMs })
    }
    @Test fun disablingDuringRecordingStopsCaptureWithoutAFurtherRecorderCallback() {
        start(); ready()
        source.callbacks[0].receive(point(1_000), false)
        await { state.get().accepted == 1 }
        enabled.set(false); now.set(2_000)
        source.callbacks[0].receive(point(2_000), false)
        await { state.get().status == TelemetryStatus.DISABLED && source.callbacks[0].closed.get() }
        assertNull(state.get().speedMps)
        assertEquals(1, TelemetryStore(directory).load(id)!!.points.size)
    }
    @Test fun permissionLostMidSessionClosesOnlyTheTelemetryPipeline() {
        start(); ready()
        source.problem = TelemetryStatus.NO_PERMISSION
        await { state.get().status == TelemetryStatus.NO_PERMISSION && source.callbacks[0].closed.get() }
        assertTrue(TelemetryStore(directory).clear())
    }
    @Test fun staleStateDropsSpeedAndNextFixBeginsANewPath() {
        start(); ready()
        source.callbacks[0].receive(point(1_000), false)
        await { state.get().accepted == 1 }
        now.set(7_000)
        await { state.get().status == TelemetryStatus.STALE }
        assertNull(state.get().speedMps)
        source.callbacks[0].receive(point(7_000), false)
        await { state.get().accepted == 2 }
        assertTrue(TelemetryStore(directory).load(id)!!.points.last().breakBefore)
    }
    @Test fun mockedAndOldSamplesCannotBecomeSavedLocations() {
        start(); ready()
        source.callbacks[0].receive(point(1_000), true)
        source.callbacks[0].receive(point(900), false)
        now.set(2_000); source.callbacks[0].receive(point(2_000).copy(speedMps = null), false)
        await { state.get().accepted == 1 }
        assertEquals(2, state.get().rejected)
        assertNull(state.get().speedMps)
        assertEquals(listOf(2_000L), TelemetryStore(directory).load(id)!!.points.map { it.elapsedMs })
    }
    @Test fun diskErrorIsReportedWithoutStartingTheProvider() {
        val blocked = File(directory, "file-instead-of-directory").apply { writeText("test") }
        start(TelemetryStore(blocked))
        await { state.get().status == TelemetryStatus.STORAGE_ERROR }
        assertEquals(0, source.callbacks.size)
    }

    @Test fun appendLimitClosesTheProviderAndDropsSubsequentUpdates() {
        start(); ready()
        RandomAccessFile(File(directory, "$id.jsonl"), "rw").use { it.setLength(TelemetryStore.MAX_FILE_BYTES) }
        source.callbacks[0].receive(point(1_000), false)
        await { state.get().status == TelemetryStatus.LIMIT_REACHED && source.callbacks[0].closed.get() }
        assertEquals(0, state.get().accepted)
        assertNull(state.get().speedMps)
    }

    private class FakeSource : TelemetryLocationSource {
        data class Subscription(val receive: (TelemetryFix, Boolean) -> Unit, val gap: () -> Unit, val closed: AtomicBoolean = AtomicBoolean())
        val callbacks = CopyOnWriteArrayList<Subscription>()
        @Volatile var problem: TelemetryStatus? = null
        override fun unavailable() = problem
        override fun subscribe(looper: Looper, receive: (TelemetryFix, Boolean) -> Unit, gap: () -> Unit): Closeable {
            val item = Subscription(receive, gap)
            callbacks += item
            return Closeable { item.closed.set(true) }
        }
    }
}
