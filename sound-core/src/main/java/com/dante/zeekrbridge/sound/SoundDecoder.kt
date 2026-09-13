package com.dante.zeekrbridge.sound

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.math.min

/** Decoded PCM metadata and the streamed temp file holding little-endian 16-bit PCM. */
data class PcmMeta(
    val sampleRate: Int,
    val channels: Int,
    val frameCount: Long,
) {
    val durationMs: Long
        get() = if (sampleRate > 0) frameCount * 1000L / sampleRate else 0L
}

data class DecodedSound(
    val name: String,
    val formatLabel: String,
    val mimeType: String,
    val uri: Uri,
    val pcmFile: File,
    val meta: PcmMeta,
)

/**
 * Decodes a SAF audio URI to 16-bit little-endian PCM using the device's
 * MediaExtractor/MediaCodec decoders. PCM is streamed to a temp file, so memory
 * stays bounded for long files. Cancellation and missing decoders produce
 * explicit, user-visible errors.
 */
object SoundDecoder {
    private const val TIMEOUT_US = 10_000L

    fun decode(
        context: Context,
        uri: Uri,
        cancellation: SoundCancellation,
        limits: SoundDecodeLimits = SoundDecodeLimits(),
        onProgress: (Float) -> Unit = {},
    ): DecodedSound {
        val dir = File(context.cacheDir, "sound-decode").apply { mkdirs() }
        val id = UUID.randomUUID().toString()
        val pcm = File(dir, "$id.pcm")
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var output: FileOutputStream? = null
        try {
            try {
                extractor.setDataSource(context, uri, null)
            } catch (t: Throwable) {
                throw SoundInputException("源文件无法读取或读取权限失效，请重新选择文件", "SOURCE_UNREADABLE", t)
            }
            var trackIndex = -1
            var trackFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i
                    trackFormat = f
                    break
                }
            }
            if (trackIndex < 0 || trackFormat == null) {
                throw SoundInputException("源文件没有可识别的音频轨道", "NO_AUDIO_TRACK")
            }
            val mime = trackFormat.getString(MediaFormat.KEY_MIME)
                ?: throw SoundInputException("无法读取音频格式", "NO_AUDIO_TRACK")
            val durationUs = if (trackFormat.containsKey(MediaFormat.KEY_DURATION)) {
                trackFormat.getLong(MediaFormat.KEY_DURATION)
            } else {
                -1L
            }
            limits.check(durationUs.coerceAtLeast(0) / 1000, 0)
            if (dir.usableSpace < limits.keepFreeBytes)
                throw SoundIoException("车机存储空间不足", "SPACE_LOCAL")
            extractor.selectTrack(trackIndex)
            output = FileOutputStream(pcm)

            var frames = 0L
            var rate = -1
            var channels = -1
            var nextSpaceCheck = 0L
            fun checkOutputBudget() {
                val bytes = frames * channels * 2L
                limits.check(if (rate > 0) frames * 1000 / rate else 0, bytes)
                if (bytes >= nextSpaceCheck) {
                    if (dir.usableSpace < limits.keepFreeBytes)
                        throw SoundIoException("车机存储空间不足", "SPACE_LOCAL")
                    nextSpaceCheck = bytes + 1024 * 1024
                }
            }

            if (mime == "audio/raw") {
                rate = trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                channels = trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                require(rate in 1..384_000 && channels in 1..2) { "Unsupported audio layout" }
                val encoding = if (trackFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                    trackFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                } else {
                    AudioFormat.ENCODING_PCM_16BIT
                }
                val buffer = ByteBuffer.allocate(64 * 1024)
                while (true) {
                    cancellation.check()
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    buffer.position(0)
                    buffer.limit(size)
                    val bytes = ByteArray(size)
                    buffer.get(bytes)
                    frames += writePcmBytes(output, bytes, size, encoding, channels)
                    checkOutputBudget()
                    if (durationUs > 0) {
                        onProgress((extractor.sampleTime.toFloat() / durationUs).coerceIn(0f, 1f))
                    }
                    extractor.advance()
                }
            } else {
                codec = try {
                    MediaCodec.createDecoderByType(mime)
                } catch (t: Throwable) {
                    throw SoundInputException("系统缺少 $mime 解码器，无法解码该音频", "NO_DECODER", t)
                }
                codec.configure(trackFormat, null, null, 0)
                codec.start()
                val info = MediaCodec.BufferInfo()
                var inputEos = false
                var outputEos = false
                var lastOutputNanos = System.nanoTime()
                while (!outputEos) {
                    cancellation.check()
                    if (System.nanoTime() - lastOutputNanos > 30_000_000_000L)
                        throw SoundInputException("音轨解码没有响应，请换一个文件", "DECODE_TIMEOUT")
                    if (!inputEos) {
                        val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (inIdx >= 0) {
                            val inputBuffer = codec.getInputBuffer(inIdx)
                            if (inputBuffer == null) continue
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEos = true
                            } else {
                                if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED != 0)
                                    throw SoundInputException("该音轨受保护，无法作为本地音效导入", "PROTECTED_AUDIO")
                                val pts = extractor.sampleTime
                                codec.queueInputBuffer(inIdx, 0, sampleSize, pts, 0)
                                extractor.advance()
                            }
                        }
                    }
                    var progressed = false
                    while (true) {
                        val outIdx = codec.dequeueOutputBuffer(info, 0L)
                        if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            val fmt = codec.outputFormat
                            val r = if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else -1
                            val c = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else -1
                            if (rate < 0) {
                                rate = r
                                channels = c
                            } else if (r != rate || c != channels) {
                                throw SoundInputException("解码器输出格式中途变化，暂不支持该文件", "DECODE_FAILED")
                            }
                        } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                            break
                        } else if (outIdx >= 0) {
                            progressed = true
                            lastOutputNanos = System.nanoTime()
                            if (rate < 0) {
                                val fmt = codec.outputFormat
                                rate = if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else -1
                                channels = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else -1
                            }
                            val encoding = if (codec.outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                codec.outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            } else {
                                AudioFormat.ENCODING_PCM_16BIT
                            }
                            val buffer = codec.getOutputBuffer(outIdx)
                            if (buffer != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                require(rate in 1..384_000 && channels in 1..2) { "Unsupported audio layout" }
                                val size = info.size
                                val bytes = PcmBlockReader.read(buffer, info.offset, size)
                                frames += writePcmBytes(output, bytes, size, encoding, channels)
                                checkOutputBudget()
                                if (durationUs > 0) {
                                    val t = extractor.sampleTime
                                    if (t >= 0) onProgress((t.toFloat() / durationUs).coerceIn(0f, 1f))
                                }
                            }
                            codec.releaseOutputBuffer(outIdx, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                outputEos = true
                            }
                        }
                    }
                    if (!progressed) {
                        Thread.sleep(2)
                    }
                }
            }

            if (rate <= 0 || channels <= 0 || channels > 2 || frames <= 0) {
                throw SoundInputException("解码后没有得到有效的音频数据", "DECODE_FAILED")
            }
            output.flush()
            val meta = PcmMeta(rate, channels, frames)
            val displayName = queryDisplayName(context, uri) ?: uri.takeIf { it.scheme == "file" }?.lastPathSegment
            return DecodedSound(
                name = displayName ?: "audio",
                formatLabel = formatLabel(mime),
                mimeType = mime,
                uri = uri,
                pcmFile = pcm,
                meta = meta,
            )
        } catch (t: SoundCancelledException) {
            runCatching { pcm.delete() }
            throw t
        } catch (t: SoundInputException) {
            runCatching { pcm.delete() }
            throw t
        } catch (t: Throwable) {
            runCatching { pcm.delete() }
            throw SoundInputException("解码失败：${t.message ?: t.javaClass.simpleName}", "DECODE_FAILED", t)
        } finally {
            runCatching { output?.close() }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    fun formatLabel(mime: String?): String = when (mime) {
        "audio/mpeg", "audio/mp3" -> "MP3"
        "audio/mp4", "audio/mp4a-latm", "audio/x-m4a", "audio/aac", "audio/aac-adts" -> "M4A/AAC"
        "audio/flac", "audio/x-flac" -> "FLAC"
        "audio/ogg", "application/ogg", "audio/vorbis" -> "OGG"
        "audio/opus" -> "Opus"
        "audio/wav", "audio/x-wav", "audio/wave", "audio/raw" -> "WAV"
        else -> mime ?: "未知格式"
    }

    private fun writePcmBytes(
        output: FileOutputStream,
        bytes: ByteArray,
        size: Int,
        encoding: Int,
        channels: Int,
    ): Long {
        require(size == bytes.size)
        val pcm = DecodedPcm.to16Bit(bytes, encoding, channels)
        output.write(pcm)
        return pcm.size.toLong() / (channels * 2)
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? =
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        } catch (t: Throwable) {
            null
        }
}
