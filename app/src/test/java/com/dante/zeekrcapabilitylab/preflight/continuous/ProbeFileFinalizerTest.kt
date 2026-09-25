package com.dante.zeekrcapabilitylab.preflight.continuous

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Test

class ProbeFileFinalizerTest {
    @Test fun oldFileCloseCanTake2342msWhileNewFileWritesContinue() {
        val time=AtomicLong(100);val f=ProbeFileFinalizer(time::get)
        val started=CountDownLatch(1);val close=CountDownLatch(1)
        try {
            f.submit(0, {true}) {started.countDown();check(close.await(3,TimeUnit.SECONDS))}
            assertTrue(started.await(1,TimeUnit.SECONDS))
            time.set(2442)
            val hot=ProbeSegmentProgress(listOf(60_000_000,120_000_000,180_000_000))
            hot.writer("WRITE_SAMPLE",1,2441);hot.written(60_033_333);hot.writer("IDLE",1,2442)
            assertNull(hot.problem(2442));assertNull(f.problem())
            assertEquals(1,hot.snapshot(2442).writtenFrames)
            assertThrows(IllegalStateException::class.java) {f.submit(1, {true}) {error("second native owner")}}
        } finally {close.countDown();assertTrue(f.finish(3000))}
    }
    @Test fun timeoutDoesNotReleaseAnOutstandingOwnerAndLateReturnDoesNotEraseIt() {
        val time=AtomicLong(100);val f=ProbeFileFinalizer(time::get)
        val started=CountDownLatch(1);val release=CountDownLatch(1)
        try {
            f.submit(0, {true}) {started.countDown();check(release.await(3,TimeUnit.SECONDS))}
            assertTrue(started.await(1,TimeUnit.SECONDS));time.set(8101)
            assertEquals("FINALIZER_MUXER_TIMEOUT",f.problem()?.code)
            assertFalse(f.finish(5));assertFalse(f.terminated())
            assertNull(f.snapshot().work.single().completedAtMs)
        } finally {release.countDown();assertTrue(f.finish(3000))}
        assertEquals("FINALIZER_MUXER_TIMEOUT",f.problem()?.code)
    }
    @Test fun descriptorSyncHasItsOwnBudgetAndFailureKeepsItsCause() {
        val time=AtomicLong(100);val f=ProbeFileFinalizer(time::get);val sync=CountDownLatch(1);val release=CountDownLatch(1)
        val ownerReleased=AtomicBoolean(false)
        try {
            f.submit(0, ownerReleased::get) {
                try {time.set(2442);f.stage(0,"USB_SYNC_CLOSE");sync.countDown();check(release.await(3,TimeUnit.SECONDS));throw java.io.IOException("controlled")}
                finally {ownerReleased.set(true)}
            }
            assertTrue(sync.await(1,TimeUnit.SECONDS));time.set(4443)
            assertEquals("FINALIZER_SYNC_TIMEOUT",f.problem()?.code)
        } finally {release.countDown();assertTrue(f.finish(3000))}
        assertTrue(ownerReleased.get());assertTrue(f.failure is java.io.IOException)
        assertEquals("FINALIZER_SYNC_TIMEOUT",f.snapshot().problem?.code)
    }
}
