package com.dante.zeekrcapabilitylab.preflight.continuous

import android.content.Context
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.*
import android.widget.FrameLayout
import android.widget.TextView
import com.dante.zeekrcapabilitylab.preflight.obj
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** UI targets never own the camera input. Texture destruction waits for its EGL reader to detach. */
internal object CameraProbeWindows {
    enum class Kind { PAGE, MIRROR }
    internal class Target(val texture:SurfaceTexture,val surface:Surface,val width:Int,val height:Int,val kind:Kind,
        val hardwareAccelerated:Boolean) {
        @Volatile var retired=false
        private var readers=0
        private var released=false
        private var draws=0;private var updates=0;private var lastFrame=-1
        private var lastDrawAt=0L;private var maxDrawGap=0L
        private var firstDrawAt=0L
        private var lastUpdateAt=0L;private var maxUpdateGap=0L
        private var firstUpdateAt=0L
        private var lastPresentedNs=0L;private var distinctUpdates=0
        private var maximumDrawGapAtFrame:Int?=null
        private var maximumUpdateGapAtTimestampNs:Long?=null
        @Synchronized fun take():Boolean { if(retired || released)return false;readers++;return true }
        @Synchronized fun releaseReader() {check(readers>0);readers--;releaseIfIdle()}
        @Synchronized fun retire() {
            if(active) {
                // A script detach ends this target's active interval. Preserve a freeze before removal.
                val now=SystemClock.elapsedRealtime()
                if(lastDrawAt>0)maxDrawGap=maxOf(maxDrawGap,now-lastDrawAt)
                if(lastUpdateAt>0)maxUpdateGap=maxOf(maxUpdateGap,now-lastUpdateAt)
            }
            retired=true;releaseIfIdle()
        }
        private fun releaseIfIdle() { if(retired && readers==0 && !released){surface.release();texture.release();released=true} }
        @Synchronized fun drawn(frame:Int) {
            check(frame>lastFrame);lastFrame=frame;draws++
            val now=SystemClock.elapsedRealtime()
            if(lastDrawAt>0 && now-lastDrawAt>maxDrawGap){maxDrawGap=now-lastDrawAt;maximumDrawGapAtFrame=frame}
            lastDrawAt=now
            if(firstDrawAt==0L)firstDrawAt=now
        }
        @Synchronized fun updated() {
            if(released)return
            val now=SystemClock.elapsedRealtime();updates++
            val stamp=texture.timestamp
            if(stamp>lastPresentedNs) {
                distinctUpdates++;lastPresentedNs=stamp
                if(lastUpdateAt>0 && now-lastUpdateAt>maxUpdateGap){maxUpdateGap=now-lastUpdateAt;maximumUpdateGapAtTimestampNs=stamp}
                lastUpdateAt=now
                if(firstUpdateAt==0L)firstUpdateAt=now
            }
        }
        @Synchronized fun evidence()=obj("kind" to kind.name,"width" to width,"height" to height,"hardwareAccelerated" to hardwareAccelerated,"draws" to draws,
            "textureCallbacks" to updates,"distinctPresentedTimestamps" to distinctUpdates,"lastFrame" to lastFrame,
            "maximumDrawGapMs" to maxDrawGap,"maximumTextureUpdateGapMs" to maxUpdateGap,
            "maximumDrawGapAtFrame" to maximumDrawGapAtFrame,"maximumUpdateGapAtPresentationTimestampNs" to maximumUpdateGapAtTimestampNs?.toString(),
            "drawFps" to fps(draws,firstDrawAt,lastDrawAt),"textureUpdateFps" to fps(distinctUpdates,firstUpdateAt,lastUpdateAt),
            "lastDrawAgeMs" to if(lastDrawAt>0)SystemClock.elapsedRealtime()-lastDrawAt else null,
            "lastUpdateAgeMs" to if(lastUpdateAt>0)SystemClock.elapsedRealtime()-lastUpdateAt else null,
            "retired" to retired,"nativeReaders" to readers,"released" to released)
        private fun fps(count:Int,first:Long,last:Long)=if(last>first)(count-1)*1000.0/(last-first) else 0.0
        @Synchronized fun healthy(live:Boolean):Boolean {
            val now=SystemClock.elapsedRealtime()
            return hardwareAccelerated && draws>=3 && distinctUpdates>=3 && CameraWindowTiming(fps(draws,firstDrawAt,lastDrawAt),
                fps(distinctUpdates,firstUpdateAt,lastUpdateAt),maxDrawGap,maxUpdateGap,
                if(lastDrawAt>0)now-lastDrawAt else null,if(lastUpdateAt>0)now-lastUpdateAt else null).healthy(live && !retired)
        }
        @Synchronized fun idle()=readers==0
        @Synchronized fun fullyReleased()=readers==0 && retired && released
    }
    private val main=Handler(Looper.getMainLooper())
    private var page:CameraProbePreviewHost?=null
    private var overlay:FrameLayout?=null
    private var manager:WindowManager?=null
    private val targets=ArrayList<Target>()
    @Volatile private var active=false
    @Volatile private var mirrorEnabled=false
    @Volatile private var windowFailure:String?=null
    @Volatile var detachCount=0;private set
    @Volatile var rejoinCount=0;private set
    private fun ui(action:()->Unit) {
        if(Looper.myLooper()==Looper.getMainLooper()){action();return}
        val done=CountDownLatch(1);var problem:Throwable?=null
        main.post {try{action()}catch(t:Throwable){problem=t}finally{done.countDown()}}
        check(done.await(3,TimeUnit.SECONDS)) { "P2_WINDOW_MAIN_TIMEOUT" };problem?.let {throw it}
    }
    fun register(host:CameraProbePreviewHost) {page=host;if(active)host.show(true)}
    fun unregister(host:CameraProbePreviewHost) {if(page===host)page=null;host.show(false)}
    fun begin(context:Context) = ui {
        check(!active) {"P2_WINDOWS_ALREADY_ACTIVE"}
        check(page?.isAttachedToWindow==true) {"P2_PAGE_WINDOW_UNAVAILABLE"}
        check(Settings.canDrawOverlays(context)) {"P2_OVERLAY_PERMISSION_REQUIRED"}
        synchronized(targets) {check(targets.all {it.idle()});targets.clear()}
        windowFailure=null;detachCount=0;rejoinCount=0;active=true;page?.show(true);showMirror(context)
    }
    private fun showMirror(context:Context) {
        if(!active || overlay!=null)return
        val wm=context.getSystemService(WindowManager::class.java);manager=wm
        val box=FrameLayout(context).apply {
            background=GradientDrawable().apply {setColor(0xe61a1a1a.toInt());cornerRadius=16f;setStroke(1,0xff606060.toInt())}
            setPadding(4,4,4,4)
        }
        box.addView(CameraProbePreviewHost(context,Kind.MIRROR).apply {show(true)},FrameLayout.LayoutParams(-1,-1))
        box.addView(TextView(context).apply {text="OpenAVM · 连续后视镜测试";setTextColor(Color.WHITE);setBackgroundColor(0xb31a1a1a.toInt());textSize=13f},
            FrameLayout.LayoutParams(-1,30,Gravity.TOP))
        val metrics=context.resources.displayMetrics
        val width=minOf((460*metrics.density).toInt(),metrics.widthPixels/2)
        val params=WindowManager.LayoutParams(width,width*9/16,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,PixelFormat.TRANSLUCENT).apply {
            gravity=Gravity.END or Gravity.BOTTOM;x=24;y=24
        }
        overlay=box
        wm.addView(box,params);mirrorEnabled=true
    }
    fun mirror(context:Context,enabled:Boolean)=ui {
        if(enabled) {if(!mirrorEnabled){showMirror(context);rejoinCount++}}
        else if(mirrorEnabled) {hideMirror();detachCount++}
    }
    fun requestMirror(context:Context,enabled:Boolean) {
        main.post {runCatching {if(active)mirror(context,enabled)}.onFailure {windowFailure=it.javaClass.simpleName}}
    }
    private fun hideMirror() {overlay?.let {if(it.isAttachedToWindow)manager?.removeViewImmediate(it)};overlay=null;mirrorEnabled=false}
    fun end()=ui {active=false;hideMirror();page?.show(false)}
    fun add(texture:SurfaceTexture,width:Int,height:Int,kind:Kind,accelerated:Boolean):Target = synchronized(targets) {
        check(targets.size<16) {"P2_WINDOW_TARGET_BUDGET"}
        Target(texture,Surface(texture),width,height,kind,accelerated).also {targets+=it}
    }
    fun callbackFailed(t:Throwable) {windowFailure=t.javaClass.simpleName}
    fun current(kind:Kind):Target?=synchronized(targets) {targets.lastOrNull {it.kind==kind && !it.retired}}
    fun evidence(live:Boolean=false)=synchronized(targets) {obj("scope" to "REAL_PAGE_AND_APPLICATION_OVERLAY_TEXTUREVIEWS",
        "liveFreshnessChecked" to live,"freshnessAndGapLimitMs" to 200,"minimumObservedFps" to 24,
        "overlayDetachCount" to detachCount,"overlayRejoinCount" to rejoinCount,"targets" to targets.map {it.evidence()},
        "windowFailure" to windowFailure,
        "status" to if(windowFailure==null && Kind.entries.all {kind->targets.any {it.kind==kind && (!live || !it.retired)}} &&
            targets.all {it.healthy(live)})"PASS" else "FAIL")}
    fun readersReleased()=synchronized(targets) {targets.all {it.fullyReleased()}}
}

internal class CameraProbePreviewHost(context:Context,private val kind:CameraProbeWindows.Kind=CameraProbeWindows.Kind.PAGE):FrameLayout(context) {
    private var preview:TextureView?=null
    fun show(enabled:Boolean) {
        if(enabled && preview==null) {
            val v=TextureView(context)
            v.surfaceTextureListener=object:TextureView.SurfaceTextureListener {
                private var target:CameraProbeWindows.Target?=null
                override fun onSurfaceTextureAvailable(t:SurfaceTexture,w:Int,h:Int) {
                    runCatching {target=CameraProbeWindows.add(t,w,h,kind,v.isHardwareAccelerated)}.onFailure(CameraProbeWindows::callbackFailed)
                }
                override fun onSurfaceTextureSizeChanged(t:SurfaceTexture,w:Int,h:Int) { /* EGL uses current viewport from its target; layout stays fixed within each run. */ }
                override fun onSurfaceTextureDestroyed(t:SurfaceTexture):Boolean {
                    val owned=target ?: return true
                    runCatching {owned.retire()}.onFailure(CameraProbeWindows::callbackFailed);return false
                }
                override fun onSurfaceTextureUpdated(t:SurfaceTexture) {runCatching {target?.updated()}.onFailure(CameraProbeWindows::callbackFailed)}
            }
            preview=v;addView(v,LayoutParams(-1,-1))
        } else if(!enabled) {preview?.let(::removeView);preview=null}
    }
    override fun onAttachedToWindow() {super.onAttachedToWindow();if(kind==CameraProbeWindows.Kind.PAGE)CameraProbeWindows.register(this)}
    override fun onDetachedFromWindow() {if(kind==CameraProbeWindows.Kind.PAGE)CameraProbeWindows.unregister(this);super.onDetachedFromWindow()}
}
