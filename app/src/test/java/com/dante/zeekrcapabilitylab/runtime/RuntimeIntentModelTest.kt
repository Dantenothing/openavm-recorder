package com.dante.zeekrcapabilitylab.runtime

import org.junit.Assert.*
import org.junit.Test

class RuntimeIntentModelTest {
    private fun task(id: String="a", parent: String="local", kind: RuntimeIntentModel.Kind=RuntimeIntentModel.Kind.LOCAL_RECORD,
                     revision: Long=1, deadline: Long=10_000) = RuntimeIntentModel.Task(id,parent,kind,revision,deadline,"AVM","REAR")
    @Test fun noPhoneOrServerIsNeededForLocalUse() {
        val m=RuntimeIntentModel("p"); assertTrue(m.beginLocal("local")); assertTrue(m.start("p",task(),0))
        assertTrue(m.target(9000).recording); assertFalse(m.target(9000).standby)
    }
    @Test fun enteringParkingRevokesLocalRecordingEvenIfDisplayNeverTurnsOff() {
        val m=RuntimeIntentModel("p"); m.beginLocal("local"); m.start("p",task(),0)
        assertTrue(m.park("park",100,1000)); assertFalse(m.target(100).recording); assertTrue(m.target(100).standby)
        assertFalse(m.target(1100).camera); assertFalse(m.target(1100).standby)
    }
    @Test fun awayEndsRecoveryAndLateStartsCannotRearmIt() {
        val m=RuntimeIntentModel("p"); m.beginLocal("local"); m.start("p",task(),0); m.endLocal("local")
        assertFalse(m.start("p",task("late"),10)); assertFalse(m.beginLocal("local")); assertFalse(m.target(10).camera)
    }
    @Test fun stoppedTaskNeverRevivesEvenWithHigherRevision() {
        val m=RuntimeIntentModel("p"); m.park("park",0,1000)
        assertTrue(m.stop("p","a","park",RuntimeIntentModel.Kind.REMOTE_VIEW,2,1))
        assertFalse(m.start("p",task(parent="park",kind=RuntimeIntentModel.Kind.REMOTE_VIEW,deadline=900),2))
        assertFalse(m.start("p",task(parent="park",kind=RuntimeIntentModel.Kind.REMOTE_VIEW,revision=3,deadline=900),2))
    }
    @Test fun oldStopCannotTouchNewLocalTask() {
        val m=RuntimeIntentModel("p"); m.park("park",0,1000); m.beginLocal("new")
        m.start("p",task("new-task","new"),0)
        assertFalse(m.stop("p","new-task","park",RuntimeIntentModel.Kind.REMOTE_VIEW,999,1))
        assertTrue(m.target(1).recording)
    }
    @Test fun expiryWinsOverLateOpenAndDoesNotNeedTimerCallback() {
        val m=RuntimeIntentModel("p"); m.park("park",0,1000)
        assertFalse(m.start("p",task(parent="park",kind=RuntimeIntentModel.Kind.REMOTE_VIEW,deadline=2000),1001))
        assertFalse(m.target(1001).standby)
    }
    @Test fun childCannotExtendParentAndViewStopDoesNotStopParkingRecording() {
        val m=RuntimeIntentModel("p"); m.park("park",0,1000)
        assertFalse(m.start("p",task(parent="park",kind=RuntimeIntentModel.Kind.REMOTE_VIEW,deadline=1001),0))
        assertTrue(m.start("p",task("v","park",RuntimeIntentModel.Kind.REMOTE_VIEW,deadline=900),0))
        assertTrue(m.start("p",task("r","park",RuntimeIntentModel.Kind.PARKING_RECORD,deadline=1000),0))
        m.stop("p","v","park",RuntimeIntentModel.Kind.REMOTE_VIEW,2,1)
        assertFalse(m.target(2).remoteVideo); assertTrue(m.target(2).recording)
        assertFalse(m.target(1000).camera)
    }
    @Test fun unknownCleanupAndOldProcessRejectOpen() {
        val m=RuntimeIntentModel("new"); m.beginLocal("local")
        assertFalse(m.start("old",task(),0)); m.cleanupConfirmed=false; assertFalse(m.start("new",task(),0))
    }
    @Test fun explicitNewHomeVisitCanRequestPreviewAfterAway() {
        val m=RuntimeIntentModel("p"); m.beginLocal("old"); m.endLocal("old"); m.beginLocal("home-visit")
        assertTrue(m.start("p",task("preview","home-visit",RuntimeIntentModel.Kind.LOCAL_PREVIEW),0))
        assertTrue(m.target(1).localDisplay); assertFalse(m.target(1).recording)
    }
    @Test fun sameStartIsIdempotentButIdentityCollisionIsRejected() {
        val m=RuntimeIntentModel("p"); m.beginLocal("local"); assertTrue(m.start("p",task(),0)); assertTrue(m.start("p",task(),1))
        assertFalse(m.start("p",task().copy(source="CABIN"),1))
    }
    @Test fun telemetryDoesNotAdvanceControlRevision() {
        val m=RuntimeIntentModel("p"); m.beginLocal("local"); m.start("p",task(),0)
        repeat(100) { m.target(it.toLong()) }
        assertTrue(m.stop("p","a","local",RuntimeIntentModel.Kind.LOCAL_RECORD,2,100))
    }
}
