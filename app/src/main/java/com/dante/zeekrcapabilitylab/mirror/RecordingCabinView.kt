package com.dante.zeekrcapabilitylab.mirror

import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.dante.zeekrcapabilitylab.util.Utils

/** Display-only child of the existing mirror. Both HOME and OVERLAY retain its SurfaceTexture. */
internal class RecordingCabinView(context: Context, private val stream: CabinPreviewBuffer) : FrameLayout(context) {
    private val handler = Handler(Looper.getMainLooper())
    private val video = TextureView(context)
    private val mask = TextView(context).apply {
        setTextColor(Color.WHITE); setBackgroundColor(Color.BLACK); gravity = Gravity.CENTER
        setPadding(16, 12, 16, 12)
    }
    private var texture: SurfaceTexture? = null
    private var decoder: CabinPreviewDecoder? = null
    private var selected = false
    private var closed = false
    private var attached = false
    private var problem: String? = null
    private var lastFrameAtMs = 0L
    private var fittedRevision = -1L
    internal var frames = 0L; private set

    init {
        setBackgroundColor(Color.BLACK)
        addView(video, LayoutParams(-1, -1)); addView(mask, LayoutParams(-1, -1))
        video.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(value: SurfaceTexture, width: Int, height: Int) {
                if (closed) return
                val old = texture
                if (old != null && old !== value) video.setSurfaceTexture(old)
                if (old == null) {
                    texture = value
                    val surface = Surface(value)
                    decoder = CabinPreviewDecoder.create(stream, surface,
                        failed = { code -> handler.post {
                            com.dante.zeekrcapabilitylab.event.EventLogger.logEvent(
                                com.dante.zeekrcapabilitylab.data.Categories.SYSTEM, "RECORDER_MIRROR_CABIN_DISPLAY_FAILED",
                                payload = mapOf("reason" to code, "recordingSessionId" to stream.sessionId))
                            problem = code; refresh()
                        } },
                        released = { handler.post { if (closed) releaseTexture(value) } })
                    if (decoder == null) { surface.release(); problem = "CABIN_DISPLAY_CLEANUP_PENDING" }
                }
                attached = true; fit(width, height); refresh()
            }
            override fun onSurfaceTextureSizeChanged(value: SurfaceTexture, width: Int, height: Int) { fit(width, height) }
            override fun onSurfaceTextureDestroyed(value: SurfaceTexture): Boolean {
                attached = false; decoder?.setActive(false)
                // Retain across a window move. Final close retires only after decoder release.
                return false
            }
            override fun onSurfaceTextureUpdated(value: SurfaceTexture) {
                if (!closed && selected) { frames++; lastFrameAtMs = SystemClock.elapsedRealtime(); refresh() }
            }
        }
    }
    fun select(value: Boolean) {
        if (selected != value) { selected = value; lastFrameAtMs = 0 }
        visibility = if (value) View.VISIBLE else View.GONE
        refresh()
    }
    fun refresh() {
        if (closed) return
        decoder?.setActive(selected && attached && isShown && MirrorDisplayState.usable(context))
        val current = stream.state()
        if (current.revision != fittedRevision && current.format != null && video.width > 0 && video.height > 0) {
            fit(video.width, video.height); fittedRevision = current.revision
        }
        val fresh = lastFrameAtMs > 0 && SystemClock.elapsedRealtime() - lastFrameAtMs < 2_000 && !current.closed
        mask.visibility = if (fresh && problem == null) View.GONE else View.VISIBLE
        mask.text = when {
            problem != null -> Utils.t("Cabin view unavailable. Recording continues; select a surround view.", "舱内预览暂不可用；录像继续，请选择环视方向。")
            current.closed -> Utils.t("Cabin recording has ended. Select a surround view.", "舱内录像已结束，请选择环视方向。")
            else -> Utils.t("Preparing cabin view · recording continues", "正在显示舱内画面 · 录像继续")
        }
    }
    private fun fit(width: Int, height: Int) {
        val format = stream.state().format ?: return
        if (width <= 0 || height <= 0) return
        val scale = minOf(width.toFloat() / format.width, height.toFloat() / format.height)
        video.setTransform(Matrix().apply { setScale(format.width * scale / width, format.height * scale / height,
            width / 2f, height / 2f) })
    }
    fun close() {
        if (closed) return
        closed = true; selected = false
        (parent as? android.view.ViewGroup)?.removeView(this)
        val current = decoder
        if (current == null || current.cleanupConfirmed) texture?.let(::releaseTexture) else current.close()
    }
    private fun releaseTexture(value: SurfaceTexture) {
        if (texture === value) { texture = null; value.release() }
    }
    fun cleanupConfirmed() = closed && (decoder?.cleanupConfirmed != false)
}
