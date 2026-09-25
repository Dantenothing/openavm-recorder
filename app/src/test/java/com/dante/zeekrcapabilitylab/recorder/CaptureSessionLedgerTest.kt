package com.dante.zeekrcapabilitylab.recorder

import com.dante.zeekrcapabilitylab.service.recorder.CaptureSessionLedger
import org.junit.Assert.*
import org.junit.Test

class CaptureSessionLedgerTest {
    @Test fun oldPreviewSurvivesClosedUntilAllCapturesEnd() {
        val released = mutableListOf<Any>()
        val ledger = CaptureSessionLedger<Any, Any> { released.add(it) }
        val session = Any(); val surface = Any()
        ledger.submitted(session, 1); ledger.submitted(session, 2)
        ledger.retire(session, surface); ledger.sessionClosed(session)
        assertTrue(released.isEmpty())
        ledger.sequenceEnded(session, 1)
        assertTrue(released.isEmpty())
        ledger.sequenceEnded(session, 2)
        assertEquals(listOf(surface), released)
        ledger.sequenceEnded(session, 2); ledger.deviceClosed()
        assertEquals(1, released.size)
    }

    @Test fun replacementDoesNotEraseOldProducerAndSequenceNumbersAreScoped() {
        val ledger = CaptureSessionLedger<Any, Any> { }
        val old = Any(); val replacement = Any()
        ledger.submitted(old, 1); ledger.sessionClosed(old)
        ledger.submitted(replacement, 1); ledger.sequenceEnded(replacement, 1)
        assertEquals(setOf(1), ledger.pending(old))
        assertTrue(ledger.hasOtherInFlight(replacement))
        ledger.sequenceEnded(old, 1)
        assertFalse(ledger.hasOtherInFlight(replacement))
    }

    @Test fun sequenceEndAloneCannotReleaseOutputStillOwnedByOpenSession() {
        val released = mutableListOf<Any>()
        val ledger = CaptureSessionLedger<Any, Any> { released.add(it) }
        val session = Any(); val output = Any()
        ledger.submitted(session, 1); ledger.retire(session, output)
        ledger.sequenceEnded(session, 1)
        assertTrue(released.isEmpty())
        ledger.sessionClosed(session)
        assertEquals(listOf(output), released)
    }

    @Test fun missingCompletionRetainsLeaseUntilActualDeviceClose() {
        val released = mutableListOf<Any>()
        val ledger = CaptureSessionLedger<Any, Any> { released.add(it) }
        val session = Any(); val output = Any()
        ledger.submitted(session, 9); ledger.retire(session, output); ledger.sessionClosed(session)
        assertEquals(1, ledger.retiredOutputCount)
        ledger.deviceClosed()
        assertEquals(listOf(output), released)
        assertEquals(0, ledger.retiredOutputCount)
        ledger.sequenceEnded(session, 9)
        assertTrue(ledger.pending(session).isEmpty())
    }
}
