package com.dante.zeekrcapabilitylab.enhancement

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.util.Size
import com.dante.zeekrcapabilitylab.BuildConfig
import com.dante.zeekrcapabilitylab.probe.camera.CameraProfileCatalog
import com.dante.zeekrcapabilitylab.probe.camera.ProfileSize
import com.dante.zeekrcapabilitylab.product.RecordingCameraCapability
import com.dante.zeekrcapabilitylab.product.RecordingSourcePolicy
import com.dante.zeekrcapabilitylab.product.SettingsStore
import com.dante.zeekrcapabilitylab.service.recorder.RecordingSourceRole
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.*

/** Explicit metadata-only collection. Never constructs a Surface, camera device or codec. */
object CapabilityCollector {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "openavm-capability-metadata").apply { isDaemon = true } }
    private val inFlight = AtomicReference<CompletableFuture<String>?>(null)

    suspend fun collect(context: Context): String {
        val app = context.applicationContext
        var job = inFlight.get()
        if (job == null) {
            val candidate = CompletableFuture<String>()
            if (inFlight.compareAndSet(null, candidate)) {
                job = candidate
                worker.execute {
                    try { candidate.complete(read(app)) }
                    catch (error: Throwable) { candidate.completeExceptionally(error) }
                    finally { inFlight.compareAndSet(candidate, null) }
                }
            } else job = inFlight.get()
        }
        // A completed job may leave the slot between the two reads; start one fresh query.
        val selected = job ?: return collect(context)
        // A Binder/native timeout cannot prove the worker stopped. Keep its slot occupied;
        // subsequent requests share it instead of accumulating more blocked threads.
        return awaitCapabilityResult(selected)
    }

    private fun text(value: String?) = value?.take(160)
    private fun size(value: Size) = buildJsonArray { add(value.width); add(value.height) }
    private fun sizes(values: Array<Size>?) = buildJsonObject {
        val ordered = values.orEmpty().distinct().sortedByDescending { it.width.toLong() * it.height }
        put("values", JsonArray(ordered.take(12).map(::size)))
        put("omitted", (ordered.size - 12).coerceAtLeast(0))
    }

    private fun read(context: Context): String {
        val started = SystemClock.elapsedRealtime()
        val manager = context.getSystemService(CameraManager::class.java)
        val idsResult = runCatching { manager.cameraIdList.toList() }
        val ids = idsResult.getOrDefault(emptyList())
        val concurrency = if (Build.VERSION.SDK_INT >= 30) runCatching { manager.concurrentCameraIds } else null
        val sets = concurrency?.getOrNull().orEmpty()
        val profiles = mutableListOf<RecordingCameraCapability>()
        val cameraReports = ids.take(8).map { id ->
            runCatching {
                val info = manager.getCameraCharacteristics(id)
                val map = info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val recorderSizes = map?.getOutputSizes(MediaRecorder::class.java)
                val preferred = CameraProfileCatalog.productPreferredProfile(recorderSizes.orEmpty().map { ProfileSize(it.width, it.height) })
                if (preferred != null) profiles += RecordingCameraCapability(id, preferred, CameraProfileCatalog.isFourLaneComposite(preferred.size))
                buildJsonObject {
                    put("id", text(id)); put("query", "OK")
                    put("hardwareLevel", info.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL))
                    put("timestampSource", info.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE))
                    put("sensorOrientation", info.get(CameraCharacteristics.SENSOR_ORIENTATION))
                    put("physicalIds", JsonArray(if (Build.VERSION.SDK_INT >= 28) info.physicalCameraIds.sorted().take(8).map { JsonPrimitive(text(it)) } else emptyList()))
                    put("mediaRecorderSizes", sizes(recorderSizes))
                    put("surfaceTextureSizes", sizes(map?.getOutputSizes(SurfaceTexture::class.java)))
                    put("aeFpsRanges", buildJsonArray { info.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty().take(12).forEach { range ->
                        add(buildJsonArray { add(range.lower); add(range.upper) })
                    } })
                    put("v4PreferredProfile", preferred?.let { buildJsonObject {
                        put("width", it.size.width); put("height", it.size.height); put("bitrateBps", it.bitrateBps)
                    } } ?: JsonNull)
                }
            }.getOrElse { error -> buildJsonObject { put("id", text(id)); put("query", "ERROR"); put("error", error.javaClass.simpleName) } }
        }
        val settings = SettingsStore.get(context)
        val mapping = RecordingSourceRole.entries.associateWith { role ->
            RecordingSourcePolicy.resolve(role, settings.cameraMapping(role), profiles)?.capability?.cameraId
        }
        val codecsResult = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter {
            it.isEncoder && it.supportedTypes.any { type -> type.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) }
        } }
        val codecs = codecsResult.getOrDefault(emptyList())
        val encoderReports = codecs.take(8).map { codec -> runCatching {
            val caps = codec.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val video = caps.videoCapabilities
            buildJsonObject {
                put("name", text(codec.name)); put("query", "OK")
                put("hardwareAccelerated", if (Build.VERSION.SDK_INT >= 29) JsonPrimitive(codec.isHardwareAccelerated) else JsonNull)
                put("maxInstancesHint", caps.maxSupportedInstances)
                val surface = MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface in caps.colorFormats
                put("surfaceInput", surface)
                put("widthAlignment", video?.widthAlignment); put("heightAlignment", video?.heightAlignment)
                put("gridCandidates", buildJsonArray {
                    for (side in listOf(2560, 1920, 1280)) for (fps in listOf(20, 30)) {
                        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, side, side).apply {
                            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                            setInteger(MediaFormat.KEY_FRAME_RATE, fps); setInteger(MediaFormat.KEY_BIT_RATE, 28_000_000)
                        }
                        val support = runCatching { surface && caps.isFormatSupported(format) && video?.areSizeAndRateSupported(side, side, fps.toDouble()) == true }
                        add(buildJsonObject {
                            put("width", side); put("height", side); put("fps", fps); put("bitrateBps", 28_000_000)
                            put("declarationAccepts", support.getOrNull()?.let(::JsonPrimitive) ?: JsonNull)
                            put("queryError", support.exceptionOrNull()?.javaClass?.simpleName)
                        })
                    }
                })
            }
        }.getOrElse { error -> buildJsonObject { put("name", text(codec.name)); put("query", "ERROR"); put("error", error.javaClass.simpleName) } } }
        val header = buildJsonObject {
            put("appVersion", BuildConfig.VERSION_NAME); put("appCode", BuildConfig.VERSION_CODE); put("source", BuildConfig.GIT_SHA)
            put("atEpochMs", System.currentTimeMillis()); put("collectionElapsedMs", SystemClock.elapsedRealtime() - started)
            put("sdk", Build.VERSION.SDK_INT); put("manufacturer", text(Build.MANUFACTURER)); put("model", text(Build.MODEL)); put("firmware", text(Build.DISPLAY))
            put("cameraEnumeration", if (idsResult.isSuccess) "OK" else idsResult.exceptionOrNull()?.javaClass?.simpleName)
            put("encoderEnumeration", if (codecsResult.isSuccess) "OK" else codecsResult.exceptionOrNull()?.javaClass?.simpleName)
            put("concurrentQuery", when { concurrency == null -> "API_UNAVAILABLE"; concurrency.isSuccess -> "OK"; else -> "QUERY_FAILED" })
            put("concurrentSets", buildJsonArray { sets.sortedBy { it.sorted().joinToString() }.take(12).forEach { add(JsonArray(it.sorted().take(8).map { id -> JsonPrimitive(text(id)) })) } })
            put("omittedConcurrentSets", (sets.size - 12).coerceAtLeast(0))
            put("roleMapping", buildJsonObject { mapping.forEach { (role, id) -> put(role.name, buildJsonObject {
                put("configured", text(settings.cameraMapping(role))); put("resolvedByV4Policy", text(id)); put("physicallyConfirmed", false)
            }) } })
            put("combinationCandidates", buildJsonArray {
                for (roles in listOf(listOf(RecordingSourceRole.SURROUND, RecordingSourceRole.CABIN), listOf(RecordingSourceRole.SURROUND, RecordingSourceRole.IR), RecordingSourceRole.entries.toList())) {
                    val selected = roles.mapNotNull { mapping[it] }
                    add(buildJsonObject {
                        put("roles", JsonArray(roles.map { JsonPrimitive(it.name) })); put("ids", JsonArray(selected.map { JsonPrimitive(text(it)) }))
                        put("evidence", if (selected.size != roles.size) "UNRESOLVED_SOURCE" else ConcurrentCapabilityPolicy.classify(selected, Build.VERSION.SDK_INT, concurrency?.isFailure == true, sets))
                    })
                }
            })
            put("sessionConfigurationQuery", "NOT_PERFORMED_NO_OUTPUTS_CREATED")
            put("gpuTextureLimit", JsonNull); put("gpuQuery", "NOT_PERFORMED_NO_EGL_CONTEXT")
            put("limitations", "Declarations do not grant recording support. Empty concurrent sets are not proof that all vendor paths are impossible. Max codec instances are an upper-bound hint. Exact session, GPU, USB and sustained-load tests remain pending.")
        }
        return CapabilityReport.encode(header, cameraReports, encoderReports, ids.size, codecs.size)
    }
}
