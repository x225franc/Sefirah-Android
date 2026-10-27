package com.castle.sefirah.presentation.main

import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.komu.sekia.di.AppCoroutineScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sefirah.data.repository.AppUpdateChecker
import sefirah.data.repository.ReleaseRepository
import sefirah.domain.model.PairedDevice
import sefirah.domain.model.ConnectionState
import sefirah.domain.model.UpdateInfo
import sefirah.domain.interfaces.DeviceManager
import sefirah.domain.interfaces.NetworkManager
import javax.inject.Inject

@HiltViewModel
class ConnectionViewModel @Inject constructor(
    private val networkManager: NetworkManager,
    private val deviceManager: DeviceManager,
    private val appScope: AppCoroutineScope,
    private val appUpdateChecker: AppUpdateChecker
) : ViewModel() {

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    val pairedDevices: StateFlow<List<PairedDevice>> = deviceManager.pairedDevices

    private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)
    val updateInfo: StateFlow<UpdateInfo?> = _updateInfo.asStateFlow()

    private val _isCheckingForUpdate = MutableStateFlow(false)
    val isCheckingForUpdate: StateFlow<Boolean> = _isCheckingForUpdate.asStateFlow()

    val hasCheckedForUpdate = mutableStateOf(false)

    val selectedDevice: StateFlow<PairedDevice?> = combine(
        pairedDevices,
        deviceManager.selectedDeviceId
    ) { devices, selectedId ->
        devices.firstOrNull { it.deviceId == selectedId }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    
    private val connectionJobs = mutableMapOf<String, Job>()
    private val requestedConnections = mutableMapOf<String, Boolean>()

    fun toggleConnection(device: PairedDevice) {
        val current = pairedDevices.value.firstOrNull { it.deviceId == device.deviceId } ?: return
        val connectingOrConnected = requestedConnections[device.deviceId]
            ?: current.connectionState.isConnectedOrConnecting
        requestConnection(current, connect = !connectingOrConnected)
    }

    fun toggleSync(syncRequest: Boolean) {
        val device = pairedDevices.value.firstOrNull {
            it.deviceId == deviceManager.selectedDeviceId.value
        } ?: return
        // Pull-to-refresh deliberately replaces the selected connection; card buttons only toggle.
        if (syncRequest && (connectionJobs[device.deviceId]?.isActive == true || device.connectionState.isConnecting)) return
        requestConnection(device, connect = syncRequest, refresh = syncRequest)
    }

    private fun requestConnection(device: PairedDevice, connect: Boolean, refresh: Boolean = false) {
        val deviceId = device.deviceId
        val previous = connectionJobs.remove(deviceId)
        previous?.cancel()
        requestedConnections[deviceId] = connect
        val job = appScope.launch(Dispatchers.Main.immediate, start = CoroutineStart.LAZY) {
            try {
                // Finish cancellation before starting the opposite operation on the same device.
                previous?.join()
                if (refresh) _isRefreshing.value = true
                withContext(Dispatchers.IO) {
                    val current = deviceManager.getPairedDevice(deviceId) ?: return@withContext
                    if (connect) {
                        if (refresh && current.connectionState.isConnected) {
                            networkManager.disconnect(deviceId)
                        }
                        networkManager.connectPaired(current, userInitiated = true)
                    } else {
                        networkManager.disconnect(deviceId)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("ConnectionViewModel", "Connection command failed for $deviceId", e)
                deviceManager.getPairedDevice(deviceId)?.let { current ->
                    deviceManager.addOrUpdatePairedDevice(
                        current.copy(connectionState = ConnectionState.Error(e.message ?: "Connection failed"))
                    )
                }
            } finally {
                if (connectionJobs[deviceId] === currentCoroutineContext()[Job]) {
                    connectionJobs.remove(deviceId)
                    requestedConnections.remove(deviceId)
                }
                if (refresh) _isRefreshing.value = false
            }
        }
        connectionJobs[deviceId] = job
        job.start()
    }

    fun selectDevice(device: PairedDevice) {
        deviceManager.selectDevice(device.deviceId)
    }

    suspend fun checkForUpdate(force: Boolean = false): ReleaseRepository.Result {
        _isCheckingForUpdate.value = true
        return try {
            val result = appUpdateChecker.checkForUpdate(force)
            when (result) {
                is ReleaseRepository.Result.NewUpdate -> {
                    _updateInfo.value = result.updateInfo
                }
                is ReleaseRepository.Result.NoNewUpdate -> {
                    result.updateInfo?.let { _updateInfo.value = it }
                }
                ReleaseRepository.Result.Error -> Unit
            }
            result
        } finally {
            _isCheckingForUpdate.value = false
        }
    }
}
