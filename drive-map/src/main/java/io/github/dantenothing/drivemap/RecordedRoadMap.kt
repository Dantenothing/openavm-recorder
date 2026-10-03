package io.github.dantenothing.drivemap

import android.widget.FrameLayout
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlin.math.pow

/** Optional street tiles with a fully local recorded route available before, during and after loading. */
@Composable
fun RecordedRoadMap(
    points: List<RoadMapPoint>,
    current: RoadMapPoint?,
    modifier: Modifier = Modifier,
    chinese: Boolean = false,
    onSeek: (Long) -> Unit,
) {
    val geometry = remember(points) { RoadMapGeometry.prepare(points) }
    val marker = current?.takeIf(RoadMapGeometry::valid)
    var roads by rememberSaveable { mutableStateOf(false) }
    var following by rememberSaveable { mutableStateOf(true) }
    var retry by remember { mutableIntStateOf(0) }
    var fitRequest by remember { mutableIntStateOf(0) }
    var zoomRequest by remember { mutableIntStateOf(0) }
    var recenterRequest by remember { mutableIntStateOf(0) }
    var native by remember { mutableStateOf<NativeRoadMapController?>(null) }
    var nativeReady by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var visible by remember { mutableStateOf(true) }
    val rootView = LocalView.current.rootView
    val seek by rememberUpdatedState(onSeek)
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    SideEffect { RoadMapDiagnostics.mode(if (roads && resumed && visible) "STREETS" else "LOCAL") }
    fun label(en: String, zh: String) = if (chinese) zh else en

    BoxWithConstraints(modifier.onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInWindow()
        visible = bounds.width > 0 && bounds.height > 0 && bounds.right > 0 && bounds.bottom > 0 &&
            bounds.left < rootView.width && bounds.top < rootView.height
    }) {
        val bounded = maxHeight.value.isFinite()
        val smallPanel = bounded && maxHeight < 240.dp
        val columnModifier = (if (bounded) Modifier.fillMaxSize() else Modifier)
            .then(if (smallPanel) Modifier.verticalScroll(rememberScrollState()) else Modifier)
        Column(columnModifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(label("Street tiles share the viewed area with OpenFreeMap. No route file is uploaded.",
                "街道底图会向 OpenFreeMap 请求当前查看区域；不会上传轨迹文件。"), style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(roads, onClick = { roads = !roads }, label = { Text(label("Street map", "街道地图")) })
                Spacer(Modifier.width(6.dp))
                TextButton(onClick = { roads = false }) { Text(label("Local trace", "本地轨迹")) }
                TextButton(onClick = { following = true; recenterRequest++; native?.recenter() }, enabled = marker != null) {
                    Text(if (following) label("Following", "跟随中") else label("Recenter", "跟随位置"))
                }
                TextButton(onClick = { following = false; fitRequest++; native?.fit() }) { Text(label("Fit route", "全程")) }
                TextButton(onClick = { zoomRequest--; native?.zoom(false) }, modifier = Modifier.width(44.dp)) { Text("−") }
                TextButton(onClick = { zoomRequest++; native?.zoom(true) }, modifier = Modifier.width(44.dp)) { Text("+") }
            }
            Box(Modifier.fillMaxWidth().then(if (bounded && !smallPanel) Modifier.weight(1f) else Modifier.height(if (smallPanel) 180.dp else 260.dp))) {
                LocalRecordedTrace(geometry, marker, following, fitRequest, zoomRequest, recenterRequest,
                    onGesture = { following = false }, onSeek = { seek(it) }, chinese = chinese,
                    drawTrace = !(roads && resumed && visible && nativeReady), modifier = Modifier.fillMaxSize())
                if (roads && resumed && visible && geometry.points.isNotEmpty()) {
                    key(retry) {
                        NativeRoadSurface(geometry, marker, following, chinese,
                            onGesture = { following = false }, onSeek = { seek(it) },
                            onController = { native = it }, onReadyChanged = { nativeReady = it },
                            onRetry = { retry++ }, modifier = Modifier.fillMaxSize())
                    }
                }
                if (geometry.points.isEmpty()) Text(label("No recorded GPS points", "没有已记录的 GPS 点"), Modifier.align(Alignment.Center))
            }
            if (roads) MapCredits(chinese)
            else Text(label("Offline · tap a recorded point to jump in the video", "离线轨迹 · 点选已记录的位置可跳转视频"), style = MaterialTheme.typography.labelSmall)
            if (geometry.truncated) Text(label("Showing the first 60,000 recorded points", "显示前 60,000 个记录点"), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun NativeRoadSurface(
    geometry: RoadGeometry, current: RoadMapPoint?, following: Boolean, chinese: Boolean,
    onGesture: () -> Unit, onSeek: (Long) -> Unit,
    onController: (NativeRoadMapController?) -> Unit, onReadyChanged: (Boolean) -> Unit,
    onRetry: () -> Unit, modifier: Modifier,
) {
    var ready by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val gesture by rememberUpdatedState(onGesture)
    val seek by rememberUpdatedState(onSeek)
    val controllerChanged by rememberUpdatedState(onController)
    val readyChanged by rememberUpdatedState(onReadyChanged)
    val controller = remember(context) {
        NativeRoadMapController(context,
            onReady = { if (error == null) { ready = true; readyChanged(true); RoadMapDiagnostics.loaded() } },
            onFailure = { category -> if (error == null) { error = category; readyChanged(false); RoadMapDiagnostics.failed(category) } },
            onGesture = { gesture() }, onSeek = { seek(it) })
    }
    DisposableEffect(controller) {
        RoadMapDiagnostics.loadStarted()
        readyChanged(false)
        controllerChanged(controller)
        onDispose { readyChanged(false); controllerChanged(null); controller.close() }
    }
    LaunchedEffect(controller) {
        delay(20_000)
        if (!ready && error == null) { error = "TIMEOUT"; readyChanged(false); RoadMapDiagnostics.failed("TIMEOUT") }
    }
    if (error == null) {
        AndroidView(factory = { FrameLayout(it).also(controller::attach) },
            // Keep the measured trace visible while tiles load. Alpha preserves the native
            // surface/layout so its first fully rendered frame can still signal readiness.
            modifier = modifier.alpha(if (ready) 1f else 0f),
            update = { controller.update(geometry, current, following) })
        if (!ready) Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)) {
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(if (chinese) "正在加载街道…" else "Loading streets…", style = MaterialTheme.typography.bodySmall)
            }
        }
    } else {
        // Dispose the native view immediately so a failed or hidden map cannot keep fetching tiles.
        DisposableEffect(controller, error) { controller.close(); onDispose { } }
        Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)) {
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (chinese) "底图暂不可用 · 保留本地轨迹" else "Streets unavailable · local trace remains", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onRetry) { Text(if (chinese) "重试" else "Retry") }
            }
        }
    }
}

private data class TraceCamera(val centerX: Double, val centerY: Double, val scale: Double)

@Composable
private fun LocalRecordedTrace(
    geometry: RoadGeometry, current: RoadMapPoint?, following: Boolean,
    fitRequest: Int, zoomRequest: Int, recenterRequest: Int,
    onGesture: () -> Unit, onSeek: (Long) -> Unit, chinese: Boolean, drawTrace: Boolean, modifier: Modifier,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var camera by remember { mutableStateOf(TraceCamera(0.5, 0.5, 512.0)) }
    var lastZoomRequest by remember { mutableIntStateOf(zoomRequest) }
    val density = LocalDensity.current.density
    val foreground = MaterialTheme.colorScheme.primary
    val background = MaterialTheme.colorScheme.surfaceContainer
    val latestCamera by rememberUpdatedState(camera)
    val latestSeek by rememberUpdatedState(onSeek)
    val latestGesture by rememberUpdatedState(onGesture)
    fun markerWorld(): Pair<Double, Double>? = current?.let { point ->
        val raw = RoadMapGeometry.x(point.longitude)
        camera.centerX + RoadMapGeometry.wrappedDelta(raw - camera.centerX) to RoadMapGeometry.y(point.latitude)
    }
    LaunchedEffect(geometry, size, fitRequest) {
        RoadMapGeometry.fit(geometry.points, (size.width - 40 * density).coerceAtLeast(1f).toDouble(),
            (size.height - 40 * density).coerceAtLeast(1f).toDouble())?.let {
            camera = TraceCamera(it.first, it.second, 512.0 * 2.0.pow(it.third))
        }
    }
    LaunchedEffect(current, following, recenterRequest) {
        if (following) markerWorld()?.let { camera = camera.copy(centerX = it.first, centerY = it.second) }
    }
    LaunchedEffect(zoomRequest) {
        camera = camera.copy(scale = (camera.scale * 2.0.pow((zoomRequest - lastZoomRequest).toDouble())).coerceIn(512.0, 512.0 * 2.0.pow(22)))
        lastZoomRequest = zoomRequest
    }
    fun project(point: WorldPoint, viewport: TraceCamera = camera): Offset = Offset(
        (size.width / 2.0 + (point.x - viewport.centerX) * viewport.scale).toFloat(),
        (size.height / 2.0 + (point.y - viewport.centerY) * viewport.scale).toFloat())
    Canvas(modifier.background(background).onSizeChanged { size = it }
        .semantics { contentDescription = if (chinese) "已记录的行车轨迹，点选位置跳转回放" else "Recorded drive trace. Tap a measured point to seek playback." }
        .pointerInput(geometry, size) {
            detectTapGestures { tap ->
                val viewport = latestCamera
                RoadMapGeometry.nearest(geometry.points, tap.x.toDouble(), tap.y.toDouble(), 24.0 * density) { p ->
                    val screen = project(p, viewport); screen.x.toDouble() to screen.y.toDouble()
                }?.let { latestSeek(it.playbackMs) }
            }
        }
        .pointerInput(geometry, size) {
            detectTransformGestures { _, pan, zoom, _ ->
                latestGesture()
                val before = latestCamera
                camera = before.copy(centerX = before.centerX - pan.x / before.scale,
                    centerY = (before.centerY - pan.y / before.scale).coerceIn(0.0, 1.0),
                    scale = (before.scale * zoom).coerceIn(512.0, 512.0 * 2.0.pow(22)))
            }
        }) {
        // Preserve the local viewport and gestures, but do not rebuild a covered route every frame.
        if (!drawTrace) return@Canvas
        geometry.strokes.forEach { stroke ->
            val path = Path()
            stroke.forEachIndexed { index, point ->
                val screen = project(point)
                if (index == 0) path.moveTo(screen.x, screen.y) else path.lineTo(screen.x, screen.y)
            }
            if (stroke.size > 1) drawPath(path, foreground, style = Stroke(3.dp.toPx()))
            else stroke.firstOrNull()?.let { drawCircle(foreground, 3.dp.toPx(), project(it)) }
        }
        markerWorld()?.let { (x, y) ->
            val position = Offset((size.width / 2.0 + (x - camera.centerX) * camera.scale).toFloat(),
                (size.height / 2.0 + (y - camera.centerY) * camera.scale).toFloat())
            drawCircle(Color.White, 8.dp.toPx(), position)
            drawCircle(Color(0xFFE47625), 5.dp.toPx(), position)
        }
    }
}

@Composable
private fun MapCredits(chinese: Boolean) {
    val opener = LocalUriHandler.current
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        listOf("OpenFreeMap" to "https://openfreemap.org/", "© OpenMapTiles" to "https://openmaptiles.org/",
            "© OpenStreetMap" to "https://www.openstreetmap.org/copyright").forEach { (title, url) ->
            Text(title, Modifier.clickable { runCatching { opener.openUri(url) } }.padding(horizontal = 4.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall)
        }
        Text(if (chinese) "隐私" else "Privacy", Modifier.clickable { runCatching { opener.openUri("https://openfreemap.org/privacy/") } }
            .padding(horizontal = 4.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall)
    }
}
