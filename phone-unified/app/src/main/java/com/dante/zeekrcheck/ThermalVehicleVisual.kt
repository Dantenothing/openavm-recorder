package com.dante.zeekrcheck

import android.animation.ValueAnimator
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.dante.zeekrcheck.core.Airflow
import kotlin.math.min

private const val ART_WIDTH = 1000f
private const val ART_HEIGHT = 1000f * 471f / 840f

/** Fit the atmosphere to the same transparent artwork as the car, never to its view bounds. */
private fun AndroidCanvas.withCarFrame(width: Float, height: Float, draw: AndroidCanvas.() -> Unit) {
    val scale = min(width / ART_WIDTH, height / ART_HEIGHT)
    save()
    translate((width - ART_WIDTH * scale) / 2, (height - ART_HEIGHT * scale) / 2)
    scale(scale,scale)
    draw()
    restore()
}

/** Soft cabin light, fully transparent well before the image edges; no oval clipping. */
internal class ThermalAmbientPainter {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shaders = mapOf("cold" to 0x006DADC6, "hot" to 0x00D6A16C).mapValues { (_, rgb) ->
        RadialGradient(0f,0f,1f,intArrayOf(rgb or (34 shl 24),rgb or (17 shl 24),rgb or (4 shl 24),rgb),
            floatArrayOf(0f,.42f,.75f,1f),Shader.TileMode.CLAMP)
    }
    fun draw(canvas: AndroidCanvas, width: Float, height: Float, ambient: String) {
        paint.shader = shaders[ambient] ?: return
        if (width <= 0 || height <= 0) return
        canvas.withCarFrame(width,height) {
            translate(650f,218f)
            scale(330f,195f)
            drawRect(-1f,-1f,1f,1f,paint)
        }
    }
}

@Composable internal fun ThermalAmbient(ambient: String, modifier: Modifier = Modifier) {
    val painter = remember { ThermalAmbientPainter() }
    Canvas(modifier) { drawIntoCanvas { painter.draw(it.nativeCanvas,size.width,size.height,ambient) } }
}

/** Transparent artwork over the existing car: paint and plate pixels are never recolored. */
internal class ThermalAirPainter {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val segment = Path()
    private val paths = (0..2).map { row ->
        val y = 150f + row * 33f
        Path().apply { moveTo(390f, y); cubicTo(460f,y-27f,490f,y+25f,570f,y+4f); cubicTo(660f,y-22f,715f,y+26f,815f,y+7f) }
    }
    private val measures = paths.map { PathMeasure(it, false) }
    fun draw(canvas: AndroidCanvas, width: Float, height: Float, flow: Airflow, progress: Float? = null) {
        if (flow == Airflow.NONE || width <= 0 || height <= 0) return
        canvas.withCarFrame(width,height) {
            paint.color = when (flow) {
                Airflow.COOLING -> 0xFF4B9FC5.toInt()
                Airflow.WARMING -> 0xFFD2965F.toInt()
                else -> 0xFF89AAA0.toInt()
            }
            paths.forEachIndexed { index, path ->
                if (flow == Airflow.HOLD && index == 2) return@forEachIndexed
                paint.strokeWidth = 15f; paint.alpha = if (flow == Airflow.HOLD) 18 else 25
                canvas.drawPath(path, paint)
                paint.strokeWidth = 4.5f; paint.alpha = if (progress == null) 175 else 58
                canvas.drawPath(path, paint)
                if (progress != null) {
                    val measure = measures[index]
                    val head = ((progress + index * .16f) % 1f) * 1.4f
                    segment.reset()
                    measure.getSegment(((head - .4f) * measure.length).coerceAtLeast(0f), (head * measure.length).coerceAtMost(measure.length), segment, true)
                    paint.strokeWidth = 5.5f; paint.alpha = if (flow == Airflow.HOLD) 160 else 225
                    canvas.drawPath(segment, paint)
                }
            }
        }
    }
}

/** Small cached overlays, shared across RemoteViews sizes; no frame scheduler. */
internal object ThermalWidgetArtwork {
    private val cache = mutableMapOf<Airflow, Bitmap>()
    private val ambientCache = mutableMapOf<String, Bitmap>()
    @Synchronized fun ambientBitmap(ambient: String): Bitmap = ambientCache.getOrPut(ambient.takeIf { it in setOf("hot","cold") } ?: "neutral") {
        Bitmap.createBitmap(320,179,Bitmap.Config.ARGB_8888).also {
            ThermalAmbientPainter().draw(AndroidCanvas(it),it.width.toFloat(),it.height.toFloat(),ambient)
        }
    }
    @Synchronized fun bitmap(flow: Airflow): Bitmap = cache.getOrPut(flow) {
        Bitmap.createBitmap(320, 179, Bitmap.Config.ARGB_8888).also {
            ThermalAirPainter().draw(AndroidCanvas(it), it.width.toFloat(), it.height.toFloat(), flow)
        }
    }
}

@Composable internal fun ThermalAirflow(flow: Airflow, modifier: Modifier = Modifier) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateAsState()
    val resolver = LocalContext.current.contentResolver
    var motionAllowed by remember { mutableStateOf(ValueAnimator.areAnimatorsEnabled()) }
    var visible by remember { mutableStateOf(false) }
    DisposableEffect(resolver, lifecycle) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { motionAllowed = ValueAnimator.areAnimatorsEnabled() }
        }
        val resume = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) motionAllowed = ValueAnimator.areAnimatorsEnabled() }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        lifecycle.addObserver(resume)
        onDispose { resolver.unregisterContentObserver(observer); lifecycle.removeObserver(resume) }
    }
    val animate = flow != Airflow.NONE && visible && motionAllowed && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    // The transition only exists while visible and resumed; Compose also observes duration scale.
    val progress = if (animate) rememberInfiniteTransition(label = "cabinAir").animateFloat(0f, 1f,
        infiniteRepeatable(tween(if (flow == Airflow.HOLD) 6000 else 4200, easing = LinearEasing)), label = "airSweep") else null
    val painter = remember { ThermalAirPainter() }
    Canvas(modifier.testTag("thermal_air_${flow.name}").onGloballyPositioned {
        val bounds = it.boundsInWindow()
        visible = bounds.width > 0 && bounds.height > 0
    }) {
        drawIntoCanvas { painter.draw(it.nativeCanvas, size.width, size.height, flow, progress?.value) }
    }
}
