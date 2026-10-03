package com.dante.zeekrcapabilitylab.telemetry

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class RecordingVehicleTelemetryTest {
    private class ManualExecutor : Executor {
        val queue = ArrayList<Runnable>()
        override fun execute(command: Runnable) { queue += command }
        fun run() { queue.removeAt(0).run() }
    }
    @Test fun pendingNativeCallCannotBeReplacedByAnotherRecordingOrTimeout() {
        val executor = ManualExecutor()
        val gate = VehicleTelemetryReadGate(executor)
        var oldCurrent = true
        var calls = 0
        var delivered = 0
        assertTrue(gate.submit({ oldCurrent }, { calls++; 10 }, { delivered++ }))
        oldCurrent = false // stop / timeout fences delivery, but does not make another SDK worker
        repeat(10) { assertFalse(gate.submit({ true }, { calls++; 20 }, { delivered++ })) }
        assertTrue(gate.busy)
        assertEquals(1, executor.queue.size)
        executor.run()
        assertEquals(0, calls); assertEquals(0, delivered); assertFalse(gate.busy)
        assertTrue(gate.submit({ true }, { calls++; 30 }, { delivered++ }))
        executor.run()
        assertEquals(1, calls); assertEquals(1, delivered)
    }
    @Test fun blockedGetterDoesNotHoldControllerAndItsLateResultIsDropped() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val workerDrained = CountDownLatch(1)
        val live = AtomicBoolean(true)
        val deliveries = AtomicInteger()
        val worker = Executors.newSingleThreadExecutor()
        val gate = VehicleTelemetryReadGate(worker)
        try {
            assertTrue(gate.submit(live::get, {
                started.countDown()
                check(release.await(2, TimeUnit.SECONDS))
                42
            }, { deliveries.incrementAndGet() }))
            assertTrue(started.await(2, TimeUnit.SECONDS))
            live.set(false)
            // This is the watchdog/stop thread; neither operation waits for the read to return.
            assertTrue(gate.busy)
            assertFalse(gate.submit({ true }, { 99 }, { deliveries.incrementAndGet() }))
            release.countDown()
            worker.execute { workerDrained.countDown() }
            assertTrue(workerDrained.await(2, TimeUnit.SECONDS))
            assertEquals(0, deliveries.get())
            assertFalse(gate.busy)
        } finally { release.countDown(); worker.shutdownNow() }
    }
    @Test fun readerFailureReturnsOnlyResultAndAlwaysReleasesSlot() {
        val executor = ManualExecutor()
        val gate = VehicleTelemetryReadGate(executor)
        var failed = false
        assertTrue(gate.submit({ true }, { throw IllegalStateException("not propagated to video") }, { failed = it.isFailure }))
        executor.run()
        assertTrue(failed); assertFalse(gate.busy)
    }
    @Test fun rejectedWorkerDoesNotLeaveAStuckReservation() {
        val gate = VehicleTelemetryReadGate(Executor { throw RejectedExecutionException() })
        assertFalse(gate.submit({ true }, { 10 }, {}))
        assertFalse(gate.busy)
    }
    @Test fun oldOwnerCloseCannotOverwriteNewRecorderState() {
        val old = Any()
        val newer = Any()
        RecordingVehicleTelemetry.claim(old)
        RecordingVehicleTelemetry.publish(old, VehicleTelemetryCaptureState(VehicleTelemetryStatus.SDK_TIMEOUT))
        RecordingVehicleTelemetry.claim(newer)
        RecordingVehicleTelemetry.publish(newer, VehicleTelemetryCaptureState(VehicleTelemetryStatus.RECORDING, speedKmh = 1.0))
        RecordingVehicleTelemetry.publish(old, VehicleTelemetryCaptureState(VehicleTelemetryStatus.IDLE))
        assertEquals(VehicleTelemetryStatus.RECORDING, RecordingVehicleTelemetry.state.value.status)
        assertEquals(1.0, RecordingVehicleTelemetry.state.value.speedKmh!!, 0.0)
    }
}
