package com.dante.zeekrcapabilitylab.sentry.runtime

import com.dante.zeekrcapabilitylab.sentry.ai.*
import kotlin.math.exp

interface GuardNativeDetector {
    /** Float RGB in HWC order, normalized with the pinned OpenCV Zoo mean/std. */
    fun infer(input: FloatArray): List<FloatArray>
}

/** Independent of OpenCV/Android so tensor decode and geometry can be checked on the JVM. */
object NanoDetRuntime {
    const val SIZE = 416
    const val MODEL_SHA = "4b82da9944b88577175ee23a459dce2e26e6e4be573def65b1055dc2d9720186"
    const val VERSION = "nanodet-4b82da9944b8-yuv-rgb-v1"
    val mean = floatArrayOf(103.53f, 116.28f, 123.675f)
    val std = floatArrayOf(57.375f, 57.12f, 58.395f)
    data class Letterbox(val width: Int, val height: Int, val left: Int, val top: Int) {
        fun box(x0: Double, y0: Double, x1: Double, y1: Double): DetectionBox? {
            val l = ((x0 - left) / width).coerceIn(0.0, 1.0)
            val t = ((y0 - top) / height).coerceIn(0.0, 1.0)
            val r = ((x1 - left) / width).coerceIn(0.0, 1.0)
            val b = ((y1 - top) / height).coerceIn(0.0, 1.0)
            return if (l < r && t < b) DetectionBox(l, t, r, b) else null
        }
    }
    fun letterbox(width: Int, height: Int): Letterbox {
        require(width > 0 && height > 0)
        val ratio = minOf(SIZE.toDouble() / width, SIZE.toDouble() / height)
        val w = (width * ratio).toInt().coerceAtLeast(1)
        val h = (height * ratio).toInt().coerceAtLeast(1)
        return Letterbox(w, h, (SIZE - w) / 2, (SIZE - h) / 2)
    }
    /** Inverse of DetectorLane.sourcePointToLane, before scaling to an analysis stream. */
    fun sourcePoint(lane: DetectorLane, u: Double, v: Double): Pair<Double, Double> {
        val x = if (lane.mirrorHorizontal) 1 - u else u
        val point = when (lane.rotationDegrees) {
            90 -> v to 1 - x
            180 -> 1 - x to 1 - v
            270 -> 1 - v to x
            else -> x to v
        }
        return (lane.x0 + point.first * (lane.x1 - lane.x0)) to (lane.y0 + point.second * (lane.y1 - lane.y0))
    }
    private data class Candidate(val classId: Int, val detection: ObjectDetection)
    fun decode(outputs: List<FloatArray>, letterbox: Letterbox): List<ObjectDetection> {
        // The pinned 2022nov graph exports three levels (six tensors); upstream's fourth
        // anchor level is unused by its zip loop. Verify against the actual ONNX graph.
        require(outputs.size == 6) { "NANODET_OUTPUT_COUNT" }
        val candidates = mutableListOf<Candidate>()
        for ((level, stride) in listOf(8, 16, 32).withIndex()) {
            val cells = SIZE / stride
            val scores = outputs[level * 2]; val boxes = outputs[level * 2 + 1]
            require(scores.size == cells * cells * 80 && boxes.size == cells * cells * 32) { "NANODET_OUTPUT_SHAPE" }
            val top = (0 until cells * cells).map { anchor ->
                val best = (0 until 80).maxBy { scores[anchor * 80 + it] }
                Triple(anchor, best, scores[anchor * 80 + best])
            }.sortedByDescending { it.third }.take(1000)
            for ((anchor, classId, confidence) in top) {
                if (!confidence.isFinite() || confidence < 0.35f || confidence > 1f) continue
                val kind = when (classId) { 0 -> ObjectKind.PERSON; 1, 3 -> ObjectKind.TWO_WHEELER; 2, 5, 7 -> ObjectKind.VEHICLE; else -> continue }
                val distance = DoubleArray(4) { axis ->
                    val offset = anchor * 32 + axis * 8
                    val max = (0..7).maxOf { boxes[offset + it] }
                    require(max.isFinite()) { "NANODET_INVALID_LOGITS" }
                    var sum = 0.0; var weighted = 0.0
                    for (i in 0..7) { val weight = exp((boxes[offset + i] - max).toDouble()); sum += weight; weighted += i * weight }
                    weighted / sum * stride
                }
                val x = (anchor % cells) * stride + 0.5 * (stride - 1)
                val y = (anchor / cells) * stride + 0.5 * (stride - 1)
                val box = letterbox.box((x - distance[0]).coerceIn(0.0, 416.0), (y - distance[1]).coerceIn(0.0, 416.0),
                    (x + distance[2]).coerceIn(0.0, 416.0), (y + distance[3]).coerceIn(0.0, 416.0)) ?: continue
                candidates += Candidate(classId, ObjectDetection(kind, confidence.toDouble(), box))
            }
        }
        val kept = mutableListOf<Candidate>()
        for (candidate in candidates.sortedWith(compareByDescending<Candidate> { it.detection.confidence }.thenBy { it.classId })) {
            if (kept.none { it.classId == candidate.classId && it.detection.box.iou(candidate.detection.box) > 0.6 }) kept += candidate
            if (kept.size >= 32) break
        }
        return kept.map { it.detection }
    }
}
