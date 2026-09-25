package com.dante.zeekrcapabilitylab.preflight.continuous

/** CPU ownership only. All GL calls, waits and fence polling happen OUTSIDE this monitor.
 * Each reader may retain one slot. Encoder gets every publication in order; displays get latest.
 * Detach cancels future acquisition; it never revokes an outstanding GPU lease.
 */
internal class SharedInputFramePool(val capacity: Int = 4) {
    enum class Reader { ENCODER, DISPLAY_A, DISPLAY_B }
    data class Write(val slot: Int, val ticket: Long)
    data class Read(val slot: Int, val ticket: Long, val frame: Int, val timestampNs: Long, val reader: Reader)
    private class Slot {
        var ticket = 0L
        var writing = false
        var published = false
        var encoderPending = false
        var frame = -1
        var timestamp = 0L
        val leases = mutableSetOf<Reader>()
    }
    private val slots = Array(capacity) { Slot() }
    private val attached = Reader.entries.toMutableSet()
    private var nextTicket = 0L
    private var lastFrame = -1
    private var lastTimestamp = 0L
    private var terminal = false
    init { require(capacity == 4) }

    @Synchronized fun reserve(): Write? {
        if (terminal) return null
        val index = slots.indices.filter { !slots[it].writing && !slots[it].encoderPending && slots[it].leases.isEmpty() }
            .minByOrNull { slots[it].frame } ?: return null
        val slot = slots[index]
        slot.writing = true; slot.published = false; slot.ticket = ++nextTicket
        return Write(index, slot.ticket)
    }
    @Synchronized fun publish(write: Write, frame: Int, timestampNs: Long) {
        val slot = slots[write.slot]
        check(!terminal && slot.writing && slot.ticket == write.ticket)
        check(frame > lastFrame && timestampNs > lastTimestamp)
        slot.frame = frame; slot.timestamp = timestampNs; slot.writing = false
        slot.published = true; slot.encoderPending = true
        lastFrame = frame; lastTimestamp = timestampNs
    }
    @Synchronized fun cancelWrite(write: Write, gpuCompleted: Boolean) {
        val slot = slots[write.slot]
        check(slot.writing && slot.ticket == write.ticket && gpuCompleted)
        slot.writing = false
    }
    @Synchronized fun acquire(reader: Reader, afterFrame: Int = -1): Read? {
        if (reader !in attached || slots.any { reader in it.leases }) return null
        val candidates = slots.indices.filter {
            val s = slots[it]
            !s.writing && s.published && s.frame > afterFrame &&
                (reader != Reader.ENCODER || s.encoderPending)
        }
        val index = if (reader == Reader.ENCODER) candidates.minByOrNull { slots[it].frame }
            else candidates.maxByOrNull { slots[it].frame }
        index ?: return null
        val slot = slots[index]; slot.leases += reader
        if (reader == Reader.ENCODER) slot.encoderPending = false
        return Read(index, slot.ticket, slot.frame, slot.timestamp, reader)
    }
    @Synchronized fun release(read: Read, gpuCompleted: Boolean) {
        val slot = slots[read.slot]
        check(slot.ticket == read.ticket && read.reader in slot.leases)
        check(gpuCompleted) { "GPU_READ_NOT_ACKNOWLEDGED" }
        slot.leases -= read.reader
    }
    @Synchronized fun detach(reader: Reader) { require(reader != Reader.ENCODER); attached -= reader }
    @Synchronized fun attach(reader: Reader) {
        check(!terminal && slots.none { reader in it.leases }); attached += reader
    }
    @Synchronized fun stopPublishing() { terminal = true }
    @Synchronized fun pendingEncoder() = slots.count { it.encoderPending || Reader.ENCODER in it.leases }
    @Synchronized fun outstanding() = slots.sumOf { it.leases.size } + slots.count { it.writing }
    @Synchronized fun latestFrame() = lastFrame
}
