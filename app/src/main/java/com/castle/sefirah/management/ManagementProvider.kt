package com.castle.sefirah.management

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import sefirah.domain.interfaces.DeviceManager
import sefirah.domain.interfaces.NetworkManager
import sefirah.domain.interfaces.PreferencesRepository
import sefirah.domain.model.ConnectionDetails
import sefirah.worker.WorkerManager

/** Versioned local API for ADB/root automation; never returns private keys or arbitrary file data. */
class ManagementProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val uid = Binder.getCallingUid()
        if (uid != 0 && uid != 2000) throw SecurityException("Management API requires shell or root")
        val entry = EntryPointAccessors.fromApplication(context!!.applicationContext, Dependencies::class.java)
        val identity = Binder.clearCallingIdentity()
        return try {
            val result = runBlocking(Dispatchers.IO) {
                withTimeout(15_000) {
                    when (method) {
                        "status" -> status(entry)
                        "start" -> {
                            entry.network().startService()
                            JSONObject().put("accepted", true)
                        }
                        "stop" -> {
                            entry.network().stopService()
                            JSONObject().put("accepted", true)
                        }
                        "connect" -> {
                            val request = JSONObject(requireNotNull(arg))
                            val id = request.getString("deviceId")
                            val peer = entry.devices().getPairedDevice(id)
                            if (peer != null) entry.network().connectPaired(peer)
                            else {
                                val addresses = request.getJSONArray("addresses")
                                val port = request.getInt("port")
                                require(port in 1..65535 && addresses.length() in 1..8)
                                entry.network().connectTo(ConnectionDetails(id, port,
                                    List(addresses.length()) { addresses.getString(it) }))
                            }
                            JSONObject().put("accepted", true)
                        }
                        "pair", "approve" -> {
                            val request = JSONObject(requireNotNull(arg))
                            entry.network().pairDevice(request.getString("deviceId"),
                                request.getString("verificationCode"), method == "approve")
                            JSONObject().put("accepted", true)
                        }
                        "disconnect" -> {
                            entry.network().disconnect(requireNotNull(arg))
                            JSONObject().put("accepted", true)
                        }
                        "options" -> {
                            val request = JSONObject(requireNotNull(arg))
                            if (request.has("clipboard")) entry.preferences().saveClipboardWorkerEnabled(request.getBoolean("clipboard"))
                            if (request.has("rootClipboard")) entry.preferences().saveRootClipboardEnabled(request.getBoolean("rootClipboard"))
                            if (request.has("onboardingComplete")) {
                                require(request.getBoolean("onboardingComplete"))
                                entry.preferences().saveAppEntry()
                            }
                            JSONObject().put("accepted", true)
                        }
                        else -> throw IllegalArgumentException("Unknown management method")
                    }
                }
            }
            Bundle().apply { putString("result", JSONObject().put("ok", true).put("data", result).toString()) }
        } catch (e: Exception) {
            Bundle().apply { putString("result", JSONObject().put("ok", false)
                .put("error", e.message ?: e.javaClass.simpleName).toString()) }
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    private suspend fun status(entry: Dependencies): JSONObject {
        val devices = entry.devices()
        val local = devices.localDeviceFlow.filterNotNull().first()
        val pending = devices.pendingDeviceApproval.value
        return JSONObject().put("apiVersion", 1)
            .put("deviceId", local.deviceId).put("deviceName", local.deviceName)
            .put("version", com.castle.sefirah.BuildConfig.VERSION_NAME)
            .put("rootClipboardStatus", entry.workers().rootStatus.value.name)
            .put("storageLocation", entry.preferences().getStorageLocation().first())
            .put("paired", JSONArray(devices.pairedDevices.value.map { peer ->
                JSONObject().put("deviceId", peer.deviceId).put("deviceName", peer.deviceName)
                    .put("connected", peer.connectionState.isConnected).put("address", peer.address)
            }))
            .put("discovered", JSONArray(devices.discoveredDevices.value.values.map { peer ->
                JSONObject().put("deviceId", peer.deviceId).put("deviceName", peer.deviceName)
                    .put("verificationCode", peer.verificationCode).put("address", peer.address).put("port", peer.port)
            }))
            .put("pending", pending?.let { JSONObject().put("deviceId", it.deviceId)
                .put("verificationCode", it.verificationCode) } ?: JSONObject.NULL)
    }

    @EntryPoint @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun network(): NetworkManager
        fun devices(): DeviceManager
        fun preferences(): PreferencesRepository
        fun workers(): WorkerManager
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
