package io.github.dantenothing.drivemap

/** Coordinate-free process diagnostics. Never stores a Context, URL, exception message or route. */
object RoadMapDiagnostics {
    private var mode = "LOCAL"
    private var outcome = "NOT_REQUESTED"
    private var error = "NONE"
    private var loads = 0L
    private var failures = 0L
    @Synchronized internal fun mode(value: String) { mode = value }
    @Synchronized internal fun loadStarted() { loads++; outcome = "LOADING"; error = "NONE" }
    @Synchronized internal fun loaded() { outcome = "RENDERED"; error = "NONE" }
    @Synchronized internal fun failed(category: String) {
        failures++; outcome = "LOCAL_FALLBACK"
        error = category.takeIf { it in setOf("LOAD_ERROR", "TIMEOUT", "RENDERER_UNAVAILABLE", "RENDER_ERROR") } ?: "LOAD_ERROR"
    }
    @Synchronized fun snapshot(): Map<String, String> = linkedMapOf(
        "module" to "drive-map-v1", "provider" to "OpenFreeMap", "renderer" to "MapLibre OpenGL ES",
        "sdkVersion" to "11.13.5", "mode" to mode, "loadOutcome" to outcome,
        "loadCount" to loads.toString(), "failureCount" to failures.toString(), "errorCategory" to error,
    )
}
