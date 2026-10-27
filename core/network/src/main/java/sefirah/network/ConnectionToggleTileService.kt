package sefirah.network

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.annotation.RequiresApi
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sefirah.common.R
import sefirah.domain.interfaces.DeviceManager
import sefirah.domain.interfaces.NetworkManager
import sefirah.domain.model.ConnectionState
import sefirah.domain.model.PairedDevice
import javax.inject.Inject

@RequiresApi(Build.VERSION_CODES.N)
@AndroidEntryPoint
class ConnectionToggleTileService : TileService() {
    @Inject lateinit var networkManager: NetworkManager
    @Inject lateinit var deviceManager: DeviceManager
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var connectionStateJob: kotlinx.coroutines.Job? = null
    private var commandJob: kotlinx.coroutines.Job? = null
    private var connectionState: ConnectionState = ConnectionState.Disconnected()
    private var lastConnectedDevice: PairedDevice? = null
    
    override fun onTileAdded() {
        super.onTileAdded()
        updateTile()
    }
    
    override fun onStartListening() {
        super.onStartListening()
        // Start observing connection state for the last connected device
        connectionStateJob?.cancel()
        connectionStateJob = serviceScope.launch {
            deviceManager.pairedDevices.collectLatest { devices ->
                val device = devices.maxByOrNull { it.lastConnected ?: 0L }
                lastConnectedDevice = device
                connectionState = device?.connectionState ?: ConnectionState.Disconnected()
                updateTile()
            }
        }
    }

    override fun onStopListening() {
        super.onStopListening()
        connectionStateJob?.cancel()
        connectionStateJob = null
    }

    override fun onClick() {
        super.onClick()
        val device = lastConnectedDevice ?: return
        val disconnect = connectionState.isConnectedOrConnecting || commandJob?.isActive == true
        val previous = commandJob
        previous?.cancel()
        commandJob = serviceScope.launch {
            try {
                previous?.join()
                withContext(Dispatchers.IO) {
                    if (disconnect) {
                        networkManager.disconnect(device.deviceId)
                    } else {
                        networkManager.connectPaired(device, userInitiated = true)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Connection tile command failed", e)
            }
        }
    }

    override fun onTileRemoved() {
        super.onTileRemoved()
        connectionStateJob?.cancel()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
    
    private fun updateTile() {
        serviceScope.launch(Dispatchers.IO) {
            val tile = qsTile ?: return@launch

            val (label, tileState, icon, subtitle) = when {
                connectionState.isConnectedOrConnecting -> {
                    TileData(
                        label = lastConnectedDevice?.deviceName ?: "",
                        tileState = Tile.STATE_ACTIVE,
                        subtitle = getString(if (connectionState.isConnecting) R.string.status_connecting else R.string.status_connected),
                        icon = Icon.createWithResource(this@ConnectionToggleTileService, R.drawable.ic_sync),
                    )
                }

                connectionState.isDisconnected -> {
                    if (lastConnectedDevice != null) {
                        TileData(
                            label = lastConnectedDevice!!.deviceName,
                            tileState = Tile.STATE_INACTIVE,
                            subtitle = getString(R.string.status_disconnected),
                            icon = Icon.createWithResource(this@ConnectionToggleTileService, R.drawable.ic_sync_disabled),
                        )
                    } else {
                        TileData(
                            label = getString(R.string.no_device),
                            tileState = Tile.STATE_UNAVAILABLE,
                            icon = Icon.createWithResource(this@ConnectionToggleTileService, R.drawable.ic_sync_desktop),
                        )
                    }
                }

                connectionState.isError -> {
                    TileData(
                        label = getString(R.string.error),
                        tileState = Tile.STATE_UNAVAILABLE,
                        icon = Icon.createWithResource(this@ConnectionToggleTileService, R.drawable.ic_sync_problem),
                    )
                }
                else -> {
                    TileData(
                        label = "Unknown",
                        tileState = Tile.STATE_UNAVAILABLE,
                        icon = Icon.createWithResource(this@ConnectionToggleTileService, R.drawable.ic_sync_problem),
                    )
                }
            }

            withContext(Dispatchers.Main) {
                tile.label = label
                tile.icon = icon
                tile.state = tileState
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = subtitle
                }
                tile.updateTile()
            }
        }
    }

    private data class TileData(
        val label: String,
        val tileState: Int,
        val icon: Icon,
        val subtitle: String? = null,
    )

    companion object {
        private const val TAG = "ConnectionTileService"
    }
}
