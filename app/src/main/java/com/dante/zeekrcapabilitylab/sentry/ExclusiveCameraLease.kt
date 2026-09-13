package com.dante.zeekrcapabilitylab.sentry

enum class CameraLeasePhase { ACQUIRING, ACTIVE, RELEASING }
data class CameraLeaseTicket(val id: Long, val stamp: ModeStamp, val mode: CaptureMode)
data class CameraLeaseSnapshot(val ticket: CameraLeaseTicket, val phase: CameraLeasePhase)

/** Pure ownership ledger. A timeout never authorizes reuse of an unclosed camera. */
class ExclusiveCameraLease {
    private var nextId = 0L
    var snapshot: CameraLeaseSnapshot? = null
        private set

    fun acquire(stamp: ModeStamp, mode: CaptureMode): CameraLeaseTicket? {
        if (snapshot != null) return null
        val ticket = CameraLeaseTicket(++nextId, stamp, mode)
        snapshot = CameraLeaseSnapshot(ticket, CameraLeasePhase.ACQUIRING)
        return ticket
    }

    fun acquired(ticket: CameraLeaseTicket): Boolean {
        if (snapshot?.ticket != ticket || snapshot?.phase != CameraLeasePhase.ACQUIRING) return false
        snapshot = CameraLeaseSnapshot(ticket, CameraLeasePhase.ACTIVE)
        return true
    }

    fun beginRelease(ticket: CameraLeaseTicket): Boolean {
        if (snapshot?.ticket != ticket || snapshot?.phase == CameraLeasePhase.RELEASING) return false
        snapshot = CameraLeaseSnapshot(ticket, CameraLeasePhase.RELEASING)
        return true
    }

    /** Adapter may call only after close acknowledgements and recorder/codec release. */
    fun released(ticket: CameraLeaseTicket): Boolean {
        if (snapshot?.ticket != ticket || snapshot?.phase != CameraLeasePhase.RELEASING) return false
        snapshot = null
        return true
    }
}
