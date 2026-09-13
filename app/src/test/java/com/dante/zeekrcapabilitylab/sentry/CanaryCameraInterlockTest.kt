package com.dante.zeekrcapabilitylab.sentry

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CanaryCameraInterlockTest {
    @Test fun deviceAckDoesNotClearUnsettledRecorderOwnership() {
        val open = Any(); val cleanup = Any(); val next = Any()
        try {
            assertTrue(CanaryCameraInterlock.beginNormalOpen(open, "2"))
            CanaryCameraInterlock.beginCleanup(cleanup, "2")
            CanaryCameraInterlock.normalClosed(open)
            assertFalse(CanaryCameraInterlock.beginNormalOpen(next, "2"))
            assertFalse(CanaryCameraInterlock.reserveCanary(next))
            CanaryCameraInterlock.cleanupComplete(cleanup)
            assertTrue(CanaryCameraInterlock.beginNormalOpen(next, "2"))
        } finally {
            CanaryCameraInterlock.normalClosed(open); CanaryCameraInterlock.normalClosed(next)
            CanaryCameraInterlock.cleanupComplete(cleanup); CanaryCameraInterlock.canaryClosed(next)
        }
    }
    @Test fun unrelatedCleanupTokenCannotReleaseAnotherOwner() {
        val cleanup = Any(); val next = Any()
        try {
            CanaryCameraInterlock.beginCleanup(cleanup, "2")
            CanaryCameraInterlock.cleanupComplete(Any())
            assertFalse(CanaryCameraInterlock.beginNormalOpen(next, "2"))
        } finally { CanaryCameraInterlock.cleanupComplete(cleanup); CanaryCameraInterlock.normalClosed(next) }
    }
    @Test fun twoNormalRequestsForSameIdHaveOneAtomicWinner() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(100) {
                val a = Any(); val b = Any(); val start = CountDownLatch(1)
                val left = executor.submit<Boolean> { start.await(); CanaryCameraInterlock.beginNormalOpen(a, "2") }
                val right = executor.submit<Boolean> { start.await(); CanaryCameraInterlock.beginNormalOpen(b, "2") }
                start.countDown()
                try { assertNotEquals(left.get(2, TimeUnit.SECONDS), right.get(2, TimeUnit.SECONDS)) }
                finally { CanaryCameraInterlock.normalClosed(a); CanaryCameraInterlock.normalClosed(b) }
            }
        } finally { executor.shutdownNow() }
    }
    @Test fun closingOldCameraDoesNotAuthorizeAnotherOpenWhileNewOwnerExists() {
        val old = Any()
        val current = Any()
        val canary = Any()
        try {
            assertTrue(CanaryCameraInterlock.beginNormalOpen(old, "2"))
            assertTrue(CanaryCameraInterlock.beginNormalOpen(current, "3"))
            CanaryCameraInterlock.normalClosed(old)
            assertFalse(CanaryCameraInterlock.reserveCanary(canary))
            CanaryCameraInterlock.normalClosed(old)
            assertFalse(CanaryCameraInterlock.reserveCanary(canary))
            CanaryCameraInterlock.normalClosed(current)
            assertTrue(CanaryCameraInterlock.reserveCanary(canary))
            assertFalse(CanaryCameraInterlock.canaryClosed(Any()))
            assertFalse(CanaryCameraInterlock.beginNormalOpen(current))
        } finally {
            CanaryCameraInterlock.normalClosed(old)
            CanaryCameraInterlock.normalClosed(current)
            CanaryCameraInterlock.canaryClosed(canary)
        }
    }

    @Test fun simultaneousNormalAndCanaryAdmissionHasExactlyOneWinner() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(200) {
                val normal = Any()
                val canary = Any()
                val start = CountDownLatch(1)
                val a = executor.submit<Boolean> { start.await(); CanaryCameraInterlock.beginNormalOpen(normal) }
                val b = executor.submit<Boolean> { start.await(); CanaryCameraInterlock.reserveCanary(canary) }
                start.countDown()
                try { assertNotEquals(a.get(2, TimeUnit.SECONDS), b.get(2, TimeUnit.SECONDS)) }
                finally { CanaryCameraInterlock.normalClosed(normal); CanaryCameraInterlock.canaryClosed(canary) }
            }
        } finally { executor.shutdownNow() }
    }
}
