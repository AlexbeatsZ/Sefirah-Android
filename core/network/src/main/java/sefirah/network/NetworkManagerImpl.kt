package sefirah.network

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.util.Log
import sefirah.domain.interfaces.NetworkManager
import sefirah.domain.model.ClipboardInfo
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
    private val serviceBindingGate = ServiceBindingGate()
    @Volatile private var networkService: NetworkService? = null
    @Volatile private var serviceWanted = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (!serviceWanted) return
            networkService = (service as NetworkService.LocalBinder).getService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            networkService = null
        }

        override fun onBindingDied(name: ComponentName?) {
            networkService = null
            releaseBindingRegistration()
            if (serviceWanted) startService()
        }

        override fun onNullBinding(name: ComponentName?) {
            networkService = null
            releaseBindingRegistration()
        }
    }

    override fun startService() {
        serviceWanted = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
        serviceBindingGate.bindOnce {
            context.bindService(
                serviceIntent,
                serviceConnection,
                Context.BIND_AUTO_CREATE,
            ).also { didBind ->
                if (!didBind) {
                    Log.w(TAG, "Service binding was rejected")
                }
            }
        }
    }

    override fun stopService() {
        serviceWanted = false
        networkService = null
        releaseBindingRegistration()
        context.stopService(serviceIntent)
    }

    private fun releaseBindingRegistration() {
        serviceBindingGate.unbindOnce {
            try {
                context.unbindService(serviceConnection)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Service not bound when unbinding", e)
            }
        }
    }

    override suspend fun connectPaired(device: PairedDevice) {
        networkService?.connectPaired(device)
    }

    override suspend fun connectTo(connectionDetails: ConnectionDetails) {
        networkService?.connectTo(connectionDetails)
    }

    override suspend fun disconnect(deviceId: String) {
        networkService?.disconnect(deviceId)
    }

    override fun broadcastMessage(message: SocketMessage) {
        networkService?.broadcastMessage(message)
    }

    override fun sendMessage(deviceId: String, message: SocketMessage) {
        networkService?.sendMessage(deviceId, message)
    }

    override suspend fun sendMessageAwait(deviceId: String, message: SocketMessage): Boolean {
        return networkService?.sendMessageAwait(deviceId, message) ?: false
    }

    override fun sendClipboardMessage(message: ClipboardInfo) {
        networkService?.sendClipboardMessage(message)
    }

    override suspend fun approveDeviceConnection(deviceId: String) {
        networkService?.approveDeviceConnection(deviceId)
    }

    override suspend fun rejectDeviceConnection(deviceId: String) {
        networkService?.rejectDeviceConnection(deviceId)
    }

    companion object {
        private const val TAG = "NetworkManager"
    }
}
