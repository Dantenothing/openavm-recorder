package com.dante.zeekrcapabilitylab.telemetry

import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Production scheduler/read gate; synthetic read-only signals, no vehicle SDK or network. */
class VehicleTelemetryRecoveryAndroidTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun sample(speed: Double) = SystemClock.elapsedRealtime().let { at ->
        VehicleTelemetrySample(at, System.currentTimeMillis(), mapOf("speed" to VehicleTelemetryValue(at, number = speed),
            "gear" to VehicleTelemetryValue(at, text = "D")))
    }
    @Test fun previewUpdatesWithoutRecorderOrJournalAndStopsThenReturns() {
        assumeTrue(Build.HARDWARE == "ranchu")
        val allowed = AtomicBoolean(true)
        val calls = AtomicInteger()
        val live = AtomicReference(VehicleTelemetryCaptureState())
        val recorderOwner = Any()
        RecordingVehicleTelemetry.claim(recorderOwner)
        RecordingVehicleTelemetry.publish(recorderOwner, VehicleTelemetryCaptureState(VehicleTelemetryStatus.IDLE))
        val directory = java.io.File(context.noBackupFilesDir, "recording-vehicle-telemetry")
        val filesBefore = directory.list().orEmpty().toSet()
        val capture = RecordingVehicleTelemetryCapture(context, allowed::get, true, live::set, {
            calls.incrementAndGet(); sample(32.0)
        })
        try {
            capture.follow(null, true)
            await { live.get().accepted >= 3 }
            assertEquals(32.0, live.get().speedKmh!!, 0.0); assertEquals("D", live.get().gear)
            assertEquals(VehicleTelemetryStatus.IDLE, RecordingVehicleTelemetry.state.value.status)
            assertEquals(filesBefore, directory.list().orEmpty().toSet())
            allowed.set(false); capture.follow(null, false)
            await { live.get().latestSample == null }
            val stopped = calls.get(); SystemClock.sleep(650); assertEquals(stopped, calls.get())
            allowed.set(true); capture.follow(null, true)
            await { live.get().accepted >= 2 }
            capture.close(); await { live.get().latestSample == null }
            val closed = calls.get(); SystemClock.sleep(650); assertEquals(closed, calls.get())
        } finally { capture.close() }
    }
    @Test fun recorderRecoversAfterSlowGetterWithoutStartingAnotherSdkWorker() {
        assumeTrue(Build.HARDWARE == "ranchu")
        val calls = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val capture = RecordingVehicleTelemetryCapture(context, { true }, sampleReader = {
            if (calls.incrementAndGet() == 1) {
                entered.countDown(); check(release.await(15, TimeUnit.SECONDS)); returned.countDown(); sample(999.0)
            } else sample(24.0)
        })
        try {
            capture.follow(UUID.randomUUID().toString(), true)
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            await { RecordingVehicleTelemetry.state.value.status == VehicleTelemetryStatus.SDK_TIMEOUT }
            // Cross the backoff while the getter is still blocked: no parallel or queued retry.
            SystemClock.sleep(5_400)
            assertEquals(1, calls.get())
            assertNull(RecordingVehicleTelemetry.state.value.latestSample)
            release.countDown(); assertTrue(returned.await(2, TimeUnit.SECONDS))
            await { RecordingVehicleTelemetry.state.value.speedKmh == 24.0 }
            assertTrue(calls.get() >= 2)
            assertEquals("D", RecordingVehicleTelemetry.state.value.gear)
            capture.follow(null, false)
            await { RecordingVehicleTelemetry.state.value.latestSample == null }
        } finally { release.countDown(); capture.close() }
    }
    private fun await(timeoutMs: Long = 6_000, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (!condition() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(20)
        assertTrue("Telemetry condition within $timeoutMs ms", condition())
    }
}
