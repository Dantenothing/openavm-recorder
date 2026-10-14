package com.dante.zeekrcapabilitylab.mirror

internal data class CabinPreviewFormat(val mime: String, val width: Int, val height: Int, val csd: List<ByteArray>) {
    fun matches(other: CabinPreviewFormat) = mime == other.mime && width == other.width && height == other.height &&
        csd.size == other.csd.size && csd.zip(other.csd).all { (a, b) -> a.contentEquals(b) }
}

internal data class CabinPreviewFrame(val sequence: Long, val bytes: ByteArray, val ptsUs: Long, val keyframe: Boolean)
internal data class CabinPreviewRead(val format: CabinPreviewFormat?, val revision: Long,
    val frames: List<CabinPreviewFrame>, val reset: Boolean, val updatedAtMs: Long?, val closed: Boolean)

/** Optional display cache, never the recorder's delivery queue. Holds only the latest complete GOP.
 * Sample arrays are already immutable copies owned by the encoder; no codec/native calls or IO
 * can run under this lock. A slow/absent viewer drops its own history, never recording samples. */
internal class CabinPreviewBuffer(val sessionId: String, private val maxBytes: Int = 8 * 1024 * 1024,
    private val maxFrames: Int = 90) {
    private var currentFormat: CabinPreviewFormat? = null
    private var revision = 0L
    private var sequence = 0L
    private val frames = ArrayList<CabinPreviewFrame>()
    private var bytes = 0
    private var closed = false
    private var updated: Long? = null

    @Synchronized fun format(value: CabinPreviewFormat) {
        if (closed || currentFormat?.matches(value) == true) return
        require(value.width > 0 && value.height > 0 && value.csd.sumOf { it.size } <= 128 * 1024)
        currentFormat = value; revision++; clear()
    }

    @Synchronized fun frame(data: ByteArray, ptsUs: Long, keyframe: Boolean, nowMs: Long) {
        if (closed || currentFormat == null || data.isEmpty()) return
        sequence++
        if (keyframe) clear()
        if (!keyframe && frames.isEmpty()) return
        if (data.size > maxBytes - bytes || frames.size >= maxFrames ||
            frames.firstOrNull()?.let { ptsUs < it.ptsUs || ptsUs - it.ptsUs > 2_000_000L } == true) {
            clear(); return
        }
        frames += CabinPreviewFrame(sequence, data, ptsUs, keyframe)
        bytes += data.size; updated = nowMs
    }

    @Synchronized fun read(afterSequence: Long): CabinPreviewRead {
        val first = frames.firstOrNull()?.sequence
        val reset = first != null && (afterSequence == 0L || afterSequence < first - 1 || afterSequence > sequence)
        return CabinPreviewRead(currentFormat, revision,
            if (reset) frames.toList() else frames.filter { it.sequence > afterSequence }, reset, updated, closed)
    }

    @Synchronized fun state() = CabinPreviewRead(currentFormat, revision, emptyList(), false, updated, closed)

    @Synchronized fun close() { closed = true; currentFormat = null; clear() }
    private fun clear() { frames.clear(); bytes = 0; updated = null }
}
