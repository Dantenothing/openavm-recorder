package com.dante.zeekrcheck

import android.content.Context
import android.graphics.*
import android.util.AtomicFile
import android.util.LruCache
import com.dante.zeekrcheck.core.PaintMaster
import com.dante.zeekrcheck.core.VehicleAppearance
import java.io.File
import java.util.concurrent.Executors

/** One renderer for Compose and RemoteViews. Cache keys are immutable; old jobs cannot replace new revisions. */
internal object AppearanceRenderer {
    private val memory = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val executor = Executors.newSingleThreadExecutor()
    private val pending = mutableSetOf<String>()
    fun ready(appearance: VehicleAppearance, width: Int, showPlate: Boolean) = memory.get(appearance.cacheKey(width,showPlate))
    fun request(context: Context, appearance: VehicleAppearance, width: Int, showPlate: Boolean, completed: () -> Unit) {
        val key = appearance.cacheKey(width,showPlate)
        if (memory.get(key) != null || synchronized(pending) { !pending.add(key) }) return
        executor.execute {
            try { load(context,appearance,width,showPlate); completed() }
            catch (_: Exception) { /* Caller keeps the clear default; telemetry and controls stay usable. */ }
            finally { synchronized(pending) { pending.remove(key) } }
        }
    }
    @Synchronized fun load(context: Context, appearance: VehicleAppearance, width: Int, showPlate: Boolean): Bitmap {
        require(width in 160..1200 && appearance.problem() == null)
        val key = appearance.cacheKey(width,showPlate)
        memory.get(key)?.let { return it }
        val dir = File(context.cacheDir,"vehicle-appearance").apply { mkdirs() }
        val file = AtomicFile(File(dir,"$key.png"))
        val restored = runCatching { file.openRead().use { BitmapFactory.decodeStream(it) } }.getOrNull()
        val bitmap = restored ?: render(context,appearance,width,showPlate).also { image ->
            val stream = file.startWrite()
            try { check(image.compress(Bitmap.CompressFormat.PNG,100,stream)); file.finishWrite(stream) }
            catch (e: Exception) { file.failWrite(stream); throw e }
            dir.listFiles()?.filter { it.extension == "png" }?.sortedByDescending { it.lastModified() }?.drop(36)?.forEach { it.delete() }
        }
        memory.put(key,bitmap)
        return bitmap
    }
    fun render(context: Context, appearance: VehicleAppearance, width: Int, showPlate: Boolean): Bitmap {
        val master = BitmapFactory.decodeResource(context.resources,R.drawable.zeekr_7x_paint_master)
        val height = (width * master.height.toDouble() / master.width).toInt()
        val scaled = Bitmap.createScaledBitmap(master,width,height,true)
        if (master !== scaled) master.recycle()
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels,0,width,0,0,width,height)
        val body = Color.parseColor(appearance.bodyColor)
        for (i in pixels.indices) pixels[i] = PaintMaster.recolor(pixels[i],body)
        // createBitmap(IntArray, ...) is immutable; the perspective plate needs a writable canvas.
        val result = Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        result.setPixels(pixels,0,width,0,0,width,height)
        scaled.recycle()
        if (appearance.plateEnabled) {
            val plate = plate(appearance,showPlate,640)
            val matrix = Matrix()
            // Normalized corners of the blank front plate in master v1: TL, TR, BR, BL.
            val target = floatArrayOf(.0728f*width,.5840f*height, .1503f*width,.5874f*height,
                .1481f*width,.6475f*height, .0716f*width,.6426f*height)
            check(matrix.setPolyToPoly(floatArrayOf(0f,0f,plate.width.toFloat(),0f,plate.width.toFloat(),plate.height.toFloat(),0f,plate.height.toFloat()),0,target,0,4))
            Canvas(result).drawBitmap(plate,matrix,Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            plate.recycle()
        }
        return result
    }
    /** Same font, spacing and fit for enlarged preview and the perspective-mounted plate. */
    fun plate(appearance: VehicleAppearance, showText: Boolean, width: Int = 640): Bitmap {
        val height = width / 3
        val result = Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result); val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.parseColor(appearance.plateBackground)
        canvas.drawRoundRect(2f,2f,width-2f,height-2f,14f,14f,paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 6f; paint.color = Color.argb(90,0,0,0)
        canvas.drawRoundRect(4f,4f,width-4f,height-4f,12f,12f,paint)
        paint.style = Paint.Style.FILL; paint.color = Color.parseColor(appearance.plateForeground)
        paint.typeface = Typeface.create("sans-serif-condensed",Typeface.BOLD); paint.textAlign = Paint.Align.CENTER
        paint.textSize = height * .62f
        val text = if (showText) appearance.plateText else ""
        val length = paint.measureText(text)
        if (length > width * .88f) paint.textSize *= width * .88f / length
        val baseline = height / 2f - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f
        canvas.drawText(text,width/2f,baseline,paint)
        paint.shader = LinearGradient(0f,0f,0f,height.toFloat(), intArrayOf(0x18FFFFFF,0x00FFFFFF,0x15000000),null,Shader.TileMode.CLAMP)
        canvas.drawRoundRect(4f,4f,width-4f,height-4f,12f,12f,paint)
        return result
    }
}
