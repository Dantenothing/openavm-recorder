package com.dante.zeekrcapabilitylab.sharing

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.LinkProperties
import android.os.SystemClock
import android.util.Base64
import com.dante.zeekrcapabilitylab.service.CameraRecordingService
import com.dante.zeekrcapabilitylab.service.recorder.CaptureCleanupRuntime
import java.io.File
import java.io.Closeable
import java.net.Inet4Address
import java.net.InetAddress
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SharePhase { IDLE, PREPARING, ACTIVE, CLOSING, ENDED, FAILED }
enum class ShareProblem { STOP_RECORDING, WIFI_REQUIRED, FILES_UNAVAILABLE }
data class BrowserShareState(val phase: SharePhase = SharePhase.IDLE, val url: String? = null,
    val expiresAtMs: Long = 0, val problem: ShareProblem? = null)

/** One visible, user-requested LAN share. No service, camera, wake lock or background restart. */
object BrowserShareManager {
    private class Run {
        val cancelled = AtomicBoolean()
        @Volatile var server: LocalShareServer? = null
    }
    private data class Wifi(val network: Network, val address: InetAddress)
    private class WifiWatch(context: Context, private val expected: Wifi) : Closeable {
        private val connectivity = context.getSystemService(ConnectivityManager::class.java)
        @Volatile var valid = true
            private set
        private val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) { if (network == expected.network) valid = false }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (network == expected.network && (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN))) valid = false
            }
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
                if (network == expected.network && properties.linkAddresses.none { it.address == expected.address }) valid = false
            }
        }
        init {
            connectivity.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback)
            try { if (wifi(context) != expected) valid = false }
            catch (error: Exception) { close(); throw error }
        }
        override fun close() { valid = false; runCatching { connectivity.unregisterNetworkCallback(callback) } }
    }
    private val lock = Any()
    private var current: Run? = null
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "openavm-share-prepare").apply { isDaemon = true } }
    private val mutableState = MutableStateFlow(BrowserShareState())
    val state = mutableState.asStateFlow()
    val busy: Boolean get() = synchronized(lock) { current != null }
    val limits = ShareLimits()

    fun start(context: Context, selection: ShareSelection, chosen: List<File>, includeJson: Boolean,
              labels: SharePageLabels) {
        if (!com.dante.zeekrcapabilitylab.BuildConfig.BROWSER_DOWNLOAD_ENABLED) return
        val run = synchronized(lock) {
            if (current != null) return
            if (CameraRecordingService.isRunning() || com.dante.zeekrcapabilitylab.enhancement.CameraWorkCoordinator.state.value.active || CaptureCleanupRuntime.pendingOwners.value > 0) {
                mutableState.value = BrowserShareState(SharePhase.FAILED, problem = ShareProblem.STOP_RECORDING)
                return
            }
            Run().also { current = it; mutableState.value = BrowserShareState(SharePhase.PREPARING) }
        }
        val app = context.applicationContext
        worker.execute {
            var prepared: PreparedShare? = null
            var networkWatch: WifiWatch? = null
            var serverOwnsLease = false
            var problem = ShareProblem.WIFI_REQUIRED
            var failure: BrowserShareState? = null
            try {
                val wifi = checkNotNull(wifi(app))
                val watch = WifiWatch(app, wifi)
                networkWatch = watch
                problem = ShareProblem.FILES_UNAVAILABLE
                val files = ShareSources.prepare(app, selection, chosen, includeJson, limits)
                prepared = files
                if (run.cancelled.get()) return@execute
                val token = Base64.encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) },
                    Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
                val server = LocalShareServer(wifi.address, token, files.assets, labels, limits,
                    nowMs = SystemClock::elapsedRealtime,
                    environmentValid = {
                        !run.cancelled.get() && !CameraRecordingService.isRunning() &&
                            !com.dante.zeekrcapabilitylab.enhancement.CameraWorkCoordinator.state.value.active &&
                            CaptureCleanupRuntime.pendingOwners.value == 0 && watch.valid
                    },
                    onClosed = {
                        watch.close()
                        files.close()
                        finish(run, BrowserShareState(SharePhase.ENDED))
                    })
                serverOwnsLease = true
                synchronized(lock) {
                    run.server = server
                    if (current === run && !run.cancelled.get() && server.isActive) {
                        mutableState.value = BrowserShareState(SharePhase.ACTIVE, server.url, server.expiresAtMs)
                    }
                }
                if (run.cancelled.get()) server.close()
            } catch (_: Exception) {
                if (!run.cancelled.get()) failure = BrowserShareState(SharePhase.FAILED, problem = problem)
            } finally {
                if (!serverOwnsLease) {
                    networkWatch?.close()
                    prepared?.close()
                    finish(run, failure ?: BrowserShareState(SharePhase.ENDED))
                }
            }
        }
    }

    fun stop() {
        val run = synchronized(lock) {
            current?.also {
                it.cancelled.set(true)
                mutableState.value = BrowserShareState(SharePhase.CLOSING)
            }
        } ?: return
        // No manager lock around close: a reader can finish on another thread.
        run.server?.close()
    }

    /** Recording may start only once download readers and preparation have actually finished. */
    fun stopForRecording(): Boolean { stop(); return !busy }

    private fun finish(run: Run, state: BrowserShareState) = synchronized(lock) {
        if (current === run) { current = null; mutableState.value = state }
    }

    @Suppress("DEPRECATION")
    private fun wifi(context: Context): Wifi? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        return connectivity.allNetworks.asSequence().mapNotNull { network ->
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
            val address = connectivity.getLinkProperties(network)?.linkAddresses?.map { it.address }?.firstOrNull {
                it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isAnyLocalAddress
            } ?: return@mapNotNull null
            Wifi(network, address)
        }.firstOrNull()
    }
}
