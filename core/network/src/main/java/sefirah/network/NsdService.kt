package sefirah.network

import android.content.Context
import android.content.Context.WIFI_SERVICE
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ext.SdkExtensions
import android.util.Log
import androidx.annotation.RequiresExtension
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import sefirah.domain.model.UdpBroadcast
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NsdService @Inject constructor(val context: Context) {
    private val nsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    private val _services = MutableStateFlow<List<NsdServiceInfo>>(emptyList())
    val services: StateFlow<List<NsdServiceInfo>> = _services.asStateFlow()

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var currentServiceID: String? = null

    private var multicastLock: WifiManager.MulticastLock
    private val serviceCallbacks = ConcurrentHashMap<String, NsdManager.ServiceInfoCallback>()
    private val executor = Executors.newSingleThreadExecutor()

    init {
        try {
            val wifiManager = context.applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            multicastLock = wifiManager.createMulticastLock("SefirahMulticastLock")
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring multicast lock", e)
            throw e
        }
    }

    fun startDiscovery() {
        try {
            if (discoveryListener != null) return

            Log.d(TAG, "Starting mDns discovery")
            multicastLock.acquire()
            _services.value = emptyList()
            discoveryListener = createDiscoveryListener()
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "Error in startDiscovery: ${e.message}", e)
        }
    }

    fun stopDiscovery() {
        val listener = discoveryListener
        discoveryListener = null
        if (listener != null) runCatching { nsdManager.stopServiceDiscovery(listener) }
        if (Build.VERSION.SDK_INT >= 34) {
            serviceCallbacks.values.toList().forEach { callback ->
                runCatching { nsdManager.unregisterServiceInfoCallback(callback) }
            }
            serviceCallbacks.clear()
        }
        if (multicastLock.isHeld) multicastLock.release()
        _services.value = emptyList()
    }

    fun advertiseService(broadcast: UdpBroadcast, port: Int) {
        try {            
            if (registrationListener != null) {
                stopAdvertisingService()
            }
            
            currentServiceID = broadcast.deviceId

            val serviceInfo = NsdServiceInfo().also {
                it.serviceType = SERVICE_TYPE
                it.serviceName = currentServiceID
                it.port = port
            }
            serviceInfo.setAttribute("deviceName", broadcast.deviceName)
            serviceInfo.setAttribute("serverPort", port.toString())

            registrationListener = createRegistrationListener()
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Log.e(TAG, "Error advertising service: ${e.message}")
        }
    }

    fun stopAdvertisingService() {
        try {
            if (registrationListener != null)
                nsdManager.unregisterService(registrationListener)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Error stopping service advertisement: ${e.message}")
        }
        registrationListener = null
    }

    private fun createRegistrationListener() : NsdManager.RegistrationListener {
        return object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                Log.e(TAG, "Service registration failed: Error code: $errorCode")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                Log.e(TAG, "Service unregistration failed: Error code: $errorCode")
            }

            override fun onServiceRegistered(serviceInfo: NsdServiceInfo?) {
                Log.d(TAG, "Service registered successfully: $serviceInfo")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo?) {
                Log.d(TAG, "Service unregistered successfully: $serviceInfo")
            }
        }
    }

    private fun createDiscoveryListener() : NsdManager.DiscoveryListener{
        return object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.i(TAG, "Service discovery started")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                // Ignore own service
                if (service.serviceName == currentServiceID) return

                Log.d(TAG, "Service found: $service")
                if (service.serviceType == SERVICE_TYPE) {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                            && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.TIRAMISU) >= 7) {
                            val callback = createServiceInfoCallback(service.serviceName)
                            if (serviceCallbacks.putIfAbsent(service.serviceName, callback) == null) {
                                try {
                                    nsdManager.registerServiceInfoCallback(service, executor, callback)
                                } catch (e: Exception) {
                                    serviceCallbacks.remove(service.serviceName, callback)
                                    throw e
                                }
                            }
                        } else {
                            val resolveListener = createResolveListener()
                            nsdManager.resolveService(service, resolveListener)
                        }
                    } catch (e: IllegalArgumentException) {
                        Log.e(TAG, "Listener already in use or issue in registration: ${e.message}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error registering listener: ${e.message}")
                    }
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.e(TAG, "Service lost: $service")
                _services.update { entries -> entries.filter { it.serviceName != service.serviceName } }
                if (Build.VERSION.SDK_INT >= 34) serviceCallbacks.remove(service.serviceName)?.let {
                    runCatching { nsdManager.unregisterServiceInfoCallback(it) }
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "Discovery stopped: $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery failed to start: Error code: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery failed to stop: Error code: $errorCode")
            }
        }
    }

    /**
     * Returns a new listener instance since NsdManager wants a different listener each time you call resolveService
     */
    private fun createResolveListener(): NsdManager.ResolveListener {
        return object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "MDNS Resolve failed: Error code: $errorCode")
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "MDNS Resolved successfully $serviceInfo")
                if (discoveryListener != null) publish(serviceInfo)
            }
        }
    }

    private fun publish(serviceInfo: NsdServiceInfo) {
        _services.update { entries -> entries.filter { it.serviceName != serviceInfo.serviceName } + serviceInfo }
    }

    @RequiresExtension(extension = Build.VERSION_CODES.TIRAMISU, version = 7)
    private fun createServiceInfoCallback(name: String): NsdManager.ServiceInfoCallback =
        object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                serviceCallbacks.remove(name, this)
                Log.w(TAG, "Service callback registration failed for $name: $errorCode")
            }

            override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                if (serviceCallbacks[name] === this && discoveryListener != null) publish(serviceInfo)
            }

            override fun onServiceLost() {
                if (serviceCallbacks[name] === this)
                    _services.update { entries -> entries.filter { it.serviceName != name } }
            }

            override fun onServiceInfoCallbackUnregistered() {
                serviceCallbacks.remove(name, this)
            }
        }

    companion object {
        private const val TAG = "NsdService"
        private const val SERVICE_TYPE = "_sefirah._udp."
    }
}

