package sefirah.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.LinkProperties
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import sefirah.domain.interfaces.DeviceManager
import sefirah.domain.model.PairedDevice
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps paired devices connected without the user pressing "Sync".
 *
 * - Listens to the default network for as long as the service runs, whatever the "trusted networks"
 *   setting is. A network change restarts discovery and drops connections that were bound to the old
 *   network (a TCP socket over a vanished Wi-Fi never reports an error by itself).
 * - Periodically retries every paired device that is disconnected (not by the user), so a missed
 *   mDNS/UDP announcement or a PC that started later is still picked up.
 * - Periodically re-sends our own UDP announcement while any paired device is disconnected, instead
 *   of only on app start/screen-on/Wi-Fi change. A PC that boots hours after the phone was last
 *   active (or missed our one-shot announcement) otherwise never hears from us until one of those
 *   events happens to fire, forcing a manual "refresh" on the desktop side.
 * - Periodically sends an application-level heartbeat to every connected device and drops any
 *   connection that has gone silent, since a dead socket doesn't always surface a read/write
 *   error by itself (e.g. a NAT mapping that expired while idle).
 * - Logs every decision under the tag [TAG].
 */
@Singleton
class ConnectionWatchdog @Inject constructor(
    private val context: Context,
    private val deviceManager: DeviceManager,
    private val networkDiscovery: NetworkDiscovery,
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var scope: CoroutineScope? = null
    private var loopJob: Job? = null
    private var connect: (suspend (PairedDevice) -> Unit)? = null
    private var dropConnections: (suspend () -> Unit)? = null
    private var heartbeat: (suspend () -> Unit)? = null

    private val wakeUp = Channel<String>(Channel.CONFLATED)
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    @Volatile private var currentNetwork: Network? = null
    @Volatile private var lastNetworkChangeAt = 0L
    private var callbackRegistered = false
    private var lanCallbackRegistered = false
    private val networkChangePending = AtomicBoolean(false)
    private val lanAddresses = ConcurrentHashMap<Network, String>()
    private var lastAnnouncementAt = Long.MIN_VALUE

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val previous = currentNetwork
            currentNetwork = network
            if (previous == network) return
            Log.i(TAG, "Default network available: $network (previous: $previous)")
            onNetworkChanged("default network available")
        }

        override fun onLost(network: Network) {
            if (currentNetwork != network) return
            Log.i(TAG, "Default network lost: $network")
            currentNetwork = null
            onNetworkChanged("default network lost")
        }
    }

    // The default network may remain a VPN while Wi-Fi disappears or comes back.
    private val lanCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = onNetworkChanged("LAN available")
        override fun onLost(network: Network) {
            lanAddresses.remove(network)
            onNetworkChanged("LAN lost")
        }
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
            val addresses = properties.linkAddresses.map { it.toString() }.sorted().joinToString()
            val previous = lanAddresses.put(network, addresses)
            if (previous != null && previous != addresses) onNetworkChanged("LAN addresses changed")
        }
    }

    fun start(
        scope: CoroutineScope,
        connect: suspend (PairedDevice) -> Unit,
        dropConnections: suspend () -> Unit,
        heartbeat: suspend () -> Unit,
    ) {
        if (loopJob?.isActive == true) return
        this.scope = scope
        this.connect = connect
        this.dropConnections = dropConnections
        this.heartbeat = heartbeat

        try {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
            callbackRegistered = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register default network callback", e)
        }
        try {
            connectivityManager.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                    .build(), lanCallback,
            )
            lanCallbackRegistered = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register LAN callback", e)
        }

        loopJob = scope.launch {
            Log.i(TAG, "Watchdog started")
            while (isActive) {
                val interval = if (SystemClock.elapsedRealtime() - lastNetworkChangeAt < FAST_WINDOW_MS) {
                    FAST_INTERVAL_MS
                } else {
                    SLOW_INTERVAL_MS
                }
                val reason = withTimeoutOrNull(interval) { wakeUp.receive() } ?: "periodic"
                try {
                    if (networkChangePending.get()) {
                        // VPN toggles emit several callbacks. One serialized reset prevents an old
                        // onLost coroutine from closing the session established by a later event.
                        delay(500L)
                        if (networkChangePending.getAndSet(false)) {
                            dropConnections?.invoke() // Also clears obsolete connection backoff.
                            networkDiscovery.restartDiscovery(reason)
                            lastAnnouncementAt = Long.MIN_VALUE
                        }
                    }
                    heartbeat?.invoke()
                    retryPairedDevices(reason)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Watchdog recovery failed; will retry on the next pass", e)
                }
            }
        }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        if (callbackRegistered) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unregister network callback", e)
            }
            callbackRegistered = false
        }
        currentNetwork = null
        if (lanCallbackRegistered) {
            runCatching { connectivityManager.unregisterNetworkCallback(lanCallback) }
            lanCallbackRegistered = false
        }
        lanAddresses.clear()
        networkChangePending.set(false)
        this.scope = null
        connect = null
        dropConnections = null
        heartbeat = null
        inFlight.clear()
        Log.i(TAG, "Watchdog stopped")
    }

    /** Asks for an immediate retry pass (e.g. screen on, Wi-Fi toggled). */
    fun retryNow(reason: String) {
        wakeUp.trySend(reason)
    }

    private fun onNetworkChanged(reason: String) {
        lastNetworkChangeAt = SystemClock.elapsedRealtime()
        networkChangePending.set(true)
        retryNow(reason)
    }

    private suspend fun retryPairedDevices(reason: String) {
        if (!hasLanCapableNetwork()) {
            Log.d(TAG, "[$reason] no LAN-capable network, skipping")
            return
        }
        if (!networkDiscovery.isDiscoveryAllowed()) {
            Log.d(TAG, "[$reason] current network not trusted, skipping")
            return
        }

        val candidates = deviceManager.pairedDevices.value.filter {
            !it.connectionState.isConnectedOrConnecting && !it.connectionState.isForcedDisconnect
        }
        if (candidates.isEmpty()) return

        // Re-announce ourselves so a PC that just booted (and so never heard our one-shot
        // startup broadcast) can discover us without the user touching anything.
        val now = SystemClock.elapsedRealtime()
        if (lastAnnouncementAt == Long.MIN_VALUE || now - lastAnnouncementAt >= SLOW_INTERVAL_MS) {
            lastAnnouncementAt = now
            networkDiscovery.broadcastDevice()
        }

        val connect = connect ?: return
        candidates.forEach { device ->
            if (!inFlight.add(device.deviceId)) return@forEach
            // A sleeping/offline PC must not hold up heartbeat checks for the other devices.
            scope?.launch {
                try {
                    connect(device)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "[$reason] retry of ${device.deviceName} failed", e)
                } finally {
                    inFlight.remove(device.deviceId)
                }
            }
        }
    }

    /** True unless the only network is cellular (a LAN device can't be reached there). */
    private fun hasLanCapableNetwork(): Boolean {
        if (connectivityManager.allNetworks.any { candidate ->
                val capabilities = connectivityManager.getNetworkCapabilities(candidate)
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                    capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
            }) return true
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)
    }

    private companion object {
        const val TAG = "ConnectionWatchdog"
        const val FAST_WINDOW_MS = 2 * 60_000L
        const val FAST_INTERVAL_MS = 15_000L
        const val SLOW_INTERVAL_MS = 60_000L
    }
}
