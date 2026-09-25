package com.dante.zeekrcapabilitylab.recorder

import com.dante.zeekrcapabilitylab.service.recorder.RecordingFileFinalizer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class RecordingFileFinalizerTest {
    @Test fun thousandsOfFilesKeepOnlyBoundedRecentEvidence() {
        val worker = RecordingFileFinalizer({ 1L })
        try {
            repeat(4096) { file -> worker.submit(file, { true }) {}.get(2, TimeUnit.SECONDS) }
            val snapshot = worker.snapshot()
            assertEquals(4096L, snapshot.acceptedActions)
            assertEquals(4096L, snapshot.confirmedReleases)
            assertEquals((4080..4095).toList(), snapshot.work.map { it.file })
            assertTrue(snapshot.work.all { it.nativeReleased })
            assertThrows(IllegalStateException::class.java) { worker.submit(1, { true }) {} }
        } finally { assertTrue(worker.finish()) }
    }

    @Test fun diagnosticAdmissionBudgetRemainsFour() {
        val worker = RecordingFileFinalizer({ 1L }, historyLimit = 4, admissionLimit = 4)
        try {
            repeat(4) { worker.submit(it, { true }) {}.get(2, TimeUnit.SECONDS) }
            assertThrows(IllegalStateException::class.java) { worker.submit(4, { true }) {} }
            assertEquals(4L, worker.snapshot().acceptedActions)
        } finally { assertTrue(worker.finish()) }
    }

    @Test fun nativeOwnerIsRetainedWhenWorkerReturnsWithoutReleaseAcknowledgement() {
        val worker = RecordingFileFinalizer({ 1L })
        worker.submit(0, { false }) {}.get(2, TimeUnit.SECONDS)
        assertFalse(worker.finish())
        assertTrue(worker.snapshot().workerTerminated)
        assertFalse(worker.terminated())
        assertFalse(worker.snapshot().work.single().nativeReleased)
        assertEquals(0L, worker.snapshot().confirmedReleases)
        assertEquals("FINALIZER_NATIVE_RELEASE_UNCONFIRMED", worker.failure?.message)
        assertThrows(IllegalStateException::class.java) { worker.submit(1, { true }) {} }
    }

    @Test fun timeoutCannotReleaseOrInterruptBlockedOwner() {
        val now = AtomicLong(100)
        val worker = RecordingFileFinalizer(now::get)
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        val interrupted = AtomicBoolean(false); val released = AtomicBoolean(false)
        try {
            worker.submit(0, released::get) {
                started.countDown()
                try { check(release.await(3, TimeUnit.SECONDS)); released.set(true) }
                catch (error: InterruptedException) { interrupted.set(true); throw error }
            }
            assertTrue(started.await(1, TimeUnit.SECONDS)); now.set(8100)
            assertEquals("FINALIZER_MUXER_TIMEOUT", worker.problem()?.code)
            assertFalse(worker.finish(5))
            assertFalse(interrupted.get()); assertFalse(released.get())
        } finally { release.countDown(); assertTrue(worker.finish(3000)) }
        assertEquals("FINALIZER_MUXER_TIMEOUT", worker.problem()?.code)
        assertTrue(worker.snapshot().work.single().nativeReleased)
    }

    @Test fun sameStageNotificationsDoNotRestartClockAndStagesCannotRegress() {
        val time = AtomicLong(100)
        val worker = RecordingFileFinalizer(time::get)
        try {
            worker.submit(0, { true }) {
                time.set(8099); worker.stage(0, "MUXER_FINALIZE")
                time.set(8100)
                assertEquals("FINALIZER_MUXER_TIMEOUT", worker.problem()?.code)
                worker.stage(0, "USB_SYNC_CLOSE")
                assertThrows(IllegalStateException::class.java) { worker.stage(0, "MUXER_FINALIZE") }
            }.get(2, TimeUnit.SECONDS)
            assertNull(worker.failure)
            assertEquals("FINALIZER_MUXER_TIMEOUT", worker.problem()?.code)
        } finally { assertTrue(worker.finish()) }
    }

    @Test fun outputFailureIsNotSuccessEvenWhenItsResourcesClose() {
        val worker = RecordingFileFinalizer({ 1L })
        try {
            worker.submit(0, { true }) { throw IOException("controlled failure") }.get(2, TimeUnit.SECONDS)
            assertTrue(worker.failure is IOException)
            assertEquals("IOException", worker.snapshot().work.single().failureType)
            assertTrue(worker.snapshot().work.single().nativeReleased)
            assertThrows(IOException::class.java) { worker.submit(1, { true }) {} }
        } finally { assertTrue(worker.finish()) }
    }

    @Test fun oneCloserDoesNotAdmitAnUnboundedBacklog() {
        val worker = RecordingFileFinalizer({ 1L })
        val release = CountDownLatch(1)
        try {
            worker.submit(0, { true }) { check(release.await(3, TimeUnit.SECONDS)) }
            repeat(300) { assertThrows(IllegalStateException::class.java) { worker.submit(it + 1, { true }) {} } }
            assertEquals(1L, worker.snapshot().acceptedActions)
            assertEquals(1, worker.snapshot().work.size)
        } finally { release.countDown(); assertTrue(worker.finish(3000)) }
    }

    @Test fun stoppedWorkerCannotAdmitAnotherFile() {
        val worker = RecordingFileFinalizer({ 1L })
        assertTrue(worker.finish())
        assertThrows(IllegalStateException::class.java) { worker.submit(0, { true }) {} }
        assertEquals(0L, worker.snapshot().acceptedActions)
    }
}
