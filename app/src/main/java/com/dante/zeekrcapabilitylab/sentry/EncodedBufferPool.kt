package com.dante.zeekrcapabilitylab.sentry

import java.nio.ByteBuffer
import java.util.TreeMap

/** Allocated before camera start. No byte arrays are allocated by tryAcquire or codec drain. */
class EncodedBufferPool private constructor(private val allocator: Allocator) : AutoCloseable {
    /** Retained for explicit fixed-slot tests and specialized callers. */
    constructor(slotSizes: List<Int>) : this(FixedSlots(slotSizes))
    val capacityBytes = allocator.capacityBytes
    val allocatorKind = allocator.kind
    @Volatile var liveBytes = 0L
        private set
    @Volatile var highWaterBytes = 0L
        private set
    @Volatile var payloadLiveBytes = 0L
        private set
    @Volatile var payloadHighWaterBytes = 0L
        private set
    private var nextLeaseId = 0L
    private var closed = false

    @Synchronized fun tryAcquire(size: Int): EncodedBuffer? {
        if (closed || size <= 0) return null
        val leaseId = nextLeaseId + 1
        val allocation = allocator.acquire(size, leaseId) ?: return null
        nextLeaseId = leaseId
        liveBytes += allocation.buffer.capacity()
        highWaterBytes = maxOf(highWaterBytes, liveBytes)
        payloadLiveBytes += size
        payloadHighWaterBytes = maxOf(payloadHighWaterBytes, payloadLiveBytes)
        return EncodedBuffer(this, allocation.index, leaseId, allocation.buffer, size)
    }

    @Synchronized internal fun release(index: Int, leaseId: Long, payloadSize: Int) {
        liveBytes -= allocator.release(index, leaseId)
        payloadLiveBytes -= payloadSize
        check(liveBytes >= 0 && payloadLiveBytes >= 0)
    }

    @Synchronized override fun close() {
        if (closed) return
        check(liveBytes == 0L && payloadLiveBytes == 0L) { "Cannot unmap live encoded leases" }
        allocator.close()
        closed = true
    }

    companion object {
        const val CANARY_CAPACITY_BYTES = 768 * 1024 * 1024
        // This hard cap includes CSD, pinned history and writer backlog.
        // Frame leases use 4 KiB-aligned slices instead of occupying 64 KiB / 1 MiB slots.
        fun canary() = paged(CANARY_CAPACITY_BYTES)
        fun paged(capacityBytes: Int, pageBytes: Int = 4096): EncodedBufferPool {
            validateArena(capacityBytes, pageBytes)
            return EncodedBufferPool(PagedArena(ByteBuffer.allocate(capacityBytes), pageBytes) {})
        }
        fun mapped(arena: ByteBuffer, releaseMapping: () -> Unit): EncodedBufferPool =
            EncodedBufferPool(PagedArena(arena, 4096, releaseMapping))

        private fun validateArena(capacity: Int, pageBytes: Int) {
            require(pageBytes > 0 && pageBytes and (pageBytes - 1) == 0)
            require(capacity in pageBytes..CANARY_CAPACITY_BYTES && capacity % pageBytes == 0)
            require(capacity / pageBytes <= CANARY_CAPACITY_BYTES / 4096)
        }
    }

    private data class Allocation(val index: Int, val buffer: ByteBuffer)
    private interface Allocator : AutoCloseable {
        val capacityBytes: Long
        val kind: String
        fun acquire(size: Int, leaseId: Long): Allocation?
        fun release(index: Int, leaseId: Long): Int
        override fun close() = Unit
    }
    private class FixedSlots(slotSizes: List<Int>) : Allocator {
        init { require(slotSizes.isNotEmpty() && slotSizes.size <= 4_096 && slotSizes.all { it > 0 }) }
        private class Slot(val buffer: ByteBuffer, var leaseId: Long = 0)
        private val slots = slotSizes.sorted().map { Slot(ByteBuffer.allocate(it)) }
        override val capacityBytes = slotSizes.sumOf { it.toLong() }
        override val kind = "FIXED_SLOTS"
        override fun acquire(size: Int, leaseId: Long): Allocation? {
            val index = slots.indexOfFirst { it.leaseId == 0L && it.buffer.capacity() >= size }
            if (index < 0) return null
            val slot = slots[index]
            slot.leaseId = leaseId
            return Allocation(index, slot.buffer)
        }
        override fun release(index: Int, leaseId: Long): Int {
            val slot = slots[index]
            check(slot.leaseId == leaseId) { "Buffer released after reuse or more than once" }
            slot.leaseId = 0
            return slot.buffer.capacity()
        }
    }

    /** One preallocated backing store. Free ranges coalesce; active/pinned leases never move. */
    private class PagedArena(private val arena: ByteBuffer, private val pageBytes: Int, private val releaseMapping: () -> Unit) : Allocator {
        private val capacity = arena.capacity()
        init {
            validateArena(capacity, pageBytes)
            require(!arena.isReadOnly)
        }
        private val freeRanges = TreeMap<Int, Int>().apply { put(0, capacity) }
        private val allocatedBytes = IntArray(capacity / pageBytes)
        private val leases = LongArray(capacity / pageBytes)
        override val capacityBytes = capacity.toLong()
        override val kind = "PAGED_${pageBytes}_BYTES"
        override fun close() = releaseMapping()
        override fun acquire(size: Int, leaseId: Long): Allocation? {
            if (size > arena.capacity()) return null
            val charged = ((size.toLong() + pageBytes - 1) / pageBytes * pageBytes).toInt()
            val range = freeRanges.entries.firstOrNull { it.value >= charged } ?: return null
            val start = range.key
            val available = range.value
            freeRanges.remove(start)
            if (available > charged) freeRanges[start + charged] = available - charged
            val index = start / pageBytes
            check(leases[index] == 0L)
            allocatedBytes[index] = charged
            leases[index] = leaseId
            val slice = arena.duplicate().apply { position(start); limit(start + charged) }.slice()
            return Allocation(index, slice)
        }
        override fun release(index: Int, leaseId: Long): Int {
            check(leases[index] == leaseId && allocatedBytes[index] > 0) { "Stale arena lease" }
            val charge = allocatedBytes[index]
            leases[index] = 0
            allocatedBytes[index] = 0
            var start = index * pageBytes
            var size = charge
            freeRanges.floorEntry(start)?.let { previous ->
                if (previous.key + previous.value == start) {
                    start = previous.key
                    size += previous.value
                    freeRanges.remove(previous.key)
                }
            }
            freeRanges.ceilingEntry(start)?.let { next ->
                if (start + size == next.key) {
                    size += next.value
                    freeRanges.remove(next.key)
                }
            }
            freeRanges[start] = size
            return charge
        }
    }
}

class EncodedBuffer internal constructor(
    private val pool: EncodedBufferPool,
    private val index: Int,
    private val leaseId: Long,
    private val backing: ByteBuffer,
    val size: Int,
) : AutoCloseable {
    val chargedBytes get() = backing.capacity().toLong()
    private var closed = false

    /** Called only by the producer before publishing this lease. */
    internal fun copyFrom(source: ByteBuffer) {
        check(!closed && source.remaining() == size)
        backing.clear()
        backing.put(source.duplicate())
        backing.flip()
    }

    internal fun copyFrom(first: ByteBuffer, second: ByteBuffer?) {
        check(!closed && first.remaining() + (second?.remaining() ?: 0) == size)
        backing.clear()
        backing.put(first.duplicate())
        if (second != null) backing.put(second.duplicate())
        backing.flip()
    }

    fun view(): ByteBuffer {
        check(!closed)
        return backing.asReadOnlyBuffer().apply { position(0); limit(size) }
    }

    internal fun scratch(): ByteBuffer {
        check(!closed)
        return backing.duplicate().apply { clear(); limit(size) }
    }

    override fun close() {
        check(!closed)
        closed = true
        pool.release(index, leaseId, size)
    }
}

/** CSD resides in the same fixed pool and lives until its last GOP/writer releases it. */
class CodecFormatEpoch internal constructor(
    val id: Long,
    val width: Int,
    val height: Int,
    private val data: EncodedBuffer,
    private val csd0Size: Int,
) {
    private var references = 1
    val chargedBytes get() = data.chargedBytes
    @Synchronized internal fun retain() { check(references > 0); references++ }
    @Synchronized internal fun release() { check(references > 0); if (--references == 0) data.close() }
    fun csd0(): ByteBuffer = data.view().apply { limit(csd0Size) }.slice().asReadOnlyBuffer()
    fun csd1(): ByteBuffer? = if (csd0Size == data.size) null else {
        data.view().apply { position(csd0Size) }.slice().asReadOnlyBuffer()
    }
}

data class EncodedAccessUnit(val buffer: EncodedBuffer, val ptsUs: Long, val sync: Boolean)

/** Immutable after publication. Each owner (ring or writer) has one explicit reference. */
class EncodedGop internal constructor(val epoch: CodecFormatEpoch, val samples: List<EncodedAccessUnit>) {
    init { require(samples.isNotEmpty() && samples.first().sync) }
    private var references = 1
    val firstPtsUs get() = samples.first().ptsUs
    val lastPtsUs get() = samples.last().ptsUs
    val payloadBytes = samples.sumOf { it.buffer.size.toLong() }
    val chargedBytes = samples.sumOf { it.buffer.chargedBytes }
    @Synchronized fun retain(): EncodedGop { check(references > 0); references++; return this }
    @Synchronized fun release() {
        check(references > 0)
        if (--references == 0) {
            samples.forEach { it.buffer.close() }
            epoch.release()
        }
    }
}
