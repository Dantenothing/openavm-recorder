package com.dante.zeekrcapabilitylab.recorder

import com.dante.zeekrcapabilitylab.service.recorder.*
import org.junit.Assert.*
import org.junit.Test

class ContinuousProductOwnershipTest {
    @Test fun muxerTailEndsExactlyAtNextFilesFirstKeyframe() {
        assertEquals(5_000_001L, ContinuousFileTiming.durationUs(1_234_567, 6_201_235, 6_234_568))
        assertThrows(IllegalArgumentException::class.java) { ContinuousFileTiming.durationUs(10, 30, 30) }
        assertThrows(IllegalArgumentException::class.java) { ContinuousFileTiming.durationUs(10, 30, 29) }
        assertEquals(33_334L, ContinuousFileTiming.durationUs(10, 10, 33_344))
    }
    @Test fun retiringWindowWaitsForExplicitEglDetach() {
        val value = Any(); val releases = mutableListOf<Any>()
        val lease = ContinuousPreviewLease(value, releases::add)
        assertTrue(lease.take())
        lease.retire(); lease.retire()
        assertFalse(lease.take()); assertTrue(releases.isEmpty())
        lease.detachAcknowledged(); assertEquals(listOf(value), releases)
        lease.retire(); assertEquals(1, releases.size)
    }
    @Test fun replacedUnattachedWindowIsReleasedWithoutWaitingForCamera() {
        var released = false
        ContinuousPreviewLease(Any()) { released = true }.retire()
        assertTrue(released)
    }
    @Test fun hidingDoesNotRetireAReusableWindow() {
        var releases = 0
        val lease = ContinuousPreviewLease(Any()) { releases++ }
        repeat(1000) { assertTrue(lease.take()); lease.detachAcknowledged() }
        assertEquals(0, releases)
        lease.retire(); assertEquals(1, releases)
    }
    @Test fun staleDoubleDetachCannotFreeAnotherReader() {
        val lease = ContinuousPreviewLease(Any()) { }
        assertTrue(lease.take()); assertFalse(lease.take())
        lease.detachAcknowledged()
        assertThrows(IllegalStateException::class.java) { lease.detachAcknowledged() }
    }
    @Test fun oldFileCloseDoesNotBlockAdmissionOfOnePreparedFile() {
        val owners = ContinuousFileOwners<Any>()
        val old = Any(); val current = Any(); val next = Any()
        owners.admit(1, old); owners.admit(2, current); owners.admit(3, next)
        assertEquals(listOf(old, current, next), owners.snapshot())
        assertThrows(IllegalStateException::class.java) { owners.admit(4, Any()) }
        assertThrows(IllegalStateException::class.java) { owners.release(old, false) }
        assertEquals(3, owners.snapshot().size)
        owners.release(old, true)
        owners.admit(4, Any())
        assertEquals(3, owners.snapshot().size)
    }
    @Test fun sustainedRotationRetainsNoCompletedFileHistory() {
        val owners = ContinuousFileOwners<Any>()
        var active = Any(); owners.admit(1, active)
        repeat(10_000) { i ->
            val next = Any(); owners.admit(i + 2, next)
            assertEquals(2, owners.snapshot().size)
            owners.release(active, true); active = next
        }
        owners.release(active, true); assertTrue(owners.snapshot().isEmpty())
        assertThrows(IllegalStateException::class.java) { owners.admit(1, Any()) }
    }
    @Test fun fileIdentityUsesOwnershipNotValueEquality() {
        data class Handle(val name: String)
        val owners = ContinuousFileOwners<Handle>()
        val active = Handle("same"); val wrong = Handle("same")
        owners.admit(1, active)
        assertThrows(IllegalStateException::class.java) { owners.release(wrong, true) }
        assertEquals(listOf(active), owners.snapshot())
    }
    @Test fun sourceClockRejectsRegressionAndIgnoresRepeatedImage() {
        val clock = ContinuousSourceClock()
        assertNull(clock.accept(0)); assertEquals(0L, clock.accept(9_000_000_000L))
        assertNull(clock.accept(9_000_000_000L))
        assertEquals(33_333L, clock.accept(9_033_333_333L))
        assertEquals(2, clock.frames); assertEquals(33_333_333L, clock.maximumGapNs)
        assertThrows(IllegalStateException::class.java) { clock.accept(8_000_000_000L) }
    }
    @Test fun sourceClockIsIndependentOfFileCloseAndDisplayIntervals() {
        val clock = ContinuousSourceClock()
        repeat(600) { i -> assertEquals(i * 33_334L, clock.accept(1_000_000_000L + i * 33_334_000L)) }
        assertEquals(600, clock.frames); assertEquals(33_334_000L, clock.maximumGapNs)
        assertEquals(1_000_000_000L, clock.firstNs)
    }
}
