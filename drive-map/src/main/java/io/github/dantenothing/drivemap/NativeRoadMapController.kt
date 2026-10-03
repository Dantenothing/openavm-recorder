package io.github.dantenothing.drivemap

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.view.View
import android.widget.FrameLayout
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.abs
import kotlin.math.pow

/** Owned by one visible, resumed AndroidView. All methods and callbacks run on the UI thread. */
internal class NativeRoadMapController(
    private val context: Context,
    private val onReady: () -> Unit,
    private val onFailure: (String) -> Unit,
    private val onGesture: () -> Unit,
    private val onSeek: (Long) -> Unit,
) {
    private var view: MapView? = null
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var closed = false
    private var started = false
    private var resumed = false
    private var callbacksRegistered = false
    private var rendered = false
    private var geometry = RoadGeometry(emptyList(), false)
    private var lastGeometry: RoadGeometry? = null
    private var current: RoadMapPoint? = null
    private var following = true
    private var positioned = false
    private var followZoomChosen = false
    private var pendingLayout: View.OnLayoutChangeListener? = null
    private val density = context.resources.displayMetrics.density
    private val memoryCallbacks = object : ComponentCallbacks2 {
        override fun onConfigurationChanged(newConfig: Configuration) = Unit
        override fun onLowMemory() { if (!closed) view?.onLowMemory() }
        override fun onTrimMemory(level: Int) { if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) onLowMemory() }
    }

    fun attach(host: FrameLayout) {
        if (closed || view != null) return
        try {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if ((manager?.deviceConfigurationInfo?.reqGlEsVersion ?: 0) < 0x30000) {
                onFailure("RENDERER_UNAVAILABLE"); return
            }
            MapLibre.getInstance(context.applicationContext)
            val nativeView = MapView(context, MapLibreMapOptions.createFromAttributes(context)
                .textureMode(true).translucentTextureSurface(false))
            view = nativeView
            host.addView(nativeView, FrameLayout.LayoutParams(-1, -1))
            nativeView.onCreate(null)
            nativeView.addOnDidFailLoadingMapListener { if (!closed) onFailure("LOAD_ERROR") }
            nativeView.addOnDidFinishRenderingFrameListener(MapView.OnDidFinishRenderingFrameListener { fully, _, _ ->
                if (!closed && !rendered && fully && style != null) {
                    rendered = true
                    onReady()
                }
            })
            nativeView.onStart(); started = true
            nativeView.onResume(); resumed = true
            context.applicationContext.registerComponentCallbacks(memoryCallbacks); callbacksRegistered = true
            nativeView.getMapAsync { readyMap ->
                if (closed) return@getMapAsync
                map = readyMap
                readyMap.uiSettings.apply {
                    isRotateGesturesEnabled = false
                    isTiltGesturesEnabled = false
                    isLogoEnabled = false
                    // Full linked credits are rendered below the map by RecordedRoadMap.
                    isAttributionEnabled = false
                }
                readyMap.addOnCameraMoveStartedListener { reason ->
                    if (!closed && reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                        following = false
                        onGesture()
                    }
                }
                readyMap.addOnMapClickListener { location ->
                    if (closed) false else seekNearest(location)
                }
                // No location component is activated. Only the viewed tile area goes to this provider.
                readyMap.setStyle(Style.Builder().fromUri(STYLE_URL)) { loaded ->
                    if (closed) return@setStyle
                    try {
                        style = loaded
                        loaded.addSource(GeoJsonSource(ROUTE_SOURCE, emptyFeatures()))
                        loaded.addLayer(LineLayer("openavm-route-outline", ROUTE_SOURCE).withProperties(
                            lineColor("#FFFFFF"), lineWidth(7f), lineOpacity(0.85f)))
                        loaded.addLayer(LineLayer("openavm-route", ROUTE_SOURCE).withProperties(
                            lineColor("#167A92"), lineWidth(4f)))
                        loaded.addLayer(CircleLayer("openavm-isolated-points", ROUTE_SOURCE).withProperties(
                            circleColor("#167A92"), circleRadius(4f), circleStrokeColor("#FFFFFF"), circleStrokeWidth(1.5f)))
                        loaded.addSource(GeoJsonSource(MARKER_SOURCE, emptyFeatures()))
                        loaded.addLayer(CircleLayer("openavm-playhead", MARKER_SOURCE).withProperties(
                            circleColor("#E47625"), circleRadius(8f), circleStrokeColor("#FFFFFF"), circleStrokeWidth(3f)))
                        applyData()
                        if (!positioned) fit()
                    } catch (_: Exception) { onFailure("RENDER_ERROR") }
                }
            }
        } catch (_: Exception) { onFailure("RENDERER_UNAVAILABLE") }
        catch (_: LinkageError) { onFailure("RENDERER_UNAVAILABLE") }
    }

    fun update(data: RoadGeometry, marker: RoadMapPoint?, follow: Boolean) {
        if (closed) return
        geometry = data
        val markerChanged = current != marker
        current = marker?.takeIf(RoadMapGeometry::valid)
        following = follow
        try {
            applyData()
            if (markerChanged && following) recenter()
        } catch (_: Exception) { onFailure("RENDER_ERROR") }
    }

    private fun applyData() {
        val loaded = style ?: return
        if (lastGeometry !== geometry) {
            val features = mutableListOf<Feature>()
            // Split the displayed line at the antimeridian; never draw a false world-spanning edge.
            geometry.strokes.forEach { stroke ->
                var segment = mutableListOf<Point>()
                fun flush() {
                    if (segment.size >= 2) features += Feature.fromGeometry(LineString.fromLngLats(segment))
                    else segment.firstOrNull()?.let { features += Feature.fromGeometry(it) }
                    segment = mutableListOf()
                }
                var priorLongitude: Double? = null
                stroke.forEach { value ->
                    if (priorLongitude?.let { abs(value.point.longitude - it) > 180.0 } == true) flush()
                    segment += Point.fromLngLat(value.point.longitude, value.point.latitude)
                    priorLongitude = value.point.longitude
                }
                flush()
            }
            loaded.getSourceAs<GeoJsonSource>(ROUTE_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(features))
            lastGeometry = geometry
        }
        val marker = current?.let { Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)) }
        loaded.getSourceAs<GeoJsonSource>(MARKER_SOURCE)?.setGeoJson(
            FeatureCollection.fromFeatures(marker?.let(::listOf) ?: emptyList()))
    }

    private fun seekNearest(location: LatLng): Boolean {
        val readyMap = map ?: return false
        val worldScale = 512.0 * 2.0.pow(readyMap.cameraPosition.zoom)
        val tapX = RoadMapGeometry.x(location.longitude)
        val tapY = RoadMapGeometry.y(location.latitude)
        val selected = RoadMapGeometry.nearest(geometry.points, 0.0, 0.0, 24.0 * density) { point ->
            RoadMapGeometry.wrappedDelta(point.x - tapX) * worldScale to (point.y - tapY) * worldScale
        } ?: return false
        onSeek(selected.playbackMs)
        return true
    }

    fun recenter() {
        if (closed) return
        val marker = current ?: return
        val readyMap = map ?: return
        // A marker arriving before the style/layout is ready must not suppress the initial fit.
        if (style == null || !positioned) return
        val zoom = if (followZoomChosen) readyMap.cameraPosition.zoom else readyMap.cameraPosition.zoom.coerceAtLeast(15.0)
        followZoomChosen = true
        readyMap.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(marker.latitude, marker.longitude), zoom))
    }

    fun fit() {
        if (closed) return
        val readyMap = map ?: return
        val nativeView = view ?: return
        if (style == null || geometry.points.isEmpty()) return
        if (nativeView.width <= 0 || nativeView.height <= 0) {
            if (pendingLayout == null) {
                val listener = View.OnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
                    if (right > left && bottom > top) {
                        pendingLayout?.let(nativeView::removeOnLayoutChangeListener)
                        pendingLayout = null
                        if (!closed) fit()
                    }
                }
                pendingLayout = listener
                nativeView.addOnLayoutChangeListener(listener)
            }
            return
        }
        val fit = RoadMapGeometry.fit(geometry.points,
            (nativeView.width - 64 * density).coerceAtLeast(64f).toDouble(),
            (nativeView.height - 64 * density).coerceAtLeast(64f).toDouble()) ?: return
        positioned = true
        followZoomChosen = false
        readyMap.moveCamera(CameraUpdateFactory.newLatLngZoom(
            LatLng(RoadMapGeometry.latitude(fit.second), RoadMapGeometry.longitude(fit.first)), fit.third))
    }

    fun zoom(inward: Boolean) {
        if (!closed) {
            followZoomChosen = true
            map?.moveCamera(if (inward) CameraUpdateFactory.zoomIn() else CameraUpdateFactory.zoomOut())
        }
    }

    fun close() {
        if (closed) return
        closed = true
        val nativeView = view
        pendingLayout?.let { nativeView?.removeOnLayoutChangeListener(it) }; pendingLayout = null
        if (callbacksRegistered) context.applicationContext.unregisterComponentCallbacks(memoryCallbacks)
        if (resumed) runCatching { nativeView?.onPause() }
        if (started) runCatching { nativeView?.onStop() }
        runCatching { nativeView?.onDestroy() }
        view = null; map = null; style = null
    }

    companion object {
        const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
        private const val ROUTE_SOURCE = "openavm-recorded-route"
        private const val MARKER_SOURCE = "openavm-recorded-marker"
        private fun emptyFeatures() = FeatureCollection.fromFeatures(emptyList<Feature>())
    }
}
