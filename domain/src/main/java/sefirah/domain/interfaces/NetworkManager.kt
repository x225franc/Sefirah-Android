package sefirah.domain.interfaces

import sefirah.domain.model.ConnectionDetails
import sefirah.domain.model.PairedDevice
import sefirah.domain.model.SocketMessage

interface NetworkManager {
    fun startService()
    fun stopService()
    suspend fun connectPaired(device: PairedDevice, userInitiated: Boolean = false)
    suspend fun connectTo(connectionDetails: ConnectionDetails)
    suspend fun disconnect(deviceId: String)
    fun broadcastMessage(message: SocketMessage)
    fun sendMessage(deviceId: String, message: SocketMessage)
    /** Waits for the existing transport to flush the frame; does not open another connection. */
    suspend fun sendMessageAndFlush(deviceId: String, message: SocketMessage): Boolean
    suspend fun approveDeviceConnection(deviceId: String)
    suspend fun rejectDeviceConnection(deviceId: String)
}
