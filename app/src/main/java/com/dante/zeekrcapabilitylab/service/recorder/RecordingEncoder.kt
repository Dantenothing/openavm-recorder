package com.dante.zeekrcapabilitylab.service.recorder

import android.media.MediaRecorder
import android.view.Surface

/** Camera-facing ownership stays with RecorderSession and its producer-close fence. */
internal interface RecordingEncoder {
    val surface: Surface
    val name: String
    fun prepare(config: RecorderConfig, output: RecordingOutputHandle)
    fun start()
    fun stop()
    fun reset()
    fun release()
    fun abandonOutput() = Unit
}

internal open class LegacyRecordingEncoder(private val onError: (RecordingEncoder, String) -> Unit) : RecordingEncoder {
    protected val recorder = MediaRecorder()
    override val name = "MEDIA_RECORDER"
    override val surface: Surface get() = recorder.surface
    override fun prepare(config: RecorderConfig, output: RecordingOutputHandle) {
        if (output is UsbMediaStoreRecordingOutputHandle) recorder.setOnErrorListener { _, what, extra ->
            onError(this, "USB_MEDIA_RECORDER_ERROR:what=$what:extra=$extra")
        }
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        recorder.setVideoSize(config.profile.size.width, config.profile.size.height)
        try { recorder.setVideoFrameRate(config.requestedFrameRate) }
        catch (t: Throwable) { if (config.strictFrameRate) throw IllegalStateException("ENCODER_FRAME_RATE_REJECTED", t) }
        config.captureRateFpsOrNull()?.let {
            try { recorder.setCaptureRate(it) }
            catch (t: Throwable) { throw IllegalStateException("TIME_LAPSE_CAPTURE_RATE_REJECTED source=${config.source.sourceRole} rate=$it", t) }
        }
        recorder.setVideoEncodingBitRate(config.profile.bitrateBps)
        beforePrepare(config, output)
        output.bind(recorder)
        recorder.prepare()
    }
    override fun start() = recorder.start()
    override fun stop() = recorder.stop()
    override fun reset() = recorder.reset()
    override fun release() = recorder.release()
    protected open fun beforePrepare(config: RecorderConfig, output: RecordingOutputHandle) = Unit
}
