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
import kotlin.math.roundToInt

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
            extractor.selectTrack(trackIndex)
            output = FileOutputStream(pcm)

            var frames = 0L
            var rate = -1
            var channels = -1

            if (mime == "audio/raw") {
                rate = trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                channels = trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                val encoding = if (trackFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                    trackFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                } else {
                    AudioFormat.ENCODING_PCM_16BIT
                }
                val buffer = ByteBuffer.allocate(64 * 1024)
                while (true) {
                    cancellation.check()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    buffer.position(0)
                    buffer.limit(size)
                    val bytes = ByteArray(size)
                    buffer.get(bytes)
                    frames += writePcmBytes(output, bytes, size, encoding, channels)
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
                while (!outputEos) {
                    cancellation.check()
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
                            if (buffer != null) {
                                val size = info.size
                                val bytes = ByteArray(size)
                                buffer.position(0)
                                buffer.get(bytes)
                                frames += writePcmBytes(output, bytes, size, encoding, channels)
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
            val displayName = queryDisplayName(context, uri)
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
        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            val shorts = ByteArray(size / 2)
            var si = 0
            var i = 0
            while (i + 4 <= size) {
                val bits = (bytes[i].toInt() and 0xff) or
                    ((bytes[i + 1].toInt() and 0xff) shl 8) or
                    ((bytes[i + 2].toInt() and 0xff) shl 16) or
                    ((bytes[i + 3].toInt() and 0xff) shl 24)
                val f = Float.fromBits(bits)
                val s = when {
                    f >= 1f -> 32767
                    f <= -1f -> -32768
                    else -> (f * 32767f).roundToInt().coerceIn(-32768, 32767)
                }
                shorts[si++] = (s and 0xff).toByte()
                shorts[si++] = ((s shr 8) and 0xff).toByte()
                i += 4
            }
            output.write(shorts, 0, si)
            return (si / 2).toLong() / channels
        }
        output.write(bytes, 0, size)
        return (size / 2).toLong() / channels
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
