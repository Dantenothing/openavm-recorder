package com.dante.zeekrcapabilitylab.enhancement

import com.dante.zeekrcapabilitylab.sentry.CanaryCameraInterlock as Gate
import org.junit.Assert.*
import org.junit.Test

class CameraGroupAdmissionTest {
    @Test fun groupReservationIsAtomicAndHeldUntilEveryActualOwnerCloses() {
        val first = Any(); val a = Any(); val b = Any(); val other = Any()
        try {
            assertTrue(Gate.beginNormalOpen(first, "group-test-0"))
            assertFalse(Gate.beginNormalGroup(mapOf(a to "group-test-1", b to "group-test-2")))
            assertFalse(Gate.ownsNormalReservation(a, "group-test-1"))
            Gate.normalClosed(first)
            assertTrue(Gate.beginNormalGroup(mapOf(a to "group-test-1", b to "group-test-2")))
            assertFalse(Gate.beginNormalOpen(other, "group-test-3"))
            Gate.normalClosed(a)
            assertFalse(Gate.beginNormalOpen(other, "group-test-3"))
            Gate.normalClosed(b)
            assertTrue(Gate.beginNormalOpen(other, "group-test-3"))
        } finally { listOf(first, a, b, other).forEach(Gate::normalClosed) }
    }
}
