package com.dante.zeekrcapabilitylab.mirror

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.dante.zeekrcapabilitylab.usbexport.UsbExportVolumeResolver
import kotlinx.serialization.*
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.*

@Serializable data class PreviewPhoto(val uri: String, val name: String, val savedAt: Long)

/** A still from the existing preview, not a new Camera2 JPEG capture or recording segment. USB only. */
object PreviewPhotos {
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true }
    fun recent(context: Context): List<PreviewPhoto> = synchronized(lock) {
        runCatching { json.decodeFromString<List<PreviewPhoto>>(context.getSharedPreferences("preview_photos", 0).getString("items", "[]")!!) }.getOrDefault(emptyList())
    }
    fun save(context: Context, bitmap: Bitmap, lane: Int): PreviewPhoto {
        check(Build.VERSION.SDK_INT >= 29) { "USB_UNAVAILABLE" }
        val target = UsbExportVolumeResolver.mountedTargets(context).singleOrNull() ?: error("SELECT_ONE_USB")
        check((target.freeBytes ?: 0) > 32L * 1024 * 1024) { "USB_SPACE_LOW" }
        val name = "OpenAVM_View${lane}_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).format(Date()) + ".jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name); put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/OpenAVM/"); put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri(target.volumeName), values) ?: error("PHOTO_CREATE_FAILED")
        try {
            check(uri.authority == "media" && uri.pathSegments.firstOrNull().equals(target.volumeName, true)) { "PHOTO_VOLUME_MISMATCH" }
            resolver.openOutputStream(uri, "w").use { out -> requireNotNull(out); check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)); out.flush() }
            check(UsbExportVolumeResolver.resolveExact(context, target) != null) { "USB_REMOVED" }
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) == 1)
            val photo = PreviewPhoto(uri.toString(), name, System.currentTimeMillis())
            synchronized(lock) {
                context.getSharedPreferences("preview_photos", 0).edit().putString("items", json.encodeToString((listOf(photo) + recent(context)).take(40))).commit()
            }
            return photo
        } catch (t: Throwable) { runCatching { resolver.delete(uri, null, null) }; throw t }
    }
}
