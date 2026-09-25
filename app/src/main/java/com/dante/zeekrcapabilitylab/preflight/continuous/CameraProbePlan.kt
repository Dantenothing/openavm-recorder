package com.dante.zeekrcapabilitylab.preflight.continuous

/** Fixed finite plans; remote input cannot change source, dimensions, frame rate or native routing. */
internal data class CameraProbePlan(val seconds: Int, val windowScript: Boolean) {
    init { require(seconds in setOf(20, 240, 600)) { "CAMERA_DURATION_NOT_SUPPORTED" } }
    val cutTargetsUs = (1..3).map { it * seconds * 1_000_000L / 4 }
    val durationUs = seconds * 1_000_000L
    val maxFrames = seconds * 40 + 120
    // 64 Mbps payload ceiling, above the observed 53 Mbps stress load; not a target bitrate gate.
    val encodedByteBudget = seconds * 8_000_000L + 16L * 1024 * 1024
    val minimumFreeBytes = encodedByteBudget * 2 + 1024L * 1024 * 1024
    val runTimeoutMs = seconds * 2000L + 180_000L
    fun spaceReason(free: Long?) = when {
        free == null || free < 0 -> "FREE_SPACE_UNKNOWN"
        free < minimumFreeBytes -> "P2_USB_FREE_SPACE_LOW"
        else -> null
    }
}

/** Source timestamps, not generated frame times. Duplicate images are never admitted as new frames. */
internal class CameraFrameLedger(private val maxFrames: Int) {
    private val times = ArrayList<Long>()
    private var originNs = 0L
    private var lastNs = 0L
    var duplicates = 0; private set
    var maximumGapNs = 0L; private set
    fun admit(timestampNs: Long): Long? {
        if(timestampNs <= 0 || timestampNs == lastNs) { duplicates++; return null }
        check(timestampNs > lastNs) { "CAMERA_TIMESTAMP_REGRESSED" }
        check(times.size < maxFrames) { "CAMERA_FRAME_BUDGET" }
        if(times.isEmpty())originNs=timestampNs else maximumGapNs=maxOf(maximumGapNs,timestampNs-lastNs)
        val pts=(timestampNs-originNs)/1000
        check(times.isEmpty() || pts>times.last()) { "CAMERA_PTS_NOT_MONOTONIC" }
        times+=pts;lastNs=timestampNs;return pts
    }
    fun pts()=times.toLongArray()
    val size get()=times.size
    val lastPts get()=times.lastOrNull() ?: -1L
    fun endPts()=(times.lastOrNull() ?: 0L)+33_334L
}
