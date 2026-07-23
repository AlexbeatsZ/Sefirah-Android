package sefirah.communication.bluetooth

import org.json.JSONObject
import sefirah.domain.interfaces.NetworkManager
import sefirah.domain.model.BluetoothCatalogDevice
import sefirah.domain.model.BluetoothDeviceCatalog
import sefirah.domain.model.BluetoothDeviceCatalogRequest
import sefirah.domain.model.BluetoothHandoffCommand
import sefirah.domain.model.BluetoothHandoffResult
import sefirah.privileged.PrivilegedBridgeManager
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BluetoothHandoffHandler @Inject constructor(
    private val networkManager: NetworkManager,
    private val privilegedBridgeManager: PrivilegedBridgeManager,
) {
    suspend fun handleCatalogRequest(sourceDeviceId: String, request: BluetoothDeviceCatalogRequest) {
        privilegedBridgeManager.ensureReady()
        val rawCatalog = privilegedBridgeManager.getBluetoothCatalog()
        val parsedCatalog = rawCatalog?.let { raw ->
            runCatching { parseCatalog(request.requestId, JSONObject(raw)) }.getOrNull()
        }
        val response = parsedCatalog ?: BluetoothDeviceCatalog(
            requestId = request.requestId,
            controllerAvailable = false,
            radioEnabled = false,
            supportsPerDeviceControl = false,
            errorCode = "privileged_bridge_unavailable",
            errorMessage = "Shizuku permission and service are required",
        )
        networkManager.sendMessage(sourceDeviceId, response)
    }

    suspend fun handleCommand(sourceDeviceId: String, command: BluetoothHandoffCommand) {
        privilegedBridgeManager.ensureReady()
        val response = privilegedBridgeManager.executeBluetoothCommand(
            action = command.action,
            deviceKey = command.deviceKey,
            enabled = command.enabled ?: false,
        )?.let { raw ->
            runCatching { parseResult(command, JSONObject(raw)) }.getOrNull()
        } ?: BluetoothHandoffResult(
            operationId = command.operationId,
            action = command.action,
            success = false,
            errorCode = "privileged_bridge_unavailable",
            errorMessage = "Shizuku permission and service are required",
        )
        networkManager.sendMessage(sourceDeviceId, response)
    }

    private fun parseCatalog(requestId: String, json: JSONObject): BluetoothDeviceCatalog {
        val devicesJson = json.optJSONArray("devices")
        val devices = buildList {
            if (devicesJson != null) {
                for (index in 0 until devicesJson.length()) {
                    val item = devicesJson.getJSONObject(index)
                    add(
                        BluetoothCatalogDevice(
                            deviceKey = item.getString("deviceKey"),
                            displayName = item.getString("displayName"),
                            isConnected = item.optBoolean("isConnected"),
                            bluetoothAddress = item.optNullableString("bluetoothAddress"),
                            isHeadset = item.optBoolean("isHeadset"),
                        ),
                    )
                }
            }
        }
        return BluetoothDeviceCatalog(
            requestId = requestId,
            controllerAvailable = json.optBoolean("controllerAvailable"),
            radioEnabled = json.optBoolean("radioEnabled"),
            supportsPerDeviceControl = json.optBoolean("supportsPerDeviceControl"),
            devices = devices,
            errorCode = json.optNullableString("errorCode"),
            errorMessage = json.optNullableString("errorMessage"),
        )
    }

    private fun parseResult(command: BluetoothHandoffCommand, json: JSONObject) = BluetoothHandoffResult(
        operationId = command.operationId,
        action = command.action,
        success = json.optBoolean("success"),
        radioEnabled = json.optNullableBoolean("radioEnabled"),
        deviceConnected = json.optNullableBoolean("deviceConnected"),
        errorCode = json.optNullableString("errorCode"),
        errorMessage = json.optNullableString("errorMessage"),
    )

    private fun JSONObject.optNullableString(key: String): String? =
        if (has(key) && !isNull(key)) getString(key) else null

    private fun JSONObject.optNullableBoolean(key: String): Boolean? =
        if (has(key) && !isNull(key)) getBoolean(key) else null
}
