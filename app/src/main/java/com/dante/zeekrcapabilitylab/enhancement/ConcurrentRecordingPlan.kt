package com.dante.zeekrcapabilitylab.enhancement

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.*
import android.os.Build
import com.dante.zeekrcapabilitylab.product.ProductRecorderConfigFactory
import com.dante.zeekrcapabilitylab.service.recorder.*
import com.dante.zeekrcapabilitylab.usbexport.*

data class ConcurrentDeclarationEvidence(val requestedIds: List<String>, val declaredSets: Set<Set<String>>)
class ConcurrentPreflightRejected(val reason: String, val declaration: ConcurrentDeclarationEvidence) : IllegalStateException(reason)

data class ConcurrentChannelPlan(val config: RecorderConfig, val encoder: String)
data class ConcurrentRecordingPlan(val channels: List<ConcurrentChannelPlan>, val target: UsbExportTarget) {
    val estimatedBytes: Long get() = channels.sumOf { it.config.profile.bitrateBps.toLong() * (DURATION_SECONDS + 20) / 8 * 3 / 2 } + 16L * 1024 * 1024
    companion object { const val DURATION_SECONDS = 60 }
}

object ConcurrentRecordingPreflight {
    fun format(config: RecorderConfig): MediaFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,
        config.profile.size.width, config.profile.size.height).apply {
        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        setInteger(MediaFormat.KEY_BIT_RATE, config.profile.bitrateBps)
        setInteger(MediaFormat.KEY_FRAME_RATE, config.requestedFrameRate)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
    }
    /** Queries only: no camera opens, encoder creation or file mutation. */
    fun inspect(context: Context, secondary: RecordingSourceRole): ConcurrentRecordingPlan {
        if (Build.VERSION.SDK_INT < 30) error("CONCURRENT_API_UNAVAILABLE")
        require(secondary == RecordingSourceRole.CABIN || secondary == RecordingSourceRole.IR)
        val configs = listOf(RecordingSourceRole.SURROUND, secondary).map { role ->
            requireNotNull(ProductRecorderConfigFactory.create(context, role)) { "SOURCE_NOT_MAPPED:$role" }.copy(segmentSeconds = 60)
        }
        val ids = configs.map { it.cameraId }
        val manager = context.getSystemService(CameraManager::class.java)
        val declaredSets = manager.concurrentCameraIds
        if (ConcurrentCapabilityPolicy.classify(ids, Build.VERSION.SDK_INT, false, declaredSets) != "DECLARED_NOT_TESTED") {
            throw ConcurrentPreflightRejected("PAIR_NOT_DECLARED", ConcurrentDeclarationEvidence(ids, declaredSets))
        }
        val target = UsbExportVolumeResolver.mountedTargets(context).singleOrNull() ?: error("CONNECT_ONE_USB")
        val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder && it.isHardwareAccelerated }
        val selected = configs.map { config ->
            val size = config.profile.size
            val sizes = manager.getCameraCharacteristics(config.cameraId).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(MediaCodec::class.java).orEmpty()
            check(sizes.any { it.width == size.width && it.height == size.height }) { "CODEC_SURFACE_SIZE_NOT_DECLARED:${config.cameraId}" }
            val encoder = codecs.firstOrNull { codec -> runCatching {
                val caps = codec.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface in caps.colorFormats &&
                    caps.isFormatSupported(format(config)) && caps.videoCapabilities?.areSizeAndRateSupported(size.width, size.height, config.requestedFrameRate.toDouble()) == true
            }.getOrDefault(false) } ?: error("NO_DECLARED_HARDWARE_ENCODER:${config.cameraId}")
            ConcurrentChannelPlan(config, encoder.name)
        }
        selected.groupBy { it.encoder }.forEach { (name, uses) ->
            val max = codecs.first { it.name == name }.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).maxSupportedInstances
            check(uses.size <= max) { "ENCODER_INSTANCE_LIMIT" }
        }
        return ConcurrentRecordingPlan(selected, target).also { plan ->
            check((target.freeBytes ?: -1L) >= plan.estimatedBytes + UsbRecordingFreeSpace.RESERVE_BYTES) { "USB_SPACE_LOW" }
            check(plan.estimatedBytes <= configs.minOf { it.usbQuotaBytes }) { "USB_QUOTA_LOW" }
        }
    }
}
