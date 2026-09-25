package com.dante.zeekrcapabilitylab.preflight.continuous

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import com.dante.zeekrcapabilitylab.preflight.obj
import kotlinx.serialization.json.JsonObject
import java.nio.ByteBuffer

/** The very same builder is used for declaration queries and configure(). No camera format override. */
@androidx.annotation.RequiresApi(29)
internal object ProbeCodecFormat {
    fun create(spec: ProbeVideoEncoding): MediaFormat = MediaFormat.createVideoFormat(spec.mime,
        spec.raster.width, spec.raster.height).apply {
        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        setInteger(MediaFormat.KEY_BIT_RATE, spec.bitrateBps)
        setInteger(MediaFormat.KEY_FRAME_RATE, spec.frameRate)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, spec.iFrameIntervalSeconds)
        spec.profile?.let { setInteger(MediaFormat.KEY_PROFILE, if (it == ProbeAvcProfile.BASELINE)
            MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline else MediaCodecInfo.CodecProfileLevel.AVCProfileHigh) }
        spec.maxBFrames?.let { setInteger(MediaFormat.KEY_MAX_B_FRAMES, it) }
        spec.bitrateMode?.let { setInteger(MediaFormat.KEY_BITRATE_MODE,modeValue(it)) }
    }

    private fun modeValue(mode:ProbeBitrateMode)=when(mode) {
        ProbeBitrateMode.VBR->MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
        ProbeBitrateMode.CBR->MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
    }

    fun rateControls(caps:MediaCodecInfo.CodecCapabilities):JsonObject {
        val encoderQuery=runCatching { requireNotNull(caps.encoderCapabilities) }
        val encoder=encoderQuery.getOrNull()
        val modes=listOf("CQ" to 0,"VBR" to 1,"CBR" to 2,"CBR_FD" to 3).map { (name,value) ->
            if(value==3 && Build.VERSION.SDK_INT<31)obj("mode" to name,"value" to value,
                "supported" to null,"status" to "API_BELOW_31")
            else runCatching { obj("mode" to name,"value" to value,"supported" to requireNotNull(encoder).isBitrateModeSupported(value),
                "status" to "DECLARED","allowedInRateTrial" to (name in setOf("VBR","CBR"))) }
                .getOrElse { obj("mode" to name,"value" to value,"supported" to null,"status" to "UNAVAILABLE","errorType" to it.javaClass.simpleName) }
        }
        return obj("encoderCapabilitiesStatus" to if(encoderQuery.isSuccess)"DECLARED" else "UNAVAILABLE",
            "encoderCapabilitiesErrorType" to encoderQuery.exceptionOrNull()?.javaClass?.simpleName,
            "modes" to modes,"qualityRange" to runCatching { requireNotNull(encoder).qualityRange.let {listOf(it.lower,it.upper)} }.getOrNull(),
            "complexityRange" to runCatching { requireNotNull(encoder).complexityRange.let {listOf(it.lower,it.upper)} }.getOrNull(),
            "nullRangeMeaning" to "UNAVAILABLE_NOT_ZERO",
            "declaredDefaultFormat" to runCatching { observe(caps.defaultFormat) }.getOrElse {obj("status" to "UNAVAILABLE","errorType" to it.javaClass.simpleName)},
            "defaultFormatMeaning" to "DECLARATION_NOT_RUNTIME_CONFIGURATION")
    }

    fun observe(format:MediaFormat):JsonObject=ProbeFormatObservation.capture(format::containsKey) { key ->
        when(format.getValueTypeForKey(key)) {
            MediaFormat.TYPE_INTEGER->format.getInteger(key)
            MediaFormat.TYPE_LONG->format.getLong(key)
            MediaFormat.TYPE_FLOAT->format.getFloat(key)
            MediaFormat.TYPE_STRING->format.getString(key)
            else->error("UNSUPPORTED_FIELD_TYPE")
        }
    }

    fun declarations(spec: ProbeVideoEncoding): List<ProbeCodecDeclaration> =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder && !it.isAlias &&
            it.isHardwareAccelerated && it.supportedTypes.any { mime -> mime.equals(spec.mime, true) } }.map { info ->
            runCatching {
                val caps = info.getCapabilitiesForType(spec.mime)
                ProbeCodecDeclaration(info.name, spec,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface in caps.colorFormats,
                    caps.isFormatSupported(create(spec)), caps.videoCapabilities?.areSizeAndRateSupported(
                        spec.raster.width, spec.raster.height, spec.frameRate.toDouble()),
                    bitrateModeSupported=spec.bitrateMode?.let {
                        requireNotNull(caps.encoderCapabilities).isBitrateModeSupported(modeValue(it)) },rateControlCapabilities=rateControls(caps))
            }.getOrElse { ProbeCodecDeclaration(info.name, spec, null, null, null, it.javaClass.simpleName) }
        }

    fun evidence(spec: ProbeVideoEncoding): JsonObject = obj("candidate" to spec.candidateId,
        "width" to spec.raster.width, "height" to spec.raster.height, "fps" to spec.frameRate,
        "bitrateBps" to spec.bitrateBps, "profile" to spec.profile?.name, "maxBFrames" to spec.maxBFrames,
        "bitrateMode" to (spec.bitrateMode?.name ?: "UNSPECIFIED_NOT_INFERRED"),
        "omittedKeys" to listOfNotNull("latency", "level", "color-standard", "color-range", "color-transfer",
            if(spec.bitrateMode==null)"bitrate-mode" else null, if (spec.maxBFrames == null) "max-bframes" else null))

    /** MediaFormat's CSD ByteBuffers otherwise still alias codec-owned state. */
    fun snapshot(source: MediaFormat): MediaFormat = MediaFormat().apply {
        for (key in source.keys) when (source.getValueTypeForKey(key)) {
            MediaFormat.TYPE_INTEGER -> setInteger(key, source.getInteger(key))
            MediaFormat.TYPE_LONG -> setLong(key, source.getLong(key))
            MediaFormat.TYPE_FLOAT -> setFloat(key, source.getFloat(key))
            MediaFormat.TYPE_STRING -> source.getString(key)?.let { setString(key, it) }
            MediaFormat.TYPE_BYTE_BUFFER -> source.getByteBuffer(key)?.duplicate()?.let { buffer ->
                setByteBuffer(key, ByteBuffer.wrap(ByteArray(buffer.remaining()).also { buffer.get(it) }))
            }
            else -> error("FORMAT_VALUE_UNSUPPORTED")
        }
    }

    fun fingerprint(format: MediaFormat): String = com.dante.zeekrcapabilitylab.preflight.sha(
        format.keys.sorted().joinToString("\n") { key ->
            val value = if (format.getValueTypeForKey(key) == MediaFormat.TYPE_BYTE_BUFFER) {
                val bytes = format.getByteBuffer(key)!!.duplicate()
                com.dante.zeekrcapabilitylab.preflight.sha(ByteArray(bytes.remaining()).also { bytes.get(it) })
            } else when (format.getValueTypeForKey(key)) {
                MediaFormat.TYPE_INTEGER -> format.getInteger(key).toString()
                MediaFormat.TYPE_LONG -> format.getLong(key).toString()
                MediaFormat.TYPE_FLOAT -> format.getFloat(key).toString()
                else -> format.getString(key).orEmpty()
            }
            "$key=$value"
        }.toByteArray())
}
