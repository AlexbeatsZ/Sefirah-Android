package sefirah.privileged

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.Context
import android.os.IBinder
import android.os.Process
import androidx.annotation.Keep
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

@Keep
class PrivilegedBridgeService() : IPrivilegedBridge.Stub() {
    @Keep
    constructor(context: Context) : this() {
        BluetoothShellController.initialize(context)
    }

    override fun destroy() {
        System.exit(0)
    }

    override fun readClipboardText(): String? = PrivilegedClipboardReader.readText()

    override fun getBluetoothCatalog(): String = BluetoothShellController.getCatalog()

    override fun executeBluetoothCommand(action: String, deviceKey: String?, enabled: Boolean): String =
        BluetoothShellController.execute(action, deviceKey, enabled)
}

private object PrivilegedClipboardReader {
    private val clipboardService: Any? by lazy {
        runCatching {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val binder = serviceManager
                .getMethod("getService", String::class.java)
                .invoke(null, Context.CLIPBOARD_SERVICE) as IBinder
            val stub = Class.forName("android.content.IClipboard\$Stub")
            stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        }.getOrNull()
    }

    fun readText(): String? {
        val service = clipboardService ?: return null
        val methods = service.javaClass.methods
            .filter { it.name == "getPrimaryClip" }
            .sortedByDescending { it.parameterCount }

        for (method in methods) {
            val result = runCatching {
                method.isAccessible = true
                method.invoke(service, *buildArguments(method.parameterTypes)) as? ClipData
            }.getOrNull() ?: continue

            if (result.itemCount == 0) return null
            return result.getItemAt(0).text?.toString()
        }
        return null
    }

    private fun buildArguments(types: Array<Class<*>>): Array<Any?> {
        var stringIndex = 0
        var intIndex = 0
        return Array(types.size) { index ->
            when (types[index]) {
                String::class.java -> if (stringIndex++ == 0) callingPackage() else null
                Int::class.javaPrimitiveType, Int::class.javaObjectType ->
                    if (intIndex++ == 0) Process.myUid() / PER_USER_RANGE else 0
                Boolean::class.javaPrimitiveType, Boolean::class.javaObjectType -> false
                else -> null
            }
        }
    }

    private fun callingPackage(): String =
        if (Process.myUid() == SHELL_UID) "com.android.shell" else "com.castle.sefirah"

    private const val SHELL_UID = 2000
    private const val PER_USER_RANGE = 100_000
}

private object BluetoothShellController {
    private const val ACTION_CONNECT = "connect"
    private const val ACTION_DISCONNECT = "disconnect"
    private const val ACTION_SET_RADIO = "setRadio"
    @Volatile
    private var serviceContext: Context? = null
    @Volatile
    private var adapterLookupError: String? = null
    @Volatile
    private var shellAdapter: BluetoothAdapter? = null

    fun initialize(context: Context) {
        serviceContext = context
    }

    @SuppressLint("MissingPermission")
    fun getCatalog(): String {
        val adapter = getAdapter()
            ?: return catalogError(
                "bluetooth_unavailable",
                adapterLookupError?.let { "Bluetooth adapter is unavailable: $it" }
                    ?: "Bluetooth adapter is unavailable",
            )

        return runCatching {
            val devices = JSONArray()
            adapter.bondedDevices
                .filter(::isAudioDevice)
                .sortedBy { it.name.orEmpty() }
                .forEach { device ->
                    devices.put(
                        JSONObject()
                            .put("deviceKey", device.address)
                            .put("displayName", device.name ?: device.address)
                            .put("isConnected", isConnected(device)),
                    )
                }

            JSONObject()
                .put("controllerAvailable", true)
                .put("radioEnabled", adapter.isEnabled)
                .put("supportsPerDeviceControl", supportsPerDeviceControl())
                .put("devices", devices)
                .toString()
        }.getOrElse { error ->
            catalogError("catalog_failed", error.message ?: error.javaClass.simpleName)
        }
    }

    @SuppressLint("MissingPermission")
    fun execute(action: String, deviceKey: String?, enabled: Boolean): String {
        val adapter = getAdapter()
            ?: return commandResult(
                action,
                false,
                errorCode = "bluetooth_unavailable",
                errorMessage = adapterLookupError,
            )

        return runCatching {
            when (action) {
                ACTION_SET_RADIO -> {
                    val success = setRadio(enabled)
                    commandResult(
                        action = action,
                        success = success,
                        radioEnabled = adapter.isEnabled,
                        errorCode = if (success) null else "radio_command_failed",
                    )
                }

                ACTION_CONNECT, ACTION_DISCONNECT -> {
                    val device = adapter.bondedDevices.firstOrNull {
                        it.address.equals(deviceKey, ignoreCase = true)
                    } ?: return commandResult(action, false, errorCode = "device_not_found")

                    val success = invokeDeviceAction(device, action)
                    commandResult(
                        action = action,
                        success = success,
                        radioEnabled = adapter.isEnabled,
                        deviceConnected = isConnected(device),
                        errorCode = if (success) null else "per_device_control_unsupported",
                    )
                }

                else -> commandResult(action, false, errorCode = "unknown_action")
            }
        }.getOrElse { error ->
            commandResult(
                action = action,
                success = false,
                radioEnabled = adapter.isEnabled,
                errorCode = "command_failed",
                errorMessage = error.message ?: error.javaClass.simpleName,
            )
        }
    }

    private fun setRadio(enabled: Boolean): Boolean {
        val operation = if (enabled) "enable" else "disable"
        val process = ProcessBuilder("/system/bin/cmd", "bluetooth_manager", operation)
            .redirectErrorStream(true)
            .start()
        if (!process.waitFor(8, TimeUnit.SECONDS)) {
            process.destroy()
            return false
        }
        return process.exitValue() == 0
    }

    private fun getAdapter(): BluetoothAdapter? {
        serviceContext?.getSystemService(BluetoothManager::class.java)?.adapter?.let { return it }
        shellAdapter?.let { return it }

        return synchronized(this) {
            shellAdapter ?: createShellAdapter()?.also { adapter ->
                shellAdapter = adapter
                adapterLookupError = null
            } ?: BluetoothAdapter.getDefaultAdapter()
        }
    }

    private fun createShellAdapter(): BluetoothAdapter? = runCatching {
        val frameworkInitializer = Class.forName("android.bluetooth.BluetoothFrameworkInitializer")
        val getServiceManager = frameworkInitializer
            .getDeclaredMethod("getBluetoothServiceManager")
            .apply { isAccessible = true }
        if (getServiceManager.invoke(null) == null) {
            val bluetoothServiceManagerClass = Class.forName("android.os.BluetoothServiceManager")
            val bluetoothServiceManager = bluetoothServiceManagerClass
                .getDeclaredConstructor()
                .apply { isAccessible = true }
                .newInstance()
            frameworkInitializer
                .getDeclaredMethod("setBluetoothServiceManager", bluetoothServiceManagerClass)
                .apply { isAccessible = true }
                .invoke(null, bluetoothServiceManager)
        }

        val serviceManager = Class.forName("android.os.ServiceManager")
        val managerBinder = serviceManager
            .getMethod("getService", String::class.java)
            .invoke(null, "bluetooth_manager") as? IBinder
            ?: error("bluetooth_manager binder is unavailable")
        val managerStub = Class.forName("android.bluetooth.IBluetoothManager\$Stub")
        val managerInterface = managerStub
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, managerBinder)

        val attributionSourceClass = Class.forName("android.content.AttributionSource")
        val builderClass = Class.forName("android.content.AttributionSource\$Builder")
        val builder = builderClass.getConstructor(Int::class.javaPrimitiveType).newInstance(Process.myUid())
        builderClass.getMethod("setPackageName", String::class.java).invoke(builder, "com.android.shell")
        val attributionSource = builderClass.getMethod("build").invoke(builder)

        BluetoothAdapter::class.java.declaredConstructors
            .first { constructor ->
                constructor.parameterTypes.size == 2 &&
                    constructor.parameterTypes[0].name == "android.bluetooth.IBluetoothManager" &&
                    constructor.parameterTypes[1] == attributionSourceClass
            }
            .apply { isAccessible = true }
            .newInstance(managerInterface, attributionSource) as BluetoothAdapter
    }.onFailure { error ->
        val root = error.cause ?: error
        adapterLookupError = "${root.javaClass.simpleName}: ${root.message}"
    }.getOrNull()

    private fun supportsPerDeviceControl(): Boolean =
        BluetoothDevice::class.java.methods.any { it.name == ACTION_CONNECT && it.parameterCount == 0 } &&
            BluetoothDevice::class.java.methods.any { it.name == ACTION_DISCONNECT && it.parameterCount == 0 }

    private fun invokeDeviceAction(device: BluetoothDevice, action: String): Boolean {
        val method = device.javaClass.methods.firstOrNull {
            it.name == action && it.parameterCount == 0
        } ?: return false
        method.isAccessible = true
        return method.invoke(device) as? Boolean ?: true
    }

    private fun isConnected(device: BluetoothDevice): Boolean = runCatching {
        val method = device.javaClass.methods.first {
            it.name == "isConnected" && it.parameterCount == 0
        }
        method.isAccessible = true
        method.invoke(device) as Boolean
    }.getOrDefault(false)

    private fun isAudioDevice(device: BluetoothDevice): Boolean {
        val majorClass = device.bluetoothClass?.majorDeviceClass
        return majorClass == BluetoothClass.Device.Major.AUDIO_VIDEO
    }

    private fun catalogError(code: String, message: String): String = JSONObject()
        .put("controllerAvailable", false)
        .put("radioEnabled", false)
        .put("supportsPerDeviceControl", false)
        .put("devices", JSONArray())
        .put("errorCode", code)
        .put("errorMessage", message)
        .toString()

    private fun commandResult(
        action: String,
        success: Boolean,
        radioEnabled: Boolean? = null,
        deviceConnected: Boolean? = null,
        errorCode: String? = null,
        errorMessage: String? = null,
    ): String = JSONObject()
        .put("action", action)
        .put("success", success)
        .apply {
            radioEnabled?.let { put("radioEnabled", it) }
            deviceConnected?.let { put("deviceConnected", it) }
            errorCode?.let { put("errorCode", it) }
            errorMessage?.let { put("errorMessage", it) }
        }
        .toString()
}
