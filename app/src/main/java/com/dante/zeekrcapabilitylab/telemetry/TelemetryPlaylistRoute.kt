package com.dante.zeekrcapabilitylab.telemetry

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Only measured media durations may be passed here. Zero stops alignment of the remaining playlist. */
@Serializable
data class TelemetryPlaylistWindow(
    val sessionId: String?,
    val startedElapsedMs: Long?,
    val endedElapsedMs: Long?,
    val durationMs: Long,
    val normalSpeed: Boolean,
)

data class TelemetryPlaylistPoint(
    val fix: TelemetryFix,
    val playbackMs: Long,
    val freshThroughMs: Long,
    val segmentIndex: Int,
)

data class TelemetryPlaylistRoute(
    val windows: List<TelemetryPlaylistWindow>,
    val points: List<TelemetryPlaylistPoint>,
    val tracks: List<TelemetryTrack>,
    val incomplete: Boolean,
) {
    fun sampleAt(playbackMs: Long): TelemetryPlaylistPoint? {
        val index = points.binarySearchBy(playbackMs) { it.playbackMs }.let { if (it >= 0) it else -it - 2 }
        return points.getOrNull(index)?.takeIf { playbackMs in it.playbackMs..it.freshThroughMs }
    }
}

/** Clips before display/export. Neither a different session nor a hidden segment can leak into a route. */
object TelemetryPlaylistRoutes {
    const val MAX_SEGMENTS = 512
    const val MAX_POINTS = TelemetryStore.MAX_POINTS
    private const val MAX_DURATION_MS = 24L * 60 * 60 * 1_000
    private val json = Json { encodeDefaults = true }

    fun build(windows: List<TelemetryPlaylistWindow>, load: (String) -> TelemetryTrack?): TelemetryPlaylistRoute {
        val selected = windows.take(MAX_SEGMENTS).toList()
        val points = ArrayList<TelemetryPlaylistPoint>()
        val tracks = ArrayList<TelemetryTrack>()
        var offset = 0L
        var incomplete = windows.size > MAX_SEGMENTS
        var loadedId: String? = null
        var loadedTrack: TelemetryTrack? = null
        var previousWindow: TelemetryPlaylistWindow? = null
        for ((index, window) in selected.withIndex()) {
            if (window.durationMs !in 1..MAX_DURATION_MS || offset > Long.MAX_VALUE - window.durationMs) {
                incomplete = true
                break
            }
            val nextOffset = offset + window.durationMs
            val start = window.startedElapsedMs
            val end = window.endedElapsedMs
            val id = window.sessionId
            if (window.normalSpeed && id != null && TelemetryStore.validId(id) && start != null && end != null &&
                start >= 0 && end > start) {
                if (loadedId != id) {
                    loadedId = id
                    loadedTrack = load(id)?.takeIf { it.header.version == 1 && it.header.sessionId == id && it.header.startedElapsedMs in 0..start }
                }
                val track = loadedTrack
                if (track != null) {
                    val length = minOf(end - start, window.durationMs)
                    val clipped = ArrayList<TelemetryFix>()
                    var broken = true
                    var priorSource: TelemetryFix? = null
                    for ((sourceIndex, fix) in track.points.asSequence().take(TelemetryStore.MAX_POINTS).withIndex()) {
                        if (!TelemetryPolicy.validStored(fix) || fix.elapsedMs < track.header.startedElapsedMs ||
                            (priorSource != null && fix.elapsedMs <= priorSource.elapsedMs)) {
                            broken = true
                            continue
                        }
                        val oldSource = priorSource
                        priorSource = fix
                        if (fix.elapsedMs < start || fix.elapsedMs - start >= length) continue
                        if (points.size >= MAX_POINTS) { incomplete = true; break }
                        val local = fix.elapsedMs - start
                        val at = offset + local
                        val prior = points.lastOrNull()
                        val adjacentWindow = previousWindow?.let {
                            it.normalSpeed && it.sessionId == id && it.endedElapsedMs == start &&
                                it.startedElapsedMs?.let { began -> start - began == it.durationMs } == true
                        } == true
                        val connectPrevious = prior != null && TelemetryPlayback.connected(prior.fix, fix) &&
                            (prior.segmentIndex == index && !broken ||
                                clipped.isEmpty() && prior.segmentIndex == index - 1 && adjacentWindow &&
                                oldSource?.elapsedMs == prior.fix.elapsedMs)
                        val safeFix = fix.copy(breakBefore = !connectPrevious)
                        val following = track.points.getOrNull(sourceIndex + 1)
                        // A known invalid interval is unknown immediately after its last exact fix.
                        val maxAge = if (following != null && (!TelemetryPolicy.validStored(following) ||
                                !TelemetryPlayback.connected(fix, following))) 0L else TelemetryPolicy.PLAYBACK_AGE_MS
                        val freshThrough = at + minOf(maxAge, length - local - 1)
                        points += TelemetryPlaylistPoint(safeFix, at, freshThrough, index)
                        clipped += safeFix
                        broken = false
                    }
                    if (clipped.isNotEmpty()) tracks += TelemetryTrack(track.header, clipped)
                }
            }
            offset = nextOffset
            previousWindow = window
            if (points.size >= MAX_POINTS) { incomplete = incomplete || index < selected.lastIndex; break }
        }
        return TelemetryPlaylistRoute(selected, points, tracks, incomplete)
    }

    /** Small coordinate-free selection survives the system Save dialog. It cannot expand to a full session. */
    fun selection(route: TelemetryPlaylistRoute): String = json.encodeToString(route.windows)
    fun readSelection(value: String): List<TelemetryPlaylistWindow>? {
        if (value.length > 262_144) return null
        return runCatching { json.decodeFromString<List<TelemetryPlaylistWindow>>(value) }.getOrNull()?.takeIf { list ->
            list.size <= MAX_SEGMENTS && list.all { it.sessionId == null || TelemetryStore.validId(it.sessionId) }
        }
    }

    fun gpx(route: TelemetryPlaylistRoute): String {
        require(route.points.isNotEmpty() && route.tracks.isNotEmpty())
        val documents = route.tracks.map(TelemetryRouteExport::gpx)
        return buildString {
            append(documents.first().substringBefore("<trk>"))
            documents.forEach { document ->
                append("<trk>").append(document.substringAfter("<trk>").substringBeforeLast("</trk>"))
                append("</trk>\n")
            }
            append("</gpx>\n")
        }
    }
}
