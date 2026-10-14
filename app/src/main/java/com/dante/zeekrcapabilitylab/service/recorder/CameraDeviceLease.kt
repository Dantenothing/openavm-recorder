package com.dante.zeekrcapabilitylab.service.recorder

/** Single native connection, explicit producer leases. Callers serialize transitions.
 * Neither a deadline nor returning from close() acknowledges native release. */
internal class CameraDeviceLease<D : Any, O : Any>(
    val cameraId: String, val reservation: Any, initialOwner: O,
) {
    enum class Phase { OPENING, OWNED, DRAINING, PARKED, CLOSING, CLOSED }
    data class Transfer(val ticket: String, val deadlineMs: Long)
    var phase = Phase.OPENING; private set
    var device: D? = null; private set
    var owner: O? = initialOwner; private set
    var transfer: Transfer? = null; private set
    private var faulted = false

    fun opened(value: D) {
        check(device == null && phase == Phase.OPENING)
        device = value; phase = Phase.OWNED
    }
    fun expect(cameraId: String, ticket: String, nowMs: Long, deadlineMs: Long): Boolean {
        if (cameraId != this.cameraId || phase != Phase.OWNED || faulted ||
            ticket.isBlank() || deadlineMs <= nowMs || deadlineMs - nowMs > MAX_HANDOFF_MS) return false
        transfer = Transfer(ticket, deadlineMs)
        return true
    }
    fun prepare(value: D, nowMs: Long): Transfer? {
        val next = transfer?.takeIf { nowMs < it.deadlineMs } ?: return null
        if (device !== value || phase != Phase.OWNED || faulted) return null
        phase = Phase.DRAINING
        return next
    }
    /** Called only with real session/sequence, encoder, output and native-call completion. */
    fun producerReleased(value: D, expected: Transfer, nowMs: Long, healthy: Boolean): Boolean {
        check(device === value && phase in setOf(Phase.DRAINING, Phase.CLOSING))
        owner = null
        val keep = healthy && !faulted && transfer == expected && nowMs < expected.deadlineMs && phase == Phase.DRAINING
        phase = if (keep) Phase.PARKED else Phase.CLOSING
        if (!keep) transfer = null
        return keep
    }
    fun canClaim(ticket: String, cameraId: String, nowMs: Long): Boolean =
        phase == Phase.PARKED && !faulted && cameraId == this.cameraId &&
            transfer?.let { it.ticket == ticket && nowMs < it.deadlineMs } == true

    fun claim(ticket: String, cameraId: String, nowMs: Long, nextOwner: O): D? {
        if (!canClaim(ticket, cameraId, nowMs)) return null
        phase = Phase.OWNED; owner = nextOwner; transfer = null
        return device
    }
    /** Returns true only when the broker, rather than an active producer, must close. */
    fun cancel(ticket: String? = null): Boolean {
        if (ticket != null && transfer?.ticket != ticket) return false
        transfer = null
        if (phase != Phase.PARKED) return false
        phase = Phase.CLOSING
        return true
    }
    fun fault(value: D): Boolean {
        if (device == null && phase == Phase.OPENING) device = value
        if (device !== value || phase == Phase.CLOSED) return false
        faulted = true; transfer = null
        if (owner != null) return false
        phase = Phase.CLOSING
        return true
    }
    fun closeAcknowledged(value: D): O? {
        if (device != null && device !== value || phase == Phase.CLOSED) return null
        val previous = owner
        device = null; owner = null; transfer = null; phase = Phase.CLOSED
        return previous
    }
    companion object { const val MAX_HANDOFF_MS = 50_000L }
}
