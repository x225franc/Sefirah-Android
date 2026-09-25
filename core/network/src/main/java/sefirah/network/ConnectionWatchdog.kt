package sefirah.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import sefirah.domain.interfaces.DeviceManager
import sefirah.domain.model.PairedDevice
import java.util.concurrent.ConcurrentHashMap
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

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val previous = currentNetwork
            currentNetwork = network
            if (previous == network) return
            Log.i(TAG, "Default network available: $network (previous: $previous)")
            onNetworkChanged("network available", dropExisting = previous != null)
        }

        override fun onLost(network: Network) {
            if (currentNetwork != network) return
            Log.i(TAG, "Default network lost: $network")
            currentNetwork = null
            scope?.launch { dropConnections?.invoke() }
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

        loopJob = scope.launch {
            Log.i(TAG, "Watchdog started")
            while (isActive) {
                val interval = if (SystemClock.elapsedRealtime() - lastNetworkChangeAt < FAST_WINDOW_MS) {
                    FAST_INTERVAL_MS
                } else {
                    SLOW_INTERVAL_MS
                }
                val reason = withTimeoutOrNull(interval) { wakeUp.receive() } ?: "periodic"
                heartbeat?.invoke()
                retryPairedDevices(reason)
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
        Log.i(TAG, "Watchdog stopped")
    }

    /** Asks for an immediate retry pass (e.g. screen on, Wi-Fi toggled). */
    fun retryNow(reason: String) {
        wakeUp.trySend(reason)
    }

    private fun onNetworkChanged(reason: String, dropExisting: Boolean) {
        lastNetworkChangeAt = SystemClock.elapsedRealtime()
        scope?.launch {
            if (dropExisting) dropConnections?.invoke()
            networkDiscovery.restartDiscovery(reason)
            retryNow(reason)
        }
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

        val connect = connect ?: return
        candidates.forEach { device ->
            if (!inFlight.add(device.deviceId)) return@forEach
            Log.d(TAG, "[$reason] retrying ${device.deviceName} (${device.getAddressesToTry()})")
            try {
                connect(device)
                val state = deviceManager.getPairedDevice(device.deviceId)?.connectionState
                Log.d(TAG, "[$reason] ${device.deviceName} -> $state")
            } catch (e: Exception) {
                Log.e(TAG, "[$reason] retry of ${device.deviceName} failed", e)
            } finally {
                inFlight.remove(device.deviceId)
            }
        }
    }

    /** True unless the only network is cellular (a LAN device can't be reached there). */
    private fun hasLanCapableNetwork(): Boolean {
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
