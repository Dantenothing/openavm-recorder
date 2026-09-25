package com.dante.zeekrcapabilitylab.mirror

/** CPU ownership only. Callers must also observe both GPU fences before reusing a slot.
 * No GL call may be made under this monitor: a blocked display must never block input ownership.
 */
internal class MirrorGlFramePool(val capacity: Int = 3) {
    init { require(capacity in 2..3) }
    private enum class State { FREE, WRITING, READY, READING }
    private data class Slot(var state: State = State.FREE, var serial: Long = 0,
        var timestamp: Long = 0, var ticket: Long = 0, var prior: State = State.FREE)
    data class Write(val index: Int, val ticket: Long)
    data class Read(val index: Int, val serial: Long, val timestamp: Long)
    private val slots = Array(capacity) { Slot() }
    private var nextTicket = 0L
    private var nextSerial = 0L

    @Synchronized fun reserveWrite(excluded: Set<Int> = emptySet()): Write? {
        val index = slots.indices.filter { it !in excluded && slots[it].state in setOf(State.FREE, State.READY) }
            .minByOrNull { if (slots[it].state == State.FREE) -1L else slots[it].serial } ?: return null
        val slot = slots[index]
        slot.prior = slot.state; slot.state = State.WRITING; slot.ticket = ++nextTicket
        return Write(index, slot.ticket)
    }
    @Synchronized fun cancelWrite(write: Write) {
        val slot = slots[write.index]; check(slot.state == State.WRITING && slot.ticket == write.ticket)
        slot.state = slot.prior
    }
    @Synchronized fun publish(write: Write, timestampNs: Long) {
        val slot = slots[write.index]; check(slot.state == State.WRITING && slot.ticket == write.ticket)
        check(timestampNs > 0); slot.timestamp = timestampNs; slot.serial = ++nextSerial; slot.state = State.READY
    }
    @Synchronized fun acquireLatest(afterSerial: Long): Read? {
        val index = slots.indices.filter { slots[it].state == State.READY && slots[it].serial > afterSerial }
            .maxByOrNull { slots[it].serial } ?: return null
        val slot = slots[index]; slot.state = State.READING
        return Read(index, slot.serial, slot.timestamp)
    }
    @Synchronized fun releaseRead(read: Read, consumed: Boolean) {
        val slot = slots[read.index]; check(slot.state == State.READING && slot.serial == read.serial)
        // The caller publishes the GPU read fence before relinquishing CPU ownership.
        slot.state = if (consumed) State.FREE else State.READY
    }
    @Synchronized fun inUse(): Int = slots.count { it.state in setOf(State.READING, State.WRITING) }
    @Synchronized fun latestSerial(): Long = nextSerial
}

/** Display recovery is only permitted while independent input is fresh. No camera retry is implied. */
internal class MirrorDisplayRecoveryBudget(private val maximumAttempts: Int = 2) {
    enum class Action { NONE, WAIT_FOR_SOURCE, SOURCE_RETURNED, REBUILD_DISPLAY, INPUT_THREAD_FAILED, DISPLAY_FAILED }
    var attempts = 0; private set
    var waitingForSource = false; private set
    fun evaluate(inputHeartbeatAgeMs: Long?, inputAgeMs: Long?, outputTimedOut: Boolean): Action {
        if (inputHeartbeatAgeMs != null && inputHeartbeatAgeMs > 8_000) return Action.INPUT_THREAD_FAILED
        val freshInput = inputAgeMs != null && inputAgeMs in 0..2_000
        if (waitingForSource && freshInput) {
            waitingForSource = false
            return Action.SOURCE_RETURNED
        }
        if (!outputTimedOut) return Action.NONE
        // An alive consumer with no new producer frames is not a failed display.
        // Keep its Surface and polling; only its producer owner may reopen the camera.
        if (!freshInput) { waitingForSource = true; return Action.WAIT_FOR_SOURCE }
        return if (reserve(inputAgeMs)) Action.REBUILD_DISPLAY else Action.DISPLAY_FAILED
    }
    fun reserve(inputAgeMs: Long?): Boolean {
        if (inputAgeMs == null || inputAgeMs !in 0..2_000 || attempts >= maximumAttempts) return false
        attempts++; return true
    }
}
