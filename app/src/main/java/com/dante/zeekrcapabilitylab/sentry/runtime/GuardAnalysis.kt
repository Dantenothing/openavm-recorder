package com.dante.zeekrcapabilitylab.sentry.runtime

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import com.dante.zeekrcapabilitylab.ZeekrApp
import com.dante.zeekrcapabilitylab.sentry.ModeStamp
import com.dante.zeekrcapabilitylab.sentry.ai.*
import com.dante.zeekrcapabilitylab.service.recorder.SessionSourceSnapshot
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.encodeToString

class GuardSettings(context: Context) {
    private val prefs = context.getSharedPreferences("sentry-integrated-v1", Context.MODE_PRIVATE)
    var runDuration: GuardRunDuration
        get() = GuardRunDuration.fromStored(prefs.getString("runDuration", null))
        set(value) { prefs.edit().putString("runDuration", value.name).apply() }
    var aiEnabled: Boolean
        get() = prefs.getBoolean("aiTrial", false)
        set(value) { prefs.edit().putBoolean("aiTrial", value).apply() }
    var near: Float
        get() = prefs.getFloat("near", 0.55f).coerceIn(0.1f, 0.8f)
        set(value) { prefs.edit().putFloat("near", value.coerceIn(0.1f, 0.8f)).remove("confirmedLayout").apply() }
    var critical: Float
        get() = prefs.getFloat("critical", 0.8f).coerceIn(near + 0.05f, 0.95f)
        set(value) { prefs.edit().putFloat("critical", value.coerceIn(near + 0.05f, 0.95f)).remove("confirmedLayout").apply() }
    var confirmedLayout: String?
        get() = prefs.getString("confirmedLayout", null)
        set(value) { prefs.edit().putString("confirmedLayout", value).apply() }
}

data class GuardPreviewLane(val lane: Int, val label: String, val bitmap: Bitmap,
    val letterbox: NanoDetRuntime.Letterbox, val detections: List<ObjectDetection>)
object GuardPreview {
    val lanes = MutableStateFlow<List<GuardPreviewLane>>(emptyList())
    val layoutKey = MutableStateFlow("")
    @Volatile var visible = false
}

/** Analysis never holds codec buffers; at most one image is in inference, other images are drained. */
class GuardAnalysis(
    private val context: Context, private val stamp: ModeStamp,
    private val onState: (GuardAiState) -> Unit, private val onTrigger: (GuardTrigger) -> Unit,
    private val onReleased: () -> Unit,
) {
    private val settings = GuardSettings(context)
    private val thread = HandlerThread("sentry-analysis-images").apply { start() }
    private val handler = Handler(thread.looper)
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var busy = false
    @Volatile private var stopped = false
    @Volatile private var cameraClosedAcknowledged = false
    @Volatile var released = false
        private set
    private var reader: ImageReader? = null
    private var source: SessionSourceSnapshot? = null
    private var layout: DetectorLayout? = null
    private var sensorClock = GuardCaptureClock(false)
    private var lastSubmittedUs = -1L
    private var detector: GuardNativeDetector? = null
    private var engine: VisualRiskEngine? = null
    private var profileKey = ""
    private var frameId = 0L
    @Volatile private var failed = false
    private var previousLuma = mutableMapOf<Int, DoubleArray>()
    private data class SamplingPlan(val box: NanoDetRuntime.Letterbox, val coordinates: IntArray)
    private val samplingPlans = mutableMapOf<Int, SamplingPlan>()
    private val rgbInput by lazy { FloatArray(416 * 416 * 3) }
    @Volatile private var state = GuardAiState()
    val snapshot get() = state.copy(clockCalibrated = sensorClock.calibrated(nowUs()), clock = sensorClock.evidence(nowUs()))

    fun configure(capabilities: GuardCameraCapabilities, source: SessionSourceSnapshot): Surface? {
        this.source = source
        capabilities.requireMatches(source)
        sensorClock = GuardCaptureClock(capabilities.timestampRealtime)
        if (!settings.aiEnabled) { update(GuardAiState()); return null }
        return try {
            val frozen = FrozenLaneLayoutAdapter.freeze(requireNotNull(source.laneLayout), "layout-${source.mappingRevision}")
            layout = frozen
            val size = capabilities.analysisSize ?: error(capabilities.analysisError ?: "NO_MATCHING_YUV_OUTPUT")
            reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3).also {
                it.setOnImageAvailableListener(::imageAvailable, handler)
            }
            update(GuardAiState(status = "STARTING", message = "正在检查分析画面与编码时钟", analysisWidth = size.width, analysisHeight = size.height))
            reader!!.surface
        } catch (t: Throwable) { unavailable(t.message ?: "ANALYSIS_CONFIG_FAILED"); null }
    }
    fun sensor(timestampNs: Long) { sensorClock.sensor(timestampNs / 1000, nowUs()) }
    fun encoded(ptsUs: Long) {
        sensorClock.encoded(ptsUs, nowUs())
        if (state.status == "OFF" && state.clockCalibrated != sensorClock.calibrated(nowUs())) update(snapshot)
    }
    fun acceptsTrigger(signal: GuardTrigger, nowUs: Long): Boolean = sensorClock.acceptsTrigger(
        signal.ptsUs, signal.observedAtElapsedUs, signal.clockGeneration, nowUs)
    fun unavailable(reason: String) {
        failed = true
        update(state.copy(status = "UNAVAILABLE", message = reason.take(160), clockCalibrated = false))
    }
    /** Called only after the camera's onClosed, so no HAL request still owns this surface. */
    fun cameraClosed() { stopped = true; cameraClosedAcknowledged = true; handler.post { releaseIfIdle() } }
    fun revoke() { stopped = true }
    private fun releaseIfIdle() {
        if (released || busy || !cameraClosedAcknowledged) return
        reader?.setOnImageAvailableListener(null, null)
        reader?.close(); reader = null
        engine?.disarm(); engine = null
        released = true; worker.shutdown(); thread.quitSafely()
        GuardPreview.lanes.value = emptyList(); GuardPreview.layoutKey.value = ""
        onReleased()
    }
    private fun imageAvailable(value: ImageReader) {
        val image = runCatching { value.acquireLatestImage() }.getOrNull() ?: return
        val now = nowUs()
        if (stopped || busy || failed || now - lastSubmittedUs < 500_000) {
            image.close(); state = state.copy(dropped = state.dropped + 1); return
        }
        busy = true; lastSubmittedUs = now
        worker.execute {
            try { if (!stopped) process(image, now) }
            catch (t: Throwable) { unavailable("AI_${t.cause?.javaClass?.simpleName ?: t.javaClass.simpleName}: ${t.message.orEmpty().take(100)}") }
            finally { image.close(); busy = false; if (stopped) handler.post { releaseIfIdle() } }
        }
    }
    private fun process(image: Image, imageArrivalUs: Long) {
        val started = nowUs()
        if (detector == null) detector = Class.forName("com.dante.zeekrcapabilitylab.sentry.runtime.OpenCvGuardDetector")
            .getConstructor(Context::class.java).newInstance(context.applicationContext) as GuardNativeDetector
        val frozen = requireNotNull(layout)
        val captureUs = image.timestamp / 1000
        val near = settings.near
        val critical = settings.critical
        val key = GuardEventStore.json.encodeToString(frozen) + "/$near/$critical"
        val confirmed = settings.confirmedLayout == key
        val lanes = mutableListOf<LaneDetections>()
        val previews = mutableListOf<GuardPreviewLane>()
        for (lane in frozen.lanes) {
            if (stopped) return
            val prepared = prepare(image, frozen, lane)
            val detections = NanoDetRuntime.decode(detector!!.infer(prepared.first), prepared.second)
            val luma = DoubleArray(64) { i ->
                val x = prepared.second.left + (i % 8) * prepared.second.width / 8
                val y = prepared.second.top + (i / 8) * prepared.second.height / 8
                val offset = (y * 416 + x) * 3
                (prepared.first[offset] * NanoDetRuntime.std[0] + NanoDetRuntime.mean[0]).toDouble()
            }
            val mean = luma.average(); val previous = previousLuma.put(lane.lane, luma)
            val quality = when {
                mean < 18 -> SceneQuality.LOW_VISIBILITY
                previous != null && kotlin.math.abs(mean - previous.average()) > 32 -> SceneQuality.EXPOSURE_CHANGE
                previous != null && luma.indices.map { kotlin.math.abs((luma[it] - mean) - (previous[it] - previous.average())) }.average() > 45 -> SceneQuality.CAMERA_SHAKE
                else -> SceneQuality.GOOD
            }
            lanes += LaneDetections(lane.lane, quality, detections)
            if (GuardPreview.visible && ZeekrApp.isForeground.value) {
                val pixels = IntArray(208 * 208) { i ->
                    val offset = ((i / 208 * 2) * 416 + i % 208 * 2) * 3
                    fun channel(c: Int) = (prepared.first[offset + c] * NanoDetRuntime.std[c] + NanoDetRuntime.mean[c]).toInt().coerceIn(0, 255)
                    (0xff shl 24) or (channel(0) shl 16) or (channel(1) shl 8) or channel(2)
                }
                previews += GuardPreviewLane(lane.lane, source!!.laneLayout!!.lanes.first { it.lane == lane.lane }.label,
                    Bitmap.createBitmap(pixels, 208, 208, Bitmap.Config.ARGB_8888), prepared.second, detections)
            }
        }
        if (stopped) return
        if (previews.isNotEmpty()) { GuardPreview.lanes.value = previews; GuardPreview.layoutKey.value = key }
        val now = nowUs()
        // Inference gives the encoder time to publish this image's matching output.
        // UNKNOWN camera epochs stay in the camera/media domain throughout the risk engine.
        val frameTime = sensorClock.frameTime(captureUs, imageArrivalUs, now)
        val calibrated = frameTime != null
        val engineKey = "$key/$confirmed/${frameTime?.generation}"
        if (frameTime != null && profileKey != engineKey) {
            profileKey = engineKey; engine?.disarm()
            engine = VisualRiskEngine(stamp, frameTime.framePtsUs, NanoDetRuntime.VERSION, profile(frozen, confirmed, near, critical),
                RiskRules(version = "risk-trial-v1", maxObservationGapUs = 1_500_000, maxResultAgeUs = 1_500_000))
        }
        val result = frameTime?.let { time -> engine!!.process(DetectionFrame(runGeneration = stamp.run,
            transitionGeneration = stamp.transition, frameId = ++frameId, monotonicUs = time.framePtsUs,
            wallEpochMs = System.currentTimeMillis() - (now - time.arrivedUs) / 1000,
            detectorVersion = NanoDetRuntime.VERSION, layoutVersion = frozen.version, lanes = lanes), time.nowPtsUs) }
        val allowed = confirmed && calibrated && result?.status == RiskFrameStatus.ACCEPTED
        val signals = if (allowed) result!!.triggers else emptyList()
        update(state.copy(status = if (allowed) "TRIAL_ACTIVE" else if (!calibrated) "CLOCK_PENDING" else "CALIBRATION_REQUIRED",
            message = when { !calibrated -> "分析画面已到达，等待同一相机的画面与编码匹配"; !confirmed -> "请核对下方四个视角和警戒区域，并点击确认";
                result?.status != RiskFrameStatus.ACCEPTED -> "结果过慢或已过期：${result?.status}"; else -> "本地 AI 试用运行中，准确率待实车验收" },
            vehicleCalibrated = confirmed, clockCalibrated = calibrated, frames = state.frames + 1,
            inferenceMs = (now - started) / 1000, tracks = result?.tracks.orEmpty().take(24),
            maxConfidence = lanes.flatMap { it.objects }.maxOfOrNull { it.confidence } ?: 0.0,
            triggers = state.triggers + signals.size, model = NanoDetRuntime.VERSION,
            ruleVersion = "risk-trial-v1", profileVersion = "trial-zones-$near-$critical",
            nearBoundary = near.toDouble(), criticalBoundary = critical.toDouble(), clock = sensorClock.evidence(now)))
        for (signal in signals) if (!stopped) onTrigger(GuardTrigger("VISUAL_RISK", signal.monotonicUs,
            signal.wallEpochMs, signal.lanes, signal.tracks, signal.detectorVersion, signal.ruleConfigVersion,
            observedAtElapsedUs = frameTime!!.arrivedUs, clockGeneration = frameTime.generation))
    }

    private fun profile(layout: DetectorLayout, calibrated: Boolean, near: Float, critical: Float): RiskProfile {
        fun region(zone: RiskZone, top: Double) = ZoneRegion(zone, ImagePolygon(listOf(
            ImagePoint(0.0, top), ImagePoint(1.0, top), ImagePoint(1.0, 1.0), ImagePoint(0.0, 1.0))))
        return RiskProfile("trial-zones-$near-$critical", layout, layout.lanes.map {
            LaneRiskZones(it.lane, listOf(region(RiskZone.MID, 0.25), region(RiskZone.NEAR, near.toDouble()),
                region(RiskZone.CRITICAL, critical.toDouble())))
        }, calibrated)
    }

    private fun prepare(image: Image, layout: DetectorLayout, lane: DetectorLane): Pair<FloatArray, NanoDetRuntime.Letterbox> {
        check(image.planes.size == 3 && image.cropRect.left == 0 && image.cropRect.top == 0 &&
            image.cropRect.width() == image.width && image.cropRect.height() == image.height) { "YUV_CROP_UNSUPPORTED" }
        val plan = samplingPlans.getOrPut(lane.lane) {
            val rotated = lane.rotationDegrees in setOf(90, 270)
            val box = NanoDetRuntime.letterbox(if (rotated) lane.y1 - lane.y0 else lane.x1 - lane.x0,
                if (rotated) lane.x1 - lane.x0 else lane.y1 - lane.y0)
            SamplingPlan(box, IntArray(box.width * box.height) { index ->
                val point = NanoDetRuntime.sourcePoint(lane, (index % box.width + 0.5) / box.width, (index / box.width + 0.5) / box.height)
                val sx = (point.first * image.width / layout.sourceWidth).toInt().coerceIn(0, image.width - 1)
                val sy = (point.second * image.height / layout.sourceHeight).toInt().coerceIn(0, image.height - 1)
                sy * image.width + sx
            })
        }
        val box = plan.box
        val input = rgbInput
        for (index in input.indices) input[index] = -NanoDetRuntime.mean[index % 3] / NanoDetRuntime.std[index % 3]
        val planes = image.planes
        fun sample(plane: Int, x: Int, y: Int): Int {
            val p = planes[plane]; val offset = p.buffer.position() + y * p.rowStride + x * p.pixelStride
            return p.buffer.get(offset).toInt() and 255
        }
        for (y in 0 until box.height) for (x in 0 until box.width) {
            val coordinate = plan.coordinates[y * box.width + x]
            val sx = coordinate % image.width
            val sy = coordinate / image.width
            val yy = (sample(0, sx, sy) - 16).coerceAtLeast(0) * 1.164f
            val u = sample(1, sx / 2, sy / 2) - 128; val v = sample(2, sx / 2, sy / 2) - 128
            val offset = ((y + box.top) * 416 + x + box.left) * 3
            input[offset] = ((yy + 1.596f * v).coerceIn(0f, 255f) - NanoDetRuntime.mean[0]) / NanoDetRuntime.std[0]
            input[offset + 1] = ((yy - 0.392f * u - 0.813f * v).coerceIn(0f, 255f) - NanoDetRuntime.mean[1]) / NanoDetRuntime.std[1]
            input[offset + 2] = ((yy + 2.017f * u).coerceIn(0f, 255f) - NanoDetRuntime.mean[2]) / NanoDetRuntime.std[2]
        }
        return input to box
    }
    private fun update(value: GuardAiState) { state = value; onState(value) }
    private fun nowUs() = SystemClock.elapsedRealtimeNanos() / 1000
}
