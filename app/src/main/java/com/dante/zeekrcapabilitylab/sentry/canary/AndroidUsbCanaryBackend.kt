package com.dante.zeekrcapabilitylab.sentry.canary

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import androidx.annotation.RequiresApi
import com.dante.zeekrcapabilitylab.sentry.*
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.serialization.encodeToString

class AndroidUsbCanaryJournal(context: Context) : UsbCanaryJournal {
    private val file = AtomicFile(File(context.filesDir, "sentry-canary/usb-journal.json"))
    override fun read(): UsbCanaryRecord? {
        if (!file.baseFile.exists()) return null
        // A corrupt journal fails closed. Never overwrite unknown ownership with a new intent.
        return file.openRead().use { SentryCanaryService.json.decodeFromString<UsbCanaryRecord>(it.readBytes().toString(Charsets.UTF_8)) }
    }
    override fun write(record: UsbCanaryRecord) {
        file.baseFile.parentFile?.mkdirs()
        val output = file.startWrite()
        try {
            output.write(SentryCanaryService.json.encodeToString(record).toByteArray(Charsets.UTF_8))
            output.fd.sync()
            file.finishWrite(output)
            syncCanaryDirectory(file.baseFile.parentFile!!)
            check(read() == record) { "USB_JOURNAL_READBACK_FAILED" }
        } catch (error: Throwable) { file.failWrite(output); throw error }
    }
}

internal fun syncCanaryDirectory(directory: File) {
    check(directory.isDirectory) { "JOURNAL_DIRECTORY_MISSING" }
    val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
    try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
}

@RequiresApi(29)
class AndroidUsbCanaryBackend(private val context: Context, private val sourceClip: File?) : UsbCanaryBackend {
    private val resolver = context.contentResolver
    private var expectedSampleDigest: ByteArray? = null
    private var expectedSampleCount: Int? = null
    private var longestWriteMs = 0L
    override fun insertPending(record: UsbCanaryRecord): String = resolver.insert(Uri.parse(record.collectionUri), ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, record.displayName)
        put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
        put(MediaStore.MediaColumns.RELATIVE_PATH, UsbCanaryRecord.RELATIVE_PATH)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    })?.toString() ?: error("USB_INSERT_FAILED")

    override fun inspect(uri: String): UsbCanaryMetadata? {
        val columns = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
        return resolver.query(Uri.parse(uri), columns, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            fun text(name: String): String? = cursor.getColumnIndex(name).takeIf { it >= 0 }?.let { cursor.getString(it) }
            UsbCanaryMetadata(uri, text(columns[0]), text(columns[1]), text(columns[2]))
        }
    }

    override fun openMux(uri: String): UsbCanaryMuxSession {
        val source = requireNotNull(sourceClip)
        check(source.isFile && source.length() <= 33L * 1024 * 1024) { "TRIGGERED_SOURCE_INVALID" }
        val fd = resolver.openFileDescriptor(Uri.parse(uri), "rw") ?: error("USB_FD_OPEN_FAILED")
        val muxer = try {
            check(Os.lseek(fd.fileDescriptor, 0, OsConstants.SEEK_SET) == 0L) { "USB_FD_NOT_SEEKABLE" }
            MediaMuxer(fd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (error: Throwable) { fd.close(); throw error }
        return object : UsbCanaryMuxSession {
            private var released = false
            private var descriptorClosed = false
            override fun writeTriggeredSamples() {
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(source.absolutePath)
                    check(extractor.trackCount == 1) { "SOURCE_TRACKS_INVALID" }
                    val format = extractor.getTrackFormat(0)
                    check(format.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_VIDEO_AVC) { "SOURCE_NOT_AVC" }
                    extractor.selectTrack(0)
                    check(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) { "SOURCE_NOT_SYNC_ALIGNED" }
                    val track = muxer.addTrack(format)
                    muxer.start()
                    val scratch = ByteBuffer.allocateDirect(1024 * 1024)
                    val first = extractor.sampleTime
                    var previous = -1L
                    var count = 0
                    val digest = MessageDigest.getInstance("SHA-256")
                    val deadline = SystemClock.elapsedRealtime() + 60_000
                    while (extractor.sampleTime >= 0) {
                        check(SystemClock.elapsedRealtime() < deadline && ++count <= 10_000) { "USB_WRITE_TIME_OR_SAMPLE_LIMIT" }
                        check(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) { "ENCRYPTED_SAMPLE_REJECTED" }
                        val pts = extractor.sampleTime
                        check(pts > previous) { "SOURCE_PTS_INVALID" }
                        scratch.clear()
                        val size = extractor.readSampleData(scratch, 0)
                        check(size in 1..scratch.capacity()) { "SOURCE_SAMPLE_SIZE_INVALID" }
                        val info = MediaCodec.BufferInfo().apply {
                            set(0, size, pts - first, if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                        }
                        digest.update(scratch.asReadOnlyBuffer().apply { position(0); limit(size) })
                        val writeStarted = SystemClock.elapsedRealtime()
                        muxer.writeSampleData(track, scratch, info)
                        longestWriteMs = maxOf(longestWriteMs, SystemClock.elapsedRealtime() - writeStarted)
                        previous = pts
                        if (!extractor.advance()) break
                    }
                    expectedSampleDigest = digest.digest()
                    expectedSampleCount = count
                } finally { extractor.release() }
            }
            override fun stopAndRelease(): Long {
                val started = SystemClock.elapsedRealtime()
                muxer.stop()
                val duration = SystemClock.elapsedRealtime() - started
                muxer.release()
                released = true
                return duration
            }
            override fun syncAndClose() {
                fd.fileDescriptor.sync()
                fd.close()
                descriptorClosed = true
            }
            override fun abortClose() {
                if (!released) runCatching { muxer.release() }
                if (!descriptorClosed) runCatching { fd.close() }
            }
        }
    }

    override fun verify(uri: String): UsbCanaryVerification {
        val target = Uri.parse(uri)
        var count = 0
        val fd = resolver.openFileDescriptor(target, "r") ?: error("USB_VERIFY_OPEN_FAILED")
        fd.use {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(it.fileDescriptor)
                check(extractor.trackCount == 1) { "USB_TRACK_COUNT_INVALID" }
                check(extractor.getTrackFormat(0).getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_VIDEO_AVC) { "USB_TRACK_FORMAT_INVALID" }
                extractor.selectTrack(0)
                check(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) { "USB_FIRST_SAMPLE_NOT_SYNC" }
                var previous = -1L
                val sampleDigest = MessageDigest.getInstance("SHA-256")
                val scratch = ByteBuffer.allocateDirect(1024 * 1024)
                while (extractor.sampleTime >= 0) {
                    check(++count <= 10_000 && extractor.sampleTime > previous) { "USB_SAMPLE_TIMELINE_INVALID" }
                    previous = extractor.sampleTime
                    scratch.clear()
                    val size = extractor.readSampleData(scratch, 0)
                    check(size in 1..scratch.capacity()) { "USB_VERIFY_SAMPLE_SIZE_INVALID" }
                    scratch.position(0)
                    scratch.limit(size)
                    sampleDigest.update(scratch)
                    if (!extractor.advance()) break
                }
                expectedSampleCount?.let { check(count == it) { "USB_SAMPLE_COUNT_MISMATCH" } }
                expectedSampleDigest?.let { check(sampleDigest.digest().contentEquals(it)) { "USB_SAMPLE_DIGEST_MISMATCH" } }
            } finally { extractor.release() }
        }
        val decoded = resolver.openFileDescriptor(target, "r")!!.use {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(it.fileDescriptor)
                val bitmap = retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 1280)
                val okay = bitmap != null
                bitmap?.recycle()
                okay
            } finally { retriever.release() }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        resolver.openInputStream(target)!!.use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                bytes += n
                check(bytes <= CanaryEventWriter.MAX_CLIP_BYTES + 1024 * 1024) { "USB_CANARY_BYTE_LIMIT" }
                digest.update(buffer, 0, n)
            }
        }
        return UsbCanaryVerification(decoded, count, bytes, digest.digest().joinToString("") { "%02x".format(it) }, longestWriteMs)
    }

    override fun publish(uri: String) {
        check(resolver.update(Uri.parse(uri), ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1) {
            "USB_PUBLISH_FAILED"
        }
    }
    override fun delete(uri: String) { check(resolver.delete(Uri.parse(uri), null, null) == 1) { "USB_CLEANUP_FAILED" } }
}
