package sefirah.domain.interfaces

import sefirah.domain.model.ConnectionDetails
import sefirah.domain.model.PairedDevice
import sefirah.domain.model.SocketMessage

interface NetworkManager {
    fun startService()
    fun stopService()
    /**
     * [automatic] attempts (discovery, reconnect) never resume a manual disconnect on either end.
     * A user-requested connect (the default) clears the local one and asks the peer to resume too.
     */
    suspend fun connectPaired(device: PairedDevice, automatic: Boolean = false)
    suspend fun connectTo(connectionDetails: ConnectionDetails)
    /** Manual disconnect: the peer is told, and neither end reconnects automatically. */
    suspend fun disconnect(deviceId: String)
    /** Drop and re-establish a live link; unlike [disconnect] it is not a manual disconnect. */
    suspend fun reconnect(deviceId: String)
    fun broadcastMessage(message: SocketMessage)
    /** @return false if there is no live connection */
    fun sendMessage(deviceId: String, message: SocketMessage): Boolean
    suspend fun approveDeviceConnection(deviceId: String)
    suspend fun pairDevice(deviceId: String, verificationCode: String, approve: Boolean)
    suspend fun rejectDeviceConnection(deviceId: String)
}
