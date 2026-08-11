package sefirah.privileged

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.os.IBinder
import android.os.Process
import androidx.annotation.Keep
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeoutException
import java.util.concurrent.TimeUnit

@Keep
class PrivilegedBridgeService() : IPrivilegedBridge.Stub() {
    private val privilegedSftpServer = PrivilegedSftpServer()

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

    override fun startSftpServer(username: String, password: String, rootPath: String): Int =
        privilegedSftpServer.start(username, password, rootPath)

    override fun stopSftpServer() {
        privilegedSftpServer.stop()
    }
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
            return ClipboardTextPolicy.acceptedText(
                text = result.getItemAt(0).text,
                isSensitive = result.description.extras
                    ?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) == true,
            )
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
    @Volatile
    private var perDeviceControlHealthy = true
    private val deviceActionExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Sefirah-BluetoothAction").apply { isDaemon = true }
    }

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
                .sortedBy { it.name.orEmpty() }
                .forEach { device ->
                    devices.put(
                        JSONObject()
                            .put("deviceKey", device.address)
                            .put("displayName", device.name ?: device.address)
                            .put("isConnected", isConnected(device))
                            .put("bluetoothAddress", device.address)
                            .put("isHeadset", isHeadset(device)),
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

                    val result = executeDeviceAction(device, action)
                    commandResult(
                        action = action,
                        success = result.success,
                        radioEnabled = adapter.isEnabled,
                        deviceConnected = result.deviceConnected,
                        errorCode = result.errorCode,
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
        repeat(RADIO_COMMAND_ATTEMPTS) {
            val process = ProcessBuilder("/system/bin/cmd", "bluetooth_manager", operation)
                .redirectErrorStream(true)
                .start()
            if (!process.waitFor(8, TimeUnit.SECONDS)) {
                process.destroy()
            } else if (process.exitValue() == 0 && waitForRadioState(enabled)) {
                return true
            }
        }
        return getAdapter()?.isEnabled == enabled
    }

    private fun waitForRadioState(enabled: Boolean): Boolean {
        val deadline = System.currentTimeMillis() + RADIO_STATE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (getAdapter()?.isEnabled == enabled) return true
            Thread.sleep(RADIO_STATE_POLL_INTERVAL_MS)
        }
        return false
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
        perDeviceControlHealthy &&
            BluetoothDevice::class.java.methods.any { it.name == ACTION_CONNECT && it.parameterCount == 0 } &&
            BluetoothDevice::class.java.methods.any { it.name == ACTION_DISCONNECT && it.parameterCount == 0 }

    private fun executeDeviceAction(device: BluetoothDevice, action: String): DeviceActionResult {
        if (!supportsPerDeviceControl()) {
            return DeviceActionResult(
                success = false,
                deviceConnected = null,
                errorCode = "per_device_control_unsupported",
            )
        }

        val expectedConnected = action == ACTION_CONNECT
        val future = deviceActionExecutor.submit<DeviceActionResult> {
            if (isConnected(device) == expectedConnected) {
                return@submit DeviceActionResult(
                    success = true,
                    deviceConnected = expectedConnected,
                )
            }
            if (!invokeDeviceAction(device, action)) {
                return@submit DeviceActionResult(
                    success = false,
                    deviceConnected = isConnected(device),
                    errorCode = "per_device_control_rejected",
                )
            }

            val reachedTargetState = waitForDeviceState(device, expectedConnected)
            DeviceActionResult(
                success = reachedTargetState,
                deviceConnected = isConnected(device),
                errorCode = if (reachedTargetState) null else "device_state_timeout",
            )
        }

        return try {
            future.get(DEVICE_ACTION_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            perDeviceControlHealthy = false
            DeviceActionResult(
                success = false,
                deviceConnected = null,
                errorCode = "per_device_control_timeout",
            )
        }
    }

    private fun waitForDeviceState(device: BluetoothDevice, connected: Boolean): Boolean {
        val deadline = System.currentTimeMillis() + DEVICE_STATE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (isConnected(device) == connected) return true
            Thread.sleep(DEVICE_STATE_POLL_INTERVAL_MS)
        }
        return false
    }

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

    private fun isHeadset(device: BluetoothDevice): Boolean =
        device.bluetoothClass?.deviceClass in setOf(
            BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE,
            BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES,
            BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET,
        )

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

    private data class DeviceActionResult(
        val success: Boolean,
        val deviceConnected: Boolean?,
        val errorCode: String? = null,
    )

    private const val RADIO_COMMAND_ATTEMPTS = 3
    private const val RADIO_STATE_TIMEOUT_MS = 2_000L
    private const val RADIO_STATE_POLL_INTERVAL_MS = 250L
    private const val DEVICE_STATE_TIMEOUT_MS = 6_000L
    private const val DEVICE_ACTION_CALL_TIMEOUT_MS = 8_000L
    private const val DEVICE_STATE_POLL_INTERVAL_MS = 250L
}
