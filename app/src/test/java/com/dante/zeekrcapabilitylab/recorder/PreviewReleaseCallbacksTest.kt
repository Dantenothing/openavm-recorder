package com.dante.zeekrcapabilitylab.recorder

import com.dante.zeekrcapabilitylab.service.recorder.*
import com.dante.zeekrcapabilitylab.mirror.RetainedPreviewLease
import org.junit.Assert.*
import org.junit.Test

class PreviewReleaseCallbacksTest {
    @Test fun textureRemainsOwnedAcrossViewLossSessionClosedAndLateCaptureCompletion() {
        val order = mutableListOf<String>()
        val lease = RetainedPreviewLease { order.add("texture") }
        lease.attach(); lease.acquireProducer()
        val outputs = PreviewReleaseCallbacks<Any> { order.add("surface") }
        val surface = Any(); val session = Any()
        outputs.track(surface) { lease.producerReleased() }
        val ledger = CaptureSessionLedger<Any, Any> { outputs.release(it) }
        ledger.submitted(session, 7); ledger.retire(session, surface)
        lease.detach(); lease.retire(); ledger.sessionClosed(session)
        assertTrue(order.isEmpty())
        ledger.sequenceEnded(session, 7)
        assertEquals(listOf("surface", "texture"), order)
        ledger.deviceClosed(); assertEquals(2, order.size)
    }
    @Test fun releaseFailureCannotNotifyConsumerOfFalseSafety() {
        var fail = true; var notified = 0
        val callbacks = PreviewReleaseCallbacks<Any> { if (fail) error("native release failed") }
        val surface = Any(); callbacks.track(surface) { notified++ }
        runCatching { callbacks.release(surface) }; assertEquals(0, notified)
        fail = false; callbacks.release(surface); callbacks.release(surface)
        assertEquals(1, notified)
    }
    @Test fun equalityDoesNotTransferReleaseOwnershipToADifferentWrapper() {
        data class Wrapper(val value: Int)
        val first = Wrapper(1); val second = Wrapper(1)
        var a = 0; var b = 0
        val callbacks = PreviewReleaseCallbacks<Wrapper> { }
        callbacks.track(first) { a++ }; callbacks.track(second) { b++ }
        callbacks.release(second); assertEquals(0, a); assertEquals(1, b)
        callbacks.release(first); assertEquals(1, a)
    }
}
