package com.dante.zeekrcapabilitylab.preflight

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics as CC
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.media.*
import android.os.*
import androidx.core.content.ContextCompat
import com.dante.zeekrcapabilitylab.BuildConfig
import com.dante.zeekrcapabilitylab.product.ProductRecorderConfigFactory
import com.dante.zeekrcapabilitylab.product.SettingsStore
import com.dante.zeekrcapabilitylab.service.recorder.RecordingSourceRole
import com.dante.zeekrcapabilitylab.diagnostic.NativeCrashTrace
import kotlinx.serialization.json.*
import java.io.File

/** All methods here are metadata reads. No camera opens, codec instances or EGL contexts. */
internal object PreflightInventory {
    fun device(context: Context): JsonObject {
        val displays = context.getSystemService(DisplayManager::class.java).displays
        return obj("manufacturer" to Build.MANUFACTURER, "model" to Build.MODEL,
            "product" to Build.PRODUCT, "sdk" to Build.VERSION.SDK_INT, "abis" to Build.SUPPORTED_ABIS.toList(),
            "soc" to if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else null,
            "buildFingerprint" to Build.FINGERPRINT, "cameraPermission" to (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED),
            "displays" to displays.take(4).map { obj("id" to it.displayId, "state" to it.state, "refreshHz" to it.refreshRate) },
            "resources" to resources(context), "gear" to null, "speed" to null, "movementDetection" to "UNAVAILABLE_PUBLIC_ADAPTER")
    }
    fun resources(context: Context): JsonObject {
        val memory = Debug.MemoryInfo(); Debug.getMemoryInfo(memory)
        val power = context.getSystemService(PowerManager::class.java)
        return obj("atElapsedMs" to SystemClock.elapsedRealtime(), "pssKiB" to memory.totalPss,
            "fdCount" to File("/proc/self/fd").list()?.size, "threadCount" to File("/proc/self/task").list()?.size,
            "processCpuTimeMs" to Process.getElapsedCpuTime(), "interactive" to power.isInteractive,
            "thermalStatus" to if (Build.VERSION.SDK_INT >= 29) power.currentThermalStatus else null,
            "thermalHeadroom" to if (Build.VERSION.SDK_INT >= 30) runCatching { power.getThermalHeadroom(0).takeIf { it.isFinite() } }.getOrNull() else null,
            "temperatureC" to null, "temperatureReason" to "NO_PUBLIC_COMPONENT_SENSOR")
    }
    fun cameras(context: Context): JsonArray {
        val manager = context.getSystemService(CameraManager::class.java)
        val settings = SettingsStore.get(context)
        val resolved = RecordingSourceRole.entries.associateWith { runCatching { ProductRecorderConfigFactory.resolveSource(context, it)?.cameraId }.getOrNull() }
        return JsonArray(manager.cameraIdList.take(16).map { id -> runCatching {
            val c = manager.getCameraCharacteristics(id); val map = c.get(CC.SCALER_STREAM_CONFIGURATION_MAP)
            val outputs = listOf(SurfaceTexture::class.java, MediaRecorder::class.java, MediaCodec::class.java).map { type ->
                val sizes = runCatching { map?.getOutputSizes(type)?.toList().orEmpty() }.getOrDefault(emptyList())
                obj("outputClass" to type.simpleName, "sizes" to sizes.take(64).map { size -> obj("width" to size.width, "height" to size.height,
                    "minFrameDurationNs" to runCatching { map?.getOutputMinFrameDuration(type, size)?.toString() }.getOrNull(),
                    "stallDurationNs" to runCatching { map?.getOutputStallDuration(type, size)?.toString() }.getOrNull()) }, "omittedSizes" to (sizes.size - 64).coerceAtLeast(0))
            }
            val roles = resolved.filterValues { it == id }.keys
            obj("cameraId" to id, "roles" to roles.map { it.name },
                "roleEvidence" to roles.map { obj("role" to it.name, "source" to if (settings.cameraMapping(it) == id) "USER_MAPPING" else "PRODUCT_RESOLVER_INFERENCE") },
                "hardwareLevel" to c.get(CC.INFO_SUPPORTED_HARDWARE_LEVEL), "capabilities" to c.get(CC.REQUEST_AVAILABLE_CAPABILITIES)?.toList(),
                "physicalIds" to if (Build.VERSION.SDK_INT >= 28) c.physicalCameraIds.sorted() else emptyList<String>(),
                "timestampSource" to c.get(CC.SENSOR_INFO_TIMESTAMP_SOURCE),
                "fpsRanges" to c.get(CC.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.map { listOf(it.lower, it.upper) },
                "requestKeys" to c.availableCaptureRequestKeys?.take(128)?.map { it.name }, "outputs" to outputs,
                "sessionCombinationQuery" to "NOT_RUN_REQUIRES_DEVICE_OPEN", "evidenceLevel" to "DECLARED")
        }.getOrElse { obj("cameraId" to id, "status" to "UNAVAILABLE", "reason" to it.javaClass.simpleName) } })
    }
    fun codecs(): JsonArray = JsonArray(MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter { it.isEncoder }.take(64).flatMap { codec ->
        codec.supportedTypes.filter { it == MediaFormat.MIMETYPE_VIDEO_AVC || it == MediaFormat.MIMETYPE_VIDEO_HEVC }.map { mime -> runCatching {
            val cap = codec.getCapabilitiesForType(mime); val video = requireNotNull(cap.videoCapabilities)
            val candidates = listOf(1280 to 5140, 3840 to 1728, 2560 to 1280, 1920 to 1080).map { (w,h) ->
                val format = MediaFormat.createVideoFormat(mime,w,h).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_FRAME_RATE,30); setInteger(MediaFormat.KEY_BIT_RATE,28_000_000)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1)
                }
                fun query(block: () -> Boolean): JsonObject = runCatching { obj("value" to block(), "status" to "PASS", "evidenceLevel" to "DECLARED") }
                    .getOrElse { obj("value" to null, "status" to "UNAVAILABLE", "reason" to it.javaClass.simpleName) }
                obj("id" to "${w}x$h", "parameters" to obj("mime" to mime, "width" to w, "height" to h,"fps" to 30,"bitrateBps" to 28_000_000,
                    "iFrameIntervalSeconds" to 1,"input" to "SURFACE", "profile" to null,"bFrames" to null,"colour" to null),
                    "sizeAndRate" to query { video.areSizeAndRateSupported(w,h,30.0) }, "format" to query { cap.isFormatSupported(format) },
                    "heightAtWidth" to runCatching { video.getSupportedHeightsFor(w).toString() }.getOrNull(),
                    "widthAtHeight" to runCatching { video.getSupportedWidthsFor(h).toString() }.getOrNull(),
                    "candidateExecution" to "NOT_IMPLEMENTED", "detailLoss" to (w == 2560 || w == 1920))
            }
            obj("name" to codec.name, "canonicalName" to if (Build.VERSION.SDK_INT >= 29) codec.canonicalName else null,
                "alias" to if (Build.VERSION.SDK_INT >= 29) codec.isAlias else null,
                "hardwareAccelerated" to if (Build.VERSION.SDK_INT >= 29) codec.isHardwareAccelerated else null,
                "softwareOnly" to if (Build.VERSION.SDK_INT >= 29) codec.isSoftwareOnly else null, "mime" to mime,
                "rateControlCapabilities" to if(Build.VERSION.SDK_INT>=29)com.dante.zeekrcapabilitylab.preflight.continuous.ProbeCodecFormat.rateControls(cap) else null,
                "surfaceInput" to (MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface in cap.colorFormats),
                "profiles" to cap.profileLevels.map { obj("profile" to it.profile,"level" to it.level) },
                "widthAlignment" to video.widthAlignment,"heightAlignment" to video.heightAlignment,"bitrateRange" to video.bitrateRange.toString(),
                "widthRange" to video.supportedWidths.toString(),"heightRange" to video.supportedHeights.toString(),
                "performancePoints" to if (Build.VERSION.SDK_INT >= 29) video.supportedPerformancePoints?.take(24)?.map { it.toString() } else null,
                "candidates" to candidates, "physicalEncoderIndependence" to "UNKNOWN")
        }.getOrElse { obj("name" to codec.name, "mime" to mime,"status" to "UNAVAILABLE","reason" to it.javaClass.simpleName) } }
    })
    fun build(context: Context): JsonObject = runCatching {
        val evidence=context.assets.open("preflight/build-evidence.json").bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        check(evidence["versionName"]==JsonPrimitive(BuildConfig.VERSION_NAME) && evidence["versionCode"]==JsonPrimitive(BuildConfig.VERSION_CODE))
        JsonObject(evidence+mapOf("runtimeBuildType" to JsonPrimitive(BuildConfig.BUILD_TYPE),"runtimeApplicationId" to JsonPrimitive(context.packageName)))
    }.getOrElse { obj("versionName" to BuildConfig.VERSION_NAME,"versionCode" to BuildConfig.VERSION_CODE,"status" to "UNAVAILABLE", "reason" to "BUILD_ASSET_MISSING") }

    fun exits(context: Context): JsonObject {
        if (Build.VERSION.SDK_INT < 30) return obj("status" to "UNAVAILABLE","reason" to "API_BELOW_30")
        return runCatching {
            var traceCount = 0
            obj("scope" to "HISTORICAL_NOT_CURRENT_RUN", "entries" to context.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(context.packageName,0,8).map { exit ->
                    val trace = if (Build.VERSION.SDK_INT >= 31 && exit.reason in listOf(2,5,6) && traceCount++ < 2) runCatching {
                        exit.traceInputStream?.use { input ->
                            val bytes = input.readBytesBounded(NativeCrashTrace.MAX_BYTES + 1)
                            if (exit.reason == 6) obj("status" to "AVAILABLE_ANR_NOT_EXPORTED", "bytesRead" to bytes.size)
                            else NativeCrashTrace.decode(bytes)
                        } ?: obj("status" to "UNAVAILABLE", "reason" to "NO_RETAINED_TRACE")
                    }.getOrElse { obj("status" to "UNAVAILABLE", "reason" to it.javaClass.simpleName) } else obj("status" to "NOT_RUN")
                    obj("atEpochMs" to exit.timestamp,"reasonNumber" to exit.reason,"signalOrExitStatus" to exit.status,
                        "importance" to exit.importance,"pssKiB" to exit.pss,"rssKiB" to exit.rss,"trace" to trace)
                })
        }.getOrElse { obj("status" to "UNAVAILABLE","reason" to it.javaClass.simpleName) }
    }
    private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
        while (output.size() < limit) { val count = read(buffer,0,minOf(buffer.size,limit-output.size())); if (count < 0) break; check(count > 0); output.write(buffer,0,count) }
        return output.toByteArray()
    }
}
