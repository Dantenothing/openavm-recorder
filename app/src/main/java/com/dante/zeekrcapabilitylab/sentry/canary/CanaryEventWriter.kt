package com.dante.zeekrcapabilitylab.sentry.canary

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.os.Build
import com.dante.zeekrcapabilitylab.sentry.*
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.Serializable

@Serializable
data class CanaryClipResult(
    val fileName: String? = null,
    val containerVerified: Boolean = false,
    val decodedFrameVerified: Boolean = false,
    val partial: Boolean = true,
    val reason: String,
    val preRollAchievedUs: Long = 0,
    val postRollAchievedUs: Long = 0,
    val byteCount: Long = 0,
    val sha256: String? = null,
)

/** Short, explicitly triggered app-private S1B asset. No USB or library publication. */
class CanaryEventWriter(
    private val directory: File,
    private val pool: EncodedBufferPool,
    private val triggerPtsUs: Long,
    private val executor: Executor,
    private val complete: (CanaryClipResult) -> Unit,
) {
    private var input: CanaryHistoryInput? = null
    private val finished = AtomicBoolean(false)
    @Volatile private var endReason: String? = null
    val isFinished get() = finished.get()
    val targetEndPtsUs = triggerPtsUs + CanaryRamPolicy.POST_ROLL_US

    fun begin(pinned: List<EncodedGop>) {
        val historyInput = CanaryHistoryInput(pinned)
        input = historyInput
        try {
            executor.execute { write(historyInput) }
        } catch (error: RuntimeException) {
            historyInput.close()
            throw error
        }
    }

    /** Returns immediately to the codec callback, even when storage stalls. */
    fun offer(gop: EncodedGop) {
        if (finished.get() || endReason != null) return
        if (input?.offer(gop) == false) endReason = "WRITER_BACKLOG_LIMIT"
    }

    fun finishPartial(reason: String) { if (endReason == null) endReason = reason }

    private fun write(input: CanaryHistoryInput) {
        val token = UUID.randomUUID().toString()
        val partial = File(directory, "$token.mp4.partial")
        val final = File(directory, "$token.mp4")
        var muxer: MediaMuxer? = null
        var started = false
        var firstPts = -1L
        var lastPts = -1L
        var payload = 0L
        var samplesWritten = 0
        var epochId: Long? = null
        var result: CanaryClipResult
        try {
            directory.mkdirs()
            val used = directory.listFiles().orEmpty().sumOf { it.length() }
            check(used + MAX_CLIP_BYTES <= MAX_DIRECTORY_BYTES && directory.usableSpace > MAX_CLIP_BYTES + 64 * 1024 * 1024) {
                "INTERNAL_CANARY_QUOTA"
            }
            muxer = MediaMuxer(partial.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var track = -1
            val deadline = android.os.SystemClock.elapsedRealtime() + 60_000
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                val gop = input.poll()
                if (gop == null) {
                    if (endReason != null) break
                    continue
                }
                try {
                    if (epochId != null && gop.epoch.id != epochId) { endReason = "FORMAT_CHANGED"; break }
                    if (!started) {
                        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, gop.epoch.width, gop.epoch.height)
                        format.setByteBuffer("csd-0", gop.epoch.csd0())
                        gop.epoch.csd1()?.let { format.setByteBuffer("csd-1", it) }
                        track = muxer.addTrack(format)
                        muxer.start()
                        started = true
                        firstPts = gop.firstPtsUs
                        epochId = gop.epoch.id
                    }
                    if (payload + gop.payloadBytes > MAX_CLIP_BYTES) { endReason = "CLIP_BYTE_LIMIT"; break }
                    for (sample in gop.samples) {
                        check(sample.ptsUs > lastPts)
                        val info = MediaCodec.BufferInfo().apply {
                            set(0, sample.buffer.size, sample.ptsUs - firstPts,
                                if (sample.sync) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                        }
                        muxer.writeSampleData(track, sample.buffer.view(), info)
                        lastPts = sample.ptsUs
                        payload += sample.buffer.size
                        samplesWritten++
                    }
                    if (lastPts >= targetEndPtsUs) break
                } finally { gop.release() }
            }
            // Stop receiving references before verification; writing never owns a second copy.
            // Later offers/stop requests cannot change the coverage already written to disk.
            val terminalReason = endReason
            input.close()
            check(started && samplesWritten > 1) { "NO_DECODABLE_SAMPLES" }
            muxer.stop() // A required part of commit; failure leaves an untrusted exact partial.
            muxer.release()
            muxer = null
            RandomAccessFile(partial, "rw").use { it.fd.sync() }
            val container = verifyContainer(partial, samplesWritten)
            check(container) { "CONTAINER_VERIFICATION_FAILED" }
            val decoded = verifyFrame(partial)
            check(decoded) { "FRAME_DECODE_FAILED" }
            val hash = hashFile(partial)
            check(partial.renameTo(final)) { "COMMIT_RENAME_FAILED" }
            syncCanaryDirectory(directory)
            val achievedPost = (lastPts - triggerPtsUs).coerceAtLeast(0)
            val achievedPre = (triggerPtsUs - firstPts).coerceAtLeast(0)
            val coverage = CanaryRamPolicy.coverage(achievedPre, achievedPost, terminalReason)
            result = CanaryClipResult(
                fileName = final.name, containerVerified = true, decodedFrameVerified = true,
                partial = coverage.partial, reason = coverage.reason,
                preRollAchievedUs = achievedPre,
                postRollAchievedUs = achievedPost, byteCount = final.length(), sha256 = hash,
            )
        } catch (error: Throwable) {
            // Only this writer's freshly generated exact filename may be removed.
            result = CanaryClipResult(reason = error.message?.takeIf { it.matches(Regex("[A-Z0-9_]{1,80}")) }
                ?: "WRITE_OR_VERIFY_${error.javaClass.simpleName}")
        } finally {
            runCatching { muxer?.release() }
            input.close()
            if (partial.exists()) partial.delete()
            finished.set(true)
        }
        complete(result)
    }

    private fun verifyContainer(file: File, expectedCount: Int): Boolean {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            if (extractor.trackCount != 1) return false
            if (extractor.getTrackFormat(0).getString(MediaFormat.KEY_MIME) != MediaFormat.MIMETYPE_VIDEO_AVC) return false
            extractor.selectTrack(0)
            if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC == 0) return false
            var count = 0
            var last = -1L
            while (extractor.sampleTime >= 0) {
                val next = extractor.sampleTime
                if (next <= last || ++count > expectedCount) return false
                last = next
                if (!extractor.advance()) break
            }
            count == expectedCount && last > 0
        } finally { extractor.release() }
    }

    private fun verifyFrame(file: File): Boolean {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val frame = if (Build.VERSION.SDK_INT >= 27) {
                retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 1280)
            } else retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            val okay = frame != null
            frame?.recycle()
            okay
        } finally { retriever.release() }
    }

    private fun hashFile(file: File): String {
        val buffer = pool.tryAcquire(16 * 1024) ?: error("HASH_BUFFER_BUDGET")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).channel.use { channel ->
                val scratch = buffer.scratch()
                while (true) {
                    scratch.clear()
                    if (channel.read(scratch) < 0) break
                    scratch.flip()
                    digest.update(scratch)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        } finally { buffer.close() }
    }

    companion object {
        const val MAX_CLIP_BYTES = 800L * 1024 * 1024
        const val MAX_DIRECTORY_BYTES = 2400L * 1024 * 1024
    }
}
