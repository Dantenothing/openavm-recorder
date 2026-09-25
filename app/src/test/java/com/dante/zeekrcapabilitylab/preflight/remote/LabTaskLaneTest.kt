package com.dante.zeekrcapabilitylab.preflight.remote

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executor

class LabTaskLaneTest {
    private class Manual:Executor {
        val pending=ArrayDeque<Runnable>()
        override fun execute(command:Runnable) {pending.addLast(command)}
        fun next()=pending.removeFirst().run()
    }
    @Test fun supersededSessionCannotReceiveLateNetworkResult() {
        val control=Manual();val network=Manual();val lane=LabTaskLane(control,network)
        var old=0;var new=0
        assertTrue(lane.submit({7}) {old++})
        assertFalse(lane.submit({8}) {old++})
        lane.invalidate();assertTrue(lane.submit({9}) {new=it.getOrThrow()})
        network.next();control.next();assertEquals(0,old);assertTrue(lane.busy)
        network.next();control.next();assertEquals(9,new);assertFalse(lane.busy)
    }
    @Test fun failedNetworkReturnsToControlAndAllowsNextRequest() {
        val control=Manual();val network=Manual();val lane=LabTaskLane(control,network)
        var failed=false
        lane.submit({error("OFFLINE")}) {failed=it.isFailure}
        network.next();assertFalse(failed);control.next();assertTrue(failed);assertFalse(lane.busy)
    }
    @Test fun slowNetworkDoesNotDelayControlOrLocalCancellation() {
        val control=Executors.newSingleThreadExecutor()
        val network=Executors.newSingleThreadExecutor()
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val stopped=CountDownLatch(1)
        val lane=LabTaskLane(control,network)
        try {
            control.execute { lane.submit({entered.countDown();release.await(5,TimeUnit.SECONDS)}) {} }
            assertTrue(entered.await(2,TimeUnit.SECONDS))
            control.execute {lane.invalidate();stopped.countDown()}
            assertTrue("Local cancellation waited behind the HTTP request",stopped.await(300,TimeUnit.MILLISECONDS))
        } finally {release.countDown();control.shutdownNow();network.shutdownNow()}
    }
}
