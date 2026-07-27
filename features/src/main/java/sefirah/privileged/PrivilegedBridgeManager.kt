package sefirah.privileged

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.inject.Inject
import javax.inject.Singleton

enum class PrivilegedBridgeStatus {
    Unavailable,
    PermissionRequired,
    Binding,
    Ready,
    Error,
}

@Singleton
class PrivilegedBridgeManager @Inject constructor(context: Context) {
    private val appContext = context.applicationContext
    private val _status = MutableStateFlow(PrivilegedBridgeStatus.Unavailable)
    val status: StateFlow<PrivilegedBridgeStatus> = _status.asStateFlow()

    @Volatile
    private var bridge: IPrivilegedBridge? = null
    private var started = false
    private var lastBindAttemptAt = 0L
    private val bridgeCallExecutor = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "Sefirah-PrivilegedBridgeCall").apply { isDaemon = true }
    }

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(appContext.packageName, PrivilegedBridgeService::class.java.name),
    )
        .daemon(false)
        .processNameSuffix("privileged")
        .debuggable(false)
        .version(SERVICE_VERSION)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            Log.i(TAG, "Privileged bridge connected")
            bridge = IPrivilegedBridge.Stub.asInterface(binder)
            _status.value = if (binder.pingBinder()) {
                PrivilegedBridgeStatus.Ready
            } else {
                PrivilegedBridgeStatus.Error
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            Log.w(TAG, "Privileged bridge disconnected")
            bridge = null
            _status.value = PrivilegedBridgeStatus.Unavailable
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { refreshAndBind() }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        bridge = null
        _status.value = PrivilegedBridgeStatus.Unavailable
    }
    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResult == PackageManager.PERMISSION_GRANTED) bind() else {
                _status.value = PrivilegedBridgeStatus.PermissionRequired
            }
        }
    }

    @Synchronized
    fun start() {
        if (started) return
        started = true
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
    }

    fun requestPermission() {
        start()
        runCatching {
            if (!Shizuku.pingBinder()) {
                _status.value = PrivilegedBridgeStatus.Unavailable
                return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                bind()
            } else if (Shizuku.shouldShowRequestPermissionRationale()) {
                _status.value = PrivilegedBridgeStatus.PermissionRequired
            } else {
                Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
            }
        }.onFailure {
            _status.value = PrivilegedBridgeStatus.Error
        }
    }

    suspend fun readClipboardText(): String? = withContext(Dispatchers.IO) {
        runCatching { bridge?.readClipboardText() }.getOrNull()
    }

    suspend fun getBluetoothCatalog(): String? = withContext(Dispatchers.IO) {
        callBluetoothBridge("catalog") { it.getBluetoothCatalog() }
    }

    suspend fun ensureReady(timeoutMillis: Long = BIND_TIMEOUT_MS): Boolean = withContext(Dispatchers.IO) {
        start()
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (bridge?.asBinder()?.pingBinder() == true) {
                _status.value = PrivilegedBridgeStatus.Ready
                return@withContext true
            }
            refreshAndBind()
            delay(BIND_RETRY_INTERVAL_MS)
        }
        Log.w(TAG, "Privileged bridge did not become ready within ${timeoutMillis}ms; status=${_status.value}")
        false
    }

    suspend fun executeBluetoothCommand(action: String, deviceKey: String?, enabled: Boolean): String? =
        withContext(Dispatchers.IO) {
            callBluetoothBridge("command:$action") {
                it.executeBluetoothCommand(action, deviceKey, enabled)
            }
        }

    suspend fun startSftpServer(username: String, password: String, rootPath: String): Int? =
        withContext(Dispatchers.IO) {
            runCatching {
                bridge?.startSftpServer(username, password, rootPath)?.takeIf { it > 0 }
            }.onFailure {
                Log.e(TAG, "Failed to start privileged SFTP server", it)
            }.getOrNull()
        }

    suspend fun stopSftpServer() = withContext(Dispatchers.IO) {
        runCatching { bridge?.stopSftpServer() }
            .onFailure { Log.w(TAG, "Failed to stop privileged SFTP server", it) }
        Unit
    }

    private fun refreshAndBind() {
        runCatching {
            if (Shizuku.isPreV11()) {
                _status.value = PrivilegedBridgeStatus.Unavailable
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                bind()
            } else {
                _status.value = PrivilegedBridgeStatus.PermissionRequired
            }
        }.onFailure {
            _status.value = PrivilegedBridgeStatus.Error
        }
    }

    @Synchronized
    private fun bind() {
        if (bridge?.asBinder()?.pingBinder() == true) {
            _status.value = PrivilegedBridgeStatus.Ready
            return
        }
        val now = System.currentTimeMillis()
        if (_status.value == PrivilegedBridgeStatus.Binding && now - lastBindAttemptAt < BIND_RETRY_INTERVAL_MS) {
            return
        }
        lastBindAttemptAt = now
        _status.value = PrivilegedBridgeStatus.Binding
        Log.i(TAG, "Binding privileged bridge")
        runCatching {
            Shizuku.bindUserService(userServiceArgs, serviceConnection)
        }.onFailure {
            Log.e(TAG, "Failed to bind privileged bridge", it)
            _status.value = PrivilegedBridgeStatus.Error
        }
    }

    private fun <T> callBluetoothBridge(operation: String, call: (IPrivilegedBridge) -> T): T? {
        val currentBridge = bridge ?: return null
        val future = bridgeCallExecutor.submit(Callable { call(currentBridge) })
        return try {
            future.get(BLUETOOTH_BRIDGE_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            Log.e(TAG, "Privileged Bluetooth $operation timed out; recycling the bridge")
            recycleBridge()
            null
        } catch (error: Exception) {
            Log.e(TAG, "Privileged Bluetooth $operation failed", error)
            null
        }
    }

    @Synchronized
    private fun recycleBridge() {
        bridge = null
        _status.value = PrivilegedBridgeStatus.Unavailable
        lastBindAttemptAt = 0L
        runCatching {
            Shizuku.unbindUserService(userServiceArgs, serviceConnection, true)
        }.onFailure {
            Log.w(TAG, "Failed to recycle privileged bridge", it)
        }
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 7821
        private const val SERVICE_VERSION = 4
        private const val BIND_TIMEOUT_MS = 6_000L
        private const val BIND_RETRY_INTERVAL_MS = 1_000L
        private const val BLUETOOTH_BRIDGE_CALL_TIMEOUT_MS = 9_000L
        private const val TAG = "PrivilegedBridgeManager"
    }
}
