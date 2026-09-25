package com.dante.zeekrcheck

import android.annotation.SuppressLint
import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import androidx.core.location.LocationManagerCompat
import android.os.CancellationSignal
import com.dante.zeekrcheck.core.CarLocation
import com.dante.zeekrcheck.core.VehicleAddress
import kotlinx.coroutines.*
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Uses the phone's geocoding provider; no private server, map API key or stored search history. */
internal class PlaceLookup(private val context: Context) {
    suspend fun nearbyRoad(point: CarLocation): String? = withTimeoutOrNull(8_000) {
        if (!Geocoder.isPresent()) return@withTimeoutOrNull null
        val geocoder = Geocoder(context, Locale.ENGLISH)
        val addresses = if (Build.VERSION.SDK_INT >= 33) suspendCancellableCoroutine<List<Address>> { continuation ->
            geocoder.getFromLocation(point.latitude, point.longitude, 3, object : Geocoder.GeocodeListener {
                override fun onGeocode(addresses: MutableList<Address>) { if (continuation.isActive) continuation.resume(addresses) }
                override fun onError(errorMessage: String?) { if (continuation.isActive) continuation.resume(emptyList()) }
            })
        } else withContext(Dispatchers.IO) {
            @Suppress("DEPRECATION")
            geocoder.getFromLocation(point.latitude, point.longitude, 3).orEmpty()
        }
        val nearby = addresses.filter { address -> !address.hasLatitude() || !address.hasLongitude() ||
            runCatching { point.distance(CarLocation(address.latitude, address.longitude, null)) <= 500 }.getOrDefault(false) }
        val address = nearby.firstOrNull { !it.thoroughfare.isNullOrBlank() } ?: nearby.firstOrNull()
        address?.let { VehicleAddress.label(it.subThoroughfare, it.thoroughfare, it.subLocality ?: it.locality) }
    }
    suspend fun search(query: String): List<CarLocation> {
        require(query.trim().length in 4..200) { "请输入至少 4 个字符的地址" }
        return addresses(query.trim()).mapNotNull { address ->
            if (!address.hasLatitude() || !address.hasLongitude()) null
            else runCatching { CarLocation(address.latitude, address.longitude, null,
                address.getAddressLine(0)?.take(160) ?: query.take(160)) }.getOrNull()
        }.distinctBy { it.latitude to it.longitude }
    }
    private suspend fun addresses(query: String): List<Address> = withTimeout(15_000) {
        check(Geocoder.isPresent()) { "这台手机暂不支持地址匹配，可以用手机当前位置设为家" }
        val geocoder = Geocoder(context, Locale.ENGLISH)
        if (Build.VERSION.SDK_INT >= 33) suspendCancellableCoroutine { continuation ->
            geocoder.getFromLocationName(query, 5, -44.0, 112.0, -10.0, 154.0, object : Geocoder.GeocodeListener {
                override fun onGeocode(addresses: MutableList<Address>) { if (continuation.isActive) continuation.resume(addresses) }
                override fun onError(errorMessage: String?) { if (continuation.isActive) continuation.resumeWithException(IllegalStateException("地址匹配暂不可用，请稍后重试或使用当前位置")) }
            })
        } else withContext(Dispatchers.IO) {
            @Suppress("DEPRECATION")
            geocoder.getFromLocationName(query, 5, -44.0, 112.0, -10.0, 154.0).orEmpty()
        }
    }
    @SuppressLint("MissingPermission") // Invoked only from the foreground permission result / granted branch.
    suspend fun phoneLocation(): CarLocation = withTimeout(20_000) {
        val manager = context.getSystemService(LocationManager::class.java)
        val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).firstOrNull {
            runCatching { manager.isProviderEnabled(it) }.getOrDefault(false)
        } ?: error("请先打开手机定位，或使用地址搜索")
        val location = suspendCancellableCoroutine<android.location.Location?> { continuation ->
            val cancel = CancellationSignal()
            continuation.invokeOnCancellation { cancel.cancel() }
            LocationManagerCompat.getCurrentLocation(manager, provider, cancel,
                androidx.core.content.ContextCompat.getMainExecutor(context)) { result ->
                if (continuation.isActive) continuation.resume(result)
            }
        } ?: error("暂时没有取得手机位置，请稍后重试")
        check((SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) in 0..120_000_000_000L &&
            location.hasAccuracy() && location.accuracy <= 300f) { "手机定位精度不足，请开启精确位置或使用地址搜索" }
        CarLocation(location.latitude, location.longitude, location.time, "手机当前位置", false)
    }
}
