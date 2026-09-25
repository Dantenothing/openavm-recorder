package com.dante.zeekrcapabilitylab.preflight.continuous

import android.content.Context
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.dante.zeekrcapabilitylab.preflight.*
import kotlinx.serialization.json.*
import java.nio.ByteBuffer

/** Fresh extractor + fresh decoder for EACH CLOSED FILE. CPU YUV output has no lossy Surface queue. */
@androidx.annotation.RequiresApi(29)
internal class ProbeFileDecoder(private val context: Context) {
    private var extractor: MediaExtractor? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var decoder: MediaCodec? = null
    private var image: Image? = null
    var cleanupConfirmed = false; private set

    fun inspect(uri: Uri, file: ProbeContinuousEncoder.Closed, expectedMetadata: JsonObject,
        spec: ContinuousProbeSpec, nonce: Int, verifier: SyntheticSegmentVerifier, cancelled: () -> Boolean,
        realCamera: Boolean = false): JsonObject {
        var started = false
        try {
            val x = MediaExtractor().also { extractor = it }
            descriptor = requireNotNull(context.contentResolver.openFileDescriptor(uri,"r"))
            x.setDataSource(descriptor!!.fileDescriptor)
            val metadataTrack = (0 until x.trackCount).singleOrNull { x.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == ProbeContinuousEncoder.METADATA_MIME }
                ?: error("IN_FILE_LAYOUT_TRACK_UNAVAILABLE")
            x.selectTrack(metadataTrack)
            val bytes = ByteBuffer.allocate(16 * 1024)
            val length = x.readSampleData(bytes,0)
            check(length in 1..bytes.capacity() && x.sampleTime == 0L) { "IN_FILE_LAYOUT_SAMPLE_INVALID" }
            bytes.position(0)
            val recovered = Json.parseToJsonElement(ByteArray(length).also { bytes.get(it) }.toString(Charsets.UTF_8)).jsonObject
            check(recovered == expectedMetadata) { "IN_FILE_LAYOUT_MISMATCH" }
            check(!x.advance()) { "EXTRA_LAYOUT_SAMPLE" }; x.unselectTrack(metadataTrack)
            val track = (0 until x.trackCount).singleOrNull { x.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ?: error("SINGLE_VIDEO_TRACK_REQUIRED")
            val format = x.getTrackFormat(track)
            check(format.getInteger(MediaFormat.KEY_WIDTH) == spec.layout.encoded.width &&
                format.getInteger(MediaFormat.KEY_HEIGHT) == spec.layout.encoded.height) { "DECODE_RASTER_MISMATCH" }
            val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else -1
            check(kotlin.math.abs(duration - (file.endUs - file.baseUs)) <= 20) { "MP4_LAST_SAMPLE_DURATION_MISMATCH" }
            x.selectTrack(track); x.seekTo(0,MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val firstKey = x.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
            verifier.beginSegment(file.baseUs,firstKey)
            // Retain file-owned CSD. No encoder output format or previous decoder state is injected.
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            val active = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME))).also { decoder = it }
            active.configure(format,null,null,0); active.start(); started = true
            val info = MediaCodec.BufferInfo()
            val until = SystemClock.elapsedRealtime() + if(realCamera) (file.endUs-file.baseUs)/1000+30_000 else 45_000
            var inputEos = false; var outputEos = false; var inputSamples = 0
            var colorModel: String? = null; var colorMaxError = 0.0
            val ids = ArrayList<Int?>(); val pts = ArrayList<Long>()
            while (!outputEos) {
                check(!cancelled()) { "TEST_CANCELLED" }
                check(SystemClock.elapsedRealtime() < until && ids.size <= if(realCamera)25_000 else 900) { "DECODE_WATCHDOG" }
                if (!inputEos) {
                    val index = active.dequeueInputBuffer(0)
                    if(index >= 0) {
                        val input = requireNotNull(active.getInputBuffer(index)); input.clear()
                        val sampleSize = x.readSampleData(input,0)
                        if(sampleSize < 0) {
                            active.queueInputBuffer(index,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEos = true
                        } else {
                            check(sampleSize <= input.capacity()) { "DECODE_INPUT_BUFFER_SMALL" }
                            active.queueInputBuffer(index,0,sampleSize,x.sampleTime,0); inputSamples++; x.advance()
                        }
                    }
                }
                val out = active.dequeueOutputBuffer(info,5_000)
                if(out >= 0) {
                    try {
                        if(info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val pic = requireNotNull(active.getOutputImage(out)) { "CPU_DECODE_IMAGE_UNAVAILABLE" }.also { image = it }
                            check(pic.planes.size == 3 && pic.cropRect.width() == spec.layout.encoded.width &&
                                pic.cropRect.height() == spec.layout.encoded.height) { "CPU_YUV_LAYOUT_UNAVAILABLE" }
                            val cell = ProbeFramePattern.cellWidth(spec.layout.input.width)
                            val columns = List(3) { col -> IntArray(56) { bit ->
                                channel(pic,0,col*spec.layout.input.width + 16 + bit*cell + cell/2,16) } }
                            val id = ProbeFramePattern.readFrame(nonce,columns)
                            verifier.decoded(DecodedProbeFrame(id?.toLong(),info.presentationTimeUs)); ids += id; pts += info.presentationTimeUs
                            if(!realCamera && id != null && (ids.size == 1 || ids.size % 30 == 0)) {
                                val color = inspectColors(pic,spec.layout,nonce,id,colorModel)
                                colorModel = color.first; colorMaxError = maxOf(colorMaxError,color.second)
                            }
                            pic.close(); image = null
                        }
                        if(info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputEos = true
                    } finally {
                        image?.close(); image = null
                        active.releaseOutputBuffer(out,false)
                    }
                }
            }
            verifier.endSegment(true)
            check(inputSamples == file.frames && ids.size == file.frames) { "DECODE_SAMPLE_COUNT_MISMATCH" }
            return obj("file" to file.index,"metadataReadback" to "PASS","coldDecoder" to active.name,
                "outputConsumption" to "CPU_YUV_EVERY_OUTPUT_BUFFER","decoderEos" to outputEos,
                "firstKey" to firstKey,"inputSamples" to inputSamples,"frames" to ids.size,"imageFrameIds" to ids,
                "frameIdentitySummary" to obj("first" to ids.firstOrNull(),"last" to ids.lastOrNull(),
                    "unreadable" to ids.count { it==null },"nonConsecutivePairs" to ids.zipWithNext().count { (a,b) -> a==null || b==null || b!=a+1 }),
                "filePtsUs" to pts,"basePtsUs" to file.baseUs,"durationUs" to duration,
                "ptsRoundingToleranceUs" to if(realCamera)120 else 20,"durationRoundingToleranceUs" to 20,
                "colorRoundTrip" to obj("status" to if(realCamera) "NOT_TESTED_REAL_SCENE_HAS_NO_COLOR_ORACLE" else if(colorModel != null) "PASS" else "INCOMPLETE",
                    "inferredSyntheticMatrix" to colorModel,"maximumYuvError" to colorMaxError,
                    "vehicleColorSpace" to "UNKNOWN_NOT_TESTED"))
        } finally {
            var clean = true
            try { image?.close(); image = null } catch (_:Throwable) { clean = false }
            if (image == null) {
                if(started) runCatching { decoder?.stop() } // release acknowledges ownership, stop error is not proof of a leak
                try { decoder?.release(); decoder = null } catch (_:Throwable) { clean = false }
            } else clean = false
            try { extractor?.release(); extractor = null } catch (_:Throwable) { clean = false }
            try { descriptor?.close(); descriptor = null } catch (_:Throwable) { clean = false }
            cleanupConfirmed = clean
            if(!clean) {
                synchronized(PreflightRuntime.retained) { PreflightRuntime.retained += this }
                throw PreflightCleanupUnconfirmed()
            }
        }
    }
    private fun channel(image:Image,index:Int,x:Int,y:Int):Int {
        val plane = image.planes[index]; val scale = if(index==0) 1 else 2
        val offset = plane.buffer.position() + (y + image.cropRect.top)/scale*plane.rowStride +
            (x + image.cropRect.left)/scale*plane.pixelStride
        return plane.buffer.get(offset).toInt() and 255
    }
    private fun inspectColors(pic:Image,layout:StripRepackLayout,nonce:Int,frame:Int,fixed:String?):Pair<String,Double> {
        // Multiple known patches away from edges. Matrix is inferred for synthetic RGB only.
        val points = (0..2).flatMap { col -> listOf(48,112,176).map { y -> col*layout.input.width+80 to y } } +
            listOf(2*layout.input.width+80 to (layout.input.height - 2*layout.stripHeight - 10),
                2*layout.input.width+80 to (layout.input.height - 2*layout.stripHeight + 6))
        val models = listOf("BT601_LIMITED","BT709_LIMITED","BT601_FULL","BT709_FULL").filter { fixed==null || it==fixed }
        val winner = models.map { model ->
            var max = 0.0
            for ((x,y) in points) {
                val rgb=ProbeFramePattern.rgbAtEncoded(x,y,layout,nonce,frame)
                val r=(rgb shr 16 and 255).toDouble(); val g=(rgb shr 8 and 255).toDouble(); val b=(rgb and 255).toDouble()
                val kr=if(model.startsWith("BT709")) .2126 else .299; val kb=if(model.startsWith("BT709")) .0722 else .114
                val full=model.endsWith("FULL"); val luma=kr*r+(1-kr-kb)*g+kb*b
                val expected=doubleArrayOf(if(full)luma else 16+luma*219/255,
                    128+(b-luma)/(2*(1-kb))*(if(full)1.0 else 224.0/255),
                    128+(r-luma)/(2*(1-kr))*(if(full)1.0 else 224.0/255))
                for(c in 0..2) max=maxOf(max,kotlin.math.abs(channel(pic,c,x,y)-expected[c]))
            }
            model to max
        }.minBy { it.second }
        check(winner.second <= 24) { "DECODE_COLOR_OR_GEOMETRY_MISMATCH" }
        return winner
    }
}
