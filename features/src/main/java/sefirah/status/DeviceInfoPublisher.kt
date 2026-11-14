package sefirah.status

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import dagger.Lazy
import sefirah.communication.utils.TelephonyHelper
import sefirah.domain.interfaces.DeviceManager
import sefirah.domain.interfaces.NetworkManager
import sefirah.domain.model.DeviceInfo
import sefirah.domain.model.PairedDevice
import javax.inject.Inject
import javax.inject.Singleton

/** Composes connection, control and wallpaper announcements from the same local state. */
@Singleton
class DeviceInfoPublisher @Inject constructor(
    private val context: Context,
    private val devices: DeviceManager,
    private val network: NetworkManager,
    private val controls: Lazy<DeviceControlHandler>,
    private val presentation: DevicePresentationPublisher,
) {
    @SuppressLint("MissingPermission")
    suspend fun publish(peer: PairedDevice, includePhoneNumbers: Boolean = false, requestedWallpaperId: String? = null) {
        val metadata = presentation.metadata(peer.deviceId, requestedWallpaperId != null)
        if (requestedWallpaperId != null && metadata.wallpaperId != requestedWallpaperId) return
        val numbers = if (includePhoneNumbers) {
            try { TelephonyHelper.getAllPhoneNumbers(context).map { it.toDto() } }
            catch (e: Exception) {
                Log.w("DeviceInfoPublisher", "Could not read phone numbers", e)
                emptyList()
            }
        } else null
        network.sendMessage(peer.deviceId, DeviceInfo(
            deviceName = devices.localDevice.deviceName,
            phoneNumbers = numbers,
            controls = controls.get().capabilities(peer.deviceId),
            presentation = metadata,
        ))
    }
}
