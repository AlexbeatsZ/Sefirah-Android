package sefirah.network

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.util.Log
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
    private var networkService: NetworkService? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            networkService = (service as NetworkService.LocalBinder).getService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            networkService = null
        }
    }

    override fun startService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
        context.bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun stopService() {
        networkService = null
        try {
            context.unbindService(serviceConnection)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Service not bound when unbinding", e)
        }
        context.stopService(serviceIntent)
    }

    override suspend fun connectPaired(device: PairedDevice) {
        requireService().connectPaired(device)
    }

    override suspend fun connectTo(connectionDetails: ConnectionDetails) {
        requireService().connectTo(connectionDetails)
    }

    override suspend fun disconnect(deviceId: String) {
        requireService().disconnect(deviceId)
    }

    override fun broadcastMessage(message: SocketMessage) {
        networkService?.broadcastMessage(message)
    }

    override fun sendMessage(deviceId: String, message: SocketMessage): Boolean {
        return networkService?.sendMessage(deviceId, message) ?: false
    }

    override suspend fun approveDeviceConnection(deviceId: String) {
        requireService().approveDeviceConnection(deviceId)
    }

    override suspend fun pairDevice(deviceId: String, verificationCode: String, approve: Boolean) {
        checkNotNull(networkService) { "Network service is not bound; start it first" }
            .pairDevice(deviceId, verificationCode, approve)
    }

    override suspend fun rejectDeviceConnection(deviceId: String) {
        requireService().rejectDeviceConnection(deviceId)
    }

    private fun requireService() = checkNotNull(networkService) { "Network service is not bound; start it first" }

    companion object {
        private const val TAG = "NetworkManager"
    }
}
