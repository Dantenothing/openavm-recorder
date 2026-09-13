package com.dante.zeekrcapabilitylab.sentry.runtime

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import com.dante.zeekrcapabilitylab.sentry.EncodedGop
import com.dante.zeekrcapabilitylab.sentry.canary.CanaryHistoryInput
import com.dante.zeekrcapabilitylab.sentry.canary.syncCanaryDirectory
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** GOP references flow through a bounded queue; all journal, muxer and hash IO stays on the writer. */
class GuardEventWriter(
    private val store: GuardEventStore, initial: GuardEvent, private val executor: Executor,
    private val complete: (GuardEvent) -> Unit,
) {
    private val lock = Any()
    private var event = initial
    private val window = GuardEventWindow(initial.triggerPtsUs)
    private var input: CanaryHistoryInput? = null
    private val finished = AtomicBoolean()
    @Volatile private var reason: String? = null
    @Volatile private var accepting = true
    val targetEndPtsUs get() = synchronized(lock) { window.endUs }

    fun extend(trigger: GuardTrigger): Boolean = synchronized(lock) {
        if (!accepting || reason != null || !window.extend(trigger.ptsUs)) return false
        event = event.copy(targetEndPtsUs = window.endUs,
            triggers = (event.triggers + trigger).takeLast(64),
            omittedTriggers = event.omittedTriggers + if (event.triggers.size >= 64) 1 else 0)
        true
    }
    fun begin(pinned: List<EncodedGop>) {
        val queue = CanaryHistoryInput(pinned); input = queue
        try { executor.execute { write(queue) } } catch (t: RuntimeException) { queue.close(); throw t }
    }
    fun offer(gop: EncodedGop) {
        if (!accepting || finished.get() || reason != null) return
        if (input?.offer(gop) == false) finishPartial("WRITER_BACKLOG_LIMIT")
    }
    fun finishPartial(value: String) = synchronized(lock) { if (reason == null) reason = value; Unit }
    private fun snapshot() = synchronized(lock) { event }
    private fun update(change: (GuardEvent) -> GuardEvent) = synchronized(lock) { event = change(event); event }

    private fun write(queue: CanaryHistoryInput) {
        var muxer: MediaMuxer? = null
        var partial: File? = null
        var track = -1
        var first = -1L; var last = -1L; var samples = 0
        var epoch: Long? = null; var bytes = 0L
        var totalPayload = 0L
        var failure: String? = null
        var result: GuardEvent

        fun commitChunk() {
            val active = muxer ?: return
            val metadata = snapshot()
            val asset = GuardAsset(GuardVideoPresentation.assetName(metadata.id, metadata.assets.size + 1),
                metadata.assets.size + 1, first, last, samples)
            // Journal the exact recovery candidate before closing/renaming it.
            store.save(update { it.copy(pending = asset) })
            active.stop(); active.release(); muxer = null
            val incomplete = requireNotNull(partial)
            RandomAccessFile(incomplete, "rw").use { it.fd.sync() }
            val duration = GuardMediaVerifier.verify(incomplete, samples)
            val committed = asset.copy(bytes = incomplete.length(), sha256 = GuardMediaVerifier.hash(incomplete), actualDurationMs = duration)
            val final = File(store.directory(metadata.id), asset.name)
            check(!final.exists() && incomplete.renameTo(final)) { "SENTRY_COMMIT_RENAME_FAILED" }
            syncCanaryDirectory(final.parentFile!!)
            store.sidecar(metadata, committed)
            store.save(update { it.copy(assets = it.assets + committed, pending = null) })
            partial = null; first = -1; last = -1; samples = 0; bytes = 0
        }

        try {
            store.admit(); store.save(snapshot())
            val deadline = SystemClock.elapsedRealtime() + 180_000
            while (SystemClock.elapsedRealtime() < deadline) {
                val gop = queue.poll()
                if (gop == null) { if (reason != null) break else continue }
                try {
                    if (epoch != null && gop.epoch.id != epoch) { failure = "FORMAT_CHANGED"; break }
                    if (totalPayload + gop.payloadBytes > GuardEventStore.MAX_EVENT_BYTES) { failure = "EVENT_BYTE_LIMIT"; break }
                    if (muxer != null && gop.firstPtsUs - first >= 60_000_000) commitChunk()
                    if (muxer == null) {
                        check(snapshot().assets.size < 8) { "EVENT_SEGMENT_LIMIT" }
                        val metadata = snapshot()
                        val name = GuardVideoPresentation.assetName(metadata.id, metadata.assets.size + 1)
                        partial = File(store.directory(snapshot().id), "$name.partial")
                        check(!partial!!.exists())
                        store.save(update { it.copy(pending = GuardAsset(name, it.assets.size + 1, gop.firstPtsUs, gop.lastPtsUs, gop.samples.size)) })
                        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, gop.epoch.width, gop.epoch.height)
                        format.setByteBuffer("csd-0", gop.epoch.csd0()); gop.epoch.csd1()?.let { format.setByteBuffer("csd-1", it) }
                        muxer = MediaMuxer(partial!!.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                        track = muxer!!.addTrack(format); muxer!!.start()
                        first = gop.firstPtsUs; epoch = gop.epoch.id
                    }
                    for (sample in gop.samples) {
                        check(sample.ptsUs > last)
                        muxer!!.writeSampleData(track, sample.buffer.view(), MediaCodec.BufferInfo().apply {
                            set(0, sample.buffer.size, sample.ptsUs - first, if (sample.sync) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                        })
                        last = sample.ptsUs; samples++; bytes += sample.buffer.size; totalPayload += sample.buffer.size
                    }
                    val done = synchronized(lock) {
                        if (last >= window.endUs) { accepting = false; true } else false
                    }
                    if (done) break
                } finally { gop.release() }
            }
            synchronized(lock) { accepting = false }
            queue.close()
            commitChunk()
        } catch (t: Throwable) {
            failure = t.message?.takeIf { it.matches(Regex("[A-Z0-9_]{1,100}")) } ?: "SENTRY_WRITE_${t.javaClass.simpleName}"
        } finally {
            accepting = false
            runCatching { muxer?.release() }
            queue.close()
            // Uncommitted partial is retained, identified in the journal; never advertised as playable.
            finished.set(true)
        }
        result = GuardEventStore.withCoverage(snapshot())
        val ended = failure ?: reason ?: when {
            result.assets.isEmpty() -> "NO_VERIFIED_SEGMENTS"
            result.assets.last().lastPtsUs < result.targetEndPtsUs -> "POST_ROLL_INCOMPLETE"
            result.preAchievedUs < result.preRequestedUs -> "PRE_ROLL_WARMING"
            !result.ai.clockCalibrated -> "TRIGGER_CLOCK_NOT_CALIBRATED"
            else -> null
        }
        result = result.copy(state = if (result.assets.isEmpty()) "FAILED" else if (ended != null) "PARTIAL" else "COMPLETE", reason = ended)
        try {
            // Late AI callbacks may refer to an earlier chunk; finalize all warning metadata together.
            result.assets.forEach { store.sidecar(result, it) }
            store.save(result)
        } catch (_: Throwable) { result = result.copy(state = "PARTIAL", reason = "EVENT_JOURNAL_WRITE_FAILED") }
        complete(result)
    }
}
