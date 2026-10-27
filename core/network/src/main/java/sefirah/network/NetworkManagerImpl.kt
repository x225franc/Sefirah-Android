package sefirah.network

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import sefirah.domain.interfaces.NetworkManager
import sefirah.domain.model.ConnectionDetails
import sefirah.domain.model.PairedDevice
import sefirah.domain.model.SocketMessage
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NetworkManagerImpl @Inject constructor(
    private val context: Context
) : NetworkManager {
    private val serviceIntent by lazy { Intent(context, NetworkService::class.java) }
    private val bindingLock = Any()
    @Volatile private var networkService: NetworkService? = null
    private var binding: ServiceBinding? = null

    private inner class ServiceBinding : ServiceConnection {
        var ready = CompletableDeferred<NetworkService>()

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            synchronized(bindingLock) {
                if (binding !== this) return
                val connectedService = (service as? NetworkService.LocalBinder)?.getService()
                if (connectedService == null) {
                    ready.completeExceptionally(IllegalStateException("Invalid network service binder"))
                    return
                }
                networkService = connectedService
                ready.complete(connectedService)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            synchronized(bindingLock) {
                if (binding !== this) return
                networkService = null
                // Android keeps this binding and reconnects it after a service process death.
                if (ready.isCompleted) ready = CompletableDeferred()
            }
        }

        override fun onBindingDied(name: ComponentName?) {
            synchronized(bindingLock) {
                if (binding !== this) return
                releaseBindingLocked()
                try {
                    startService()
                } catch (e: Exception) {
                    Log.e(TAG, "Could not rebind network service", e)
                }
            }
        }

        override fun onNullBinding(name: ComponentName?) {
            synchronized(bindingLock) {
                if (binding !== this) return
                ready.completeExceptionally(IllegalStateException("Network service refused binding"))
                releaseBindingLocked()
            }
        }
    }

    override fun startService() {
        synchronized(bindingLock) {
            // Activity recreation and simultaneous commands must share a single binding.
            if (binding != null) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            val requestedBinding = ServiceBinding()
            binding = requestedBinding
            try {
                check(context.bindService(serviceIntent, requestedBinding, Context.BIND_AUTO_CREATE)) {
                    "Unable to bind network service"
                }
            } catch (e: Exception) {
                binding = null
                requestedBinding.ready.completeExceptionally(e)
                throw e
            }
        }
    }

    override fun stopService() {
        synchronized(bindingLock) {
            releaseBindingLocked()
            context.stopService(serviceIntent)
        }
    }

    private fun releaseBindingLocked() {
        val previous = binding
        binding = null
        networkService = null
        if (previous == null) return
        previous.ready.cancel()
        try {
            context.unbindService(previous)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Service not bound when unbinding", e)
        }
    }

    private suspend fun awaitService(): NetworkService {
        val pending = synchronized(bindingLock) {
            networkService?.let { return it }
            startService()
            checkNotNull(binding).ready
        }
        val service = withTimeoutOrNull(SERVICE_BIND_TIMEOUT_MS) { pending.await() }
        if (service != null) return service
        synchronized(bindingLock) {
            // A later explicit command may retry a binding that never completed.
            if (binding?.ready === pending && networkService == null) releaseBindingLocked()
        }
        throw IllegalStateException("Network service did not become available")
    }

    override suspend fun connectPaired(device: PairedDevice, userInitiated: Boolean) {
        awaitService().connectPaired(device, userInitiated)
    }

    override suspend fun connectTo(connectionDetails: ConnectionDetails) {
        awaitService().connectTo(connectionDetails)
    }

    override suspend fun disconnect(deviceId: String) {
        awaitService().disconnect(deviceId)
    }

    override fun broadcastMessage(message: SocketMessage) {
        networkService?.broadcastMessage(message)
    }

    override fun sendMessage(deviceId: String, message: SocketMessage) {
        networkService?.sendMessage(deviceId, message)
    }

    override suspend fun approveDeviceConnection(deviceId: String) {
        networkService?.approveDeviceConnection(deviceId)
    }

    override suspend fun rejectDeviceConnection(deviceId: String) {
        networkService?.rejectDeviceConnection(deviceId)
    }

    companion object {
        private const val TAG = "NetworkManager"
        private const val SERVICE_BIND_TIMEOUT_MS = 5_000L
    }
}
