package com.dante.zeekrcapabilitylab.runtime
import org.junit.Assert.*
import org.junit.Test
class C0PolicyTest {
    private fun ready()=C0Policy("run","process",0).also { it.closed(1000) }
    private fun accept(p:C0Policy,now:Long=121000,sent:Long=120000,foreground:Boolean=false,cleanup:Boolean=true,seq:Int=1,
                       nonce:String="n",process:String="process")=p.accept(now,sent,process,"run","n",nonce,seq,foreground,cleanup)
    @Test fun needsConfirmedClosedCameraAndFullWait() { val p=C0Policy("run","process",0); assertFalse(accept(p)); p.closed(1000); assertFalse(accept(p,120999)); assertTrue(accept(p)) }
    @Test fun onlyFreshBoundRepliesMayReopen() { assertFalse(accept(ready(),sent=100000)); assertFalse(accept(ready(),nonce="old")); assertFalse(accept(ready(),process="old")); assertFalse(accept(ready(),sent=122000)) }
    @Test fun foregroundOrUnconfirmedCleanupCannotPass() { assertFalse(accept(ready(),foreground=true)); assertFalse(accept(ready(),cleanup=false)) }
    @Test fun cancelAndDeadlineDefeatLateReplies() { val p=ready(); p.revoke(); assertFalse(accept(p)); assertFalse(accept(ready(),C0Policy.DURATION_MS,C0Policy.DURATION_MS-1)) }
    @Test fun oldNotificationCannotCancelNewRun() {
        val p=ready()
        assertFalse(p.cancelRun(null)); assertFalse(p.cancelRun("previous-run")); assertTrue(p.active(121000))
        assertTrue(p.cancelRun("run")); assertFalse(accept(p))
    }
    @Test fun replayAndThirdOpenAreRejected() { val p=ready(); assertTrue(accept(p)); assertFalse(accept(p)); p.closed(122000); assertTrue(accept(p,242000,241000,seq=2)); p.closed(243000); assertFalse(accept(p,363000,362000,seq=3)) }
    @Test fun expiryClosingAnIncompleteCaptureCannotPass() {
        val complete=C0CaptureEvidence(30,false,false)
        assertEquals("BACKGROUND_REOPEN_NOT_COMPLETED",C0Verdict.classify(true,"PERMIT_EXPIRED",true,listOf(complete,complete.copy(buffers=0))))
        assertEquals("PASS_BACKGROUND_DISPLAY_OFF",C0Verdict.classify(true,"PERMIT_EXPIRED",true,listOf(complete,complete)))
    }
    @Test fun buffersDoNotProveScreenOffOrCleanupOrUncancelledCompletion() {
        val pair=List(2){C0CaptureEvidence(30,false,false)}
        assertEquals("INCOMPLETE",C0Verdict.classify(true,"PERMIT_EXPIRED",false,pair))
        assertEquals("INCOMPLETE",C0Verdict.classify(true,"USER_CANCELLED",true,pair))
        assertEquals("BACKGROUND_REOPEN_DISPLAY_OFF_NOT_PROVEN",C0Verdict.classify(true,"PERMIT_EXPIRED",true,pair.map {it.copy(displayOnObserved=true)}))
        assertEquals("RUNNING",C0Verdict.classify(false,null,true,pair))
    }
}
