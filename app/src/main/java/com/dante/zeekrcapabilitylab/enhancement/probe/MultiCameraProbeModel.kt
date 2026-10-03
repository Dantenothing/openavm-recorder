package com.dante.zeekrcapabilitylab.enhancement.probe

import com.dante.zeekrcapabilitylab.probe.camera.ProfileSize

enum class ProbeDeclaration { DECLARED, NOT_LISTED, QUERY_FAILED, API_UNAVAILABLE }

/** Only this explicit experiment can try an undeclared combination. Production gates stay unchanged. */
object MultiCameraProbePolicy {
    fun rejection(enabled: Boolean, foreground: Boolean, parked: Boolean, permission: Boolean,
        ids: List<String>, declaration: ProbeDeclaration, allowUnlisted: Boolean): String? = when {
        !enabled -> "PROBE_BUILD_REQUIRED"
        !foreground -> "PAGE_HIDDEN"
        !parked -> "PARK_FIRST"
        !permission -> "CAMERA_PERMISSION_REQUIRED"
        ids.size !in 2..3 || ids.any(String::isBlank) || ids.distinct().size != ids.size -> "INVALID_SELECTION"
        declaration == ProbeDeclaration.API_UNAVAILABLE -> "API_UNAVAILABLE"
        declaration == ProbeDeclaration.QUERY_FAILED -> "DECLARATION_QUERY_FAILED"
        declaration == ProbeDeclaration.NOT_LISTED && !allowUnlisted -> "UNLISTED_OPT_IN_REQUIRED"
        else -> null
    }

    fun declaration(ids: List<String>, sets: Set<Set<String>>): ProbeDeclaration =
        if (sets.any { it.containsAll(ids) }) ProbeDeclaration.DECLARED else ProbeDeclaration.NOT_LISTED

    /** Keep the known surround raster; other streams use the smallest ordinary declared preview. */
    fun size(surround: Boolean, sizes: List<ProfileSize>): ProfileSize? {
        val valid = sizes.distinct().filter { it.width in 1..8192 && it.height in 1..8192 }
        if (surround) return listOf(ProfileSize(1280, 5140), ProfileSize(5120, 1280), ProfileSize(1280, 5120))
            .firstOrNull { it in valid }
        return valid.filter { it.width <= 1920 && it.height <= 1080 &&
            it.width.toLong() * 4 >= it.height && it.height.toLong() * 4 >= it.width }
            .minWithOrNull(compareBy<ProfileSize> { kotlin.math.abs(it.totalPixels - 640L * 480) }
                .thenBy { it.totalPixels }.thenBy { it.width })
    }
}

data class ProbeFrameSnapshot(val id: String, val frames: Long, val firstFrameAfterMs: Long?,
    val lastFrameAgeMs: Long?, val averageFps: Double?, val lastTextureTimestampNs: Long?)

/** Texture timestamps prove buffer advancement; callbacks/draw passes alone do not. Control-thread only. */
class MultiCameraFrameEvidence(ids: List<String>, private val startedAt: Long) {
    companion object {
        const val START_TIMEOUT_MS = 15_000L
        const val OBSERVATION_MS = 20_000L
        const val FRESH_MS = 1_500L
    }
    private data class Stream(var frames: Long = 0, var timestamp: Long = 0,
        var first: Long? = null, var last: Long? = null)
    private val streams = ids.associateWith { Stream() }
    var simultaneousSince: Long? = null; private set
    var reason: String? = null; private set
    private var stopped = false
    private var lastTick = startedAt

    init { require(ids.size in 2..3 && ids.distinct().size == ids.size && ids.none(String::isBlank)) }

    fun frame(id: String, timestampNs: Long, at: Long) {
        if (stopped || at < startedAt) return
        val stream = streams[id] ?: return
        if (timestampNs <= stream.timestamp || at < (stream.last ?: startedAt)) return
        stream.timestamp = timestampNs
        stream.frames++
        if (stream.first == null) stream.first = at
        stream.last = at
    }

    /** A control stall breaks evidence rather than silently counting unobserved time as a pass. */
    fun tick(now: Long): String? {
        if (stopped) return reason
        if (now < lastTick || (simultaneousSince != null && now - lastTick > FRESH_MS))
            return stop("OBSERVATION_INTERRUPTED")
        lastTick = now
        val fresh = streams.values.all { it.frames >= 3 && it.last != null && now - it.last!! in 0..FRESH_MS }
        if (simultaneousSince == null) {
            if (now - startedAt >= START_TIMEOUT_MS) return stop("FIRST_FRAMES_TIMEOUT")
            if (fresh) simultaneousSince = now
        } else {
            if (!fresh) return stop("STREAM_STALLED")
            if (now - simultaneousSince!! >= OBSERVATION_MS) return stop("PREVIEW_OBSERVED")
        }
        return null
    }

    fun stop(value: String): String {
        if (!stopped) { stopped = true; reason = value }
        return requireNotNull(reason)
    }

    fun snapshot(now: Long): List<ProbeFrameSnapshot> = streams.map { (id, s) ->
        ProbeFrameSnapshot(id, s.frames, s.first?.minus(startedAt), s.last?.let { (now - it).coerceAtLeast(0) },
            if (s.frames > 1 && s.last!! > s.first!!) (s.frames - 1) * 1000.0 / (s.last!! - s.first!!) else null,
            s.timestamp.takeIf { it > 0 })
    }
}

/** SurfaceTexture belongs to the View until destruction; an active camera defers its release. */
class ProbeTextureOwnership {
    private var held = false
    private var destroyed = false
    @Synchronized fun claim(): Boolean {
        if (held || destroyed) return false
        held = true
        return true
    }
    /** true: let TextureView release; false: active owner will release after CameraDevice.onClosed. */
    @Synchronized fun viewDestroyed(): Boolean { destroyed = true; return !held }
    /** true exactly once when the owner must release the detached texture itself. */
    @Synchronized fun release(): Boolean {
        if (!held) return false
        held = false
        return destroyed
    }
}
