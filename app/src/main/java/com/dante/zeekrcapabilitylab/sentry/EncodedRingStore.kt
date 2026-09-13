package com.dante.zeekrcapabilitylab.sentry

import java.nio.ByteBuffer

data class RingSnapshot(
    val liveBytes: Long,
    val highWaterBytes: Long,
    val capacityBytes: Long,
    val historyUs: Long,
    val completedGops: Int,
    val acceptedSamples: Long,
    val droppedGops: Long,
    val lastPtsUs: Long?,
    val formatEpochId: Long?,
    val completedHistoryUs: Long = 0,
    val payloadLiveBytes: Long = 0,
    val payloadHighWaterBytes: Long = 0,
    val budgetEvictedGops: Long = 0,
    val allocatorKind: String = "UNKNOWN",
)
enum class RingAppendResult { ACCEPTED, WAITING_FOR_SYNC, GOP_DROPPED, INVALID_PTS, MISSING_FORMAT }

/**
 * All methods run on the codec thread (or a serial test executor). Writer references are
 * released on the writer thread; only pool/reference counters cross the boundary.
 * onGop must enqueue/retain without blocking, and must never perform IO.
 */
class EncodedRingStore(
    val pool: EncodedBufferPool,
    private val targetUs: Long = 30_000_000,
    private val onGop: (EncodedGop) -> Unit = {},
) : AutoCloseable {
    init { require(targetUs > 0) }
    private val history = ArrayDeque<EncodedGop>()
    private val current = ArrayList<EncodedAccessUnit>()
    private var epoch: CodecFormatEpoch? = null
    private var currentEpoch: CodecFormatEpoch? = null
    private var lastPtsUs: Long? = null
    private var waitingForSync = true
    private var accepted = 0L
    private var dropped = 0L
    private var budgetEvicted = 0L
    private var nextEpoch = 0L

    fun setAvcFormat(width: Int, height: Int, parameters: AvcParameterSets): Boolean {
        require(width > 0 && height > 0)
        val old = epoch
        if (old != null && old.width == width && old.height == height &&
            old.csd0().let { it.remaining() >= 4 && it.apply { position(4) } == parameters.sps } &&
            old.csd1()?.let { it.remaining() >= 4 && it.apply { position(4) } == parameters.pps } == true
        ) return true
        val size = 8 + parameters.sps.remaining() + parameters.pps.remaining()
        if (size > 16_384) return false
        invalidateCurrent(countDrop = current.isNotEmpty())
        clearHistory()
        old?.release()
        epoch = null
        val buffer = pool.tryAcquire(size) ?: return false
        buffer.scratch().apply {
            putInt(1)
            put(parameters.sps.duplicate())
            putInt(1)
            put(parameters.pps.duplicate())
        }
        epoch = CodecFormatEpoch(++nextEpoch, width, height, buffer, 4 + parameters.sps.remaining())
        waitingForSync = true
        return true
    }

    fun setFormat(width: Int, height: Int, csd0: ByteBuffer, csd1: ByteBuffer?): Boolean {
        require(width > 0 && height > 0)
        val size = csd0.remaining() + (csd1?.remaining() ?: 0)
        if (!csd0.hasRemaining() || size > 16 * 1024) return false
        // A format boundary is a coverage boundary, not an opportunity to mix SPS/PPS.
        invalidateCurrent(countDrop = current.isNotEmpty())
        clearHistory()
        epoch?.release()
        epoch = null
        val buffer = pool.tryAcquire(size) ?: return false
        buffer.copyFrom(csd0, csd1)
        epoch = CodecFormatEpoch(++nextEpoch, width, height, buffer, csd0.remaining())
        waitingForSync = true
        return true
    }

    fun append(source: ByteBuffer, ptsUs: Long, sync: Boolean): RingAppendResult {
        if (epoch == null) return RingAppendResult.MISSING_FORMAT
        val previous = lastPtsUs
        if (ptsUs < 0 || previous != null && ptsUs <= previous) return RingAppendResult.INVALID_PTS
        lastPtsUs = ptsUs
        if (!source.hasRemaining()) return RingAppendResult.WAITING_FOR_SYNC
        if (sync) {
            sealCurrent()
            waitingForSync = false
        }
        if (waitingForSync) return RingAppendResult.WAITING_FOR_SYNC
        trimHistory(ptsUs)
        var buffer = pool.tryAcquire(source.remaining())
        while (buffer == null && history.isNotEmpty()) {
            history.removeFirst().release()
            budgetEvicted++
            buffer = pool.tryAcquire(source.remaining())
        }
        if (buffer == null) {
            invalidateCurrent(countDrop = true)
            return RingAppendResult.GOP_DROPPED
        }
        buffer.copyFrom(source)
        if (current.isEmpty()) {
            check(sync)
            currentEpoch = epoch!!.also { it.retain() }
        }
        current.add(EncodedAccessUnit(buffer, ptsUs, sync))
        accepted++
        return RingAppendResult.ACCEPTED
    }

    private fun trimHistory(newestPtsUs: Long) {
        // Keep the GOP spanning the start boundary; at most one sync interval of extra history.
        while (history.size > 1 && history[1].firstPtsUs <= newestPtsUs - targetUs) {
            history.removeFirst().release()
        }
    }

    private fun sealCurrent() {
        if (current.isEmpty()) return
        val gop = EncodedGop(currentEpoch!!, current.toList())
        current.clear()
        currentEpoch = null
        history.addLast(gop)
        onGop(gop)
    }

    /** Does not seal the active GOP: until the next sync/EOS it may still be invalidated. */
    fun pinHistory(): List<EncodedGop> = history.map { it.retain() }

    fun finishProducer() {
        sealCurrent()
        waitingForSync = true
    }

    private fun invalidateCurrent(countDrop: Boolean) {
        current.forEach { it.buffer.close() }
        current.clear()
        currentEpoch?.release()
        currentEpoch = null
        waitingForSync = true
        if (countDrop) dropped++
    }

    fun snapshot(): RingSnapshot {
        val first = history.firstOrNull()?.firstPtsUs ?: current.firstOrNull()?.ptsUs
        return RingSnapshot(
            pool.liveBytes, pool.highWaterBytes, pool.capacityBytes,
            if (first != null && lastPtsUs != null) (lastPtsUs!! - first).coerceAtLeast(0) else 0,
            history.size, accepted, dropped, lastPtsUs, epoch?.id,
            history.firstOrNull()?.let { (history.last().lastPtsUs - it.firstPtsUs).coerceAtLeast(0) } ?: 0,
            pool.payloadLiveBytes, pool.payloadHighWaterBytes, budgetEvicted, pool.allocatorKind,
        )
    }

    private fun clearHistory() { while (history.isNotEmpty()) history.removeFirst().release() }
    override fun close() {
        invalidateCurrent(countDrop = false)
        clearHistory()
        epoch?.release()
        epoch = null
    }
}
