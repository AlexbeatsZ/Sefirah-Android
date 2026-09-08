package sefirah

import sefirah.domain.interfaces.DeviceManager
import sefirah.domain.model.DevicePreferences
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Base type for a singleton feature that can be active for zero or more connected devices.
 */
abstract class Feature(
    protected val deviceManager: DeviceManager,
) {

    /** Device IDs for which this feature is currently active. */
    protected val enabledDevices = CopyOnWriteArraySet<String>()
    protected val lifecycleMutex = Mutex()

    /** Stable snapshot for asynchronous sends; never expose the live collection. */
    val activeDeviceIds: Set<String>
        get() = enabledDevices.toTypedArray().toSet()

    /** Whether this feature is turned on in [prefs] for the device. */
    abstract fun isPrefEnabled(prefs: DevicePreferences): Boolean

    /** Runtime permission / capability check (e.g. notification listener). */
    open fun hasPermissions(): Boolean = true

    /**
     * Called when [deviceId] becomes active for this feature
     * (connected + eligible, or pref flipped on while connected).
     */
    protected open suspend fun onStart(deviceId: String) {}

    /**
     * Called when [deviceId] becomes inactive
     * (disconnect, or pref flipped off while connected).
     */
    protected open suspend fun onStop(deviceId: String) {}

    /** Mark [deviceId] active and run start hooks. Idempotent if already enabled. */
    open suspend fun enable(deviceId: String) = lifecycleMutex.withLock {
        if (deviceId in enabledDevices) return@withLock
        enabledDevices.add(deviceId)
        onStart(deviceId)
    }

    /** Mark [deviceId] inactive and run stop hooks. Idempotent if already disabled. */
    open suspend fun disable(deviceId: String) = lifecycleMutex.withLock {
        if (deviceId !in enabledDevices) return@withLock
        enabledDevices.remove(deviceId)
        onStop(deviceId)
    }
}
