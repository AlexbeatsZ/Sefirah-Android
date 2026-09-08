package sefirah.privileged

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    @Volatile
    private var started = false
    private val bindingPolicy = BridgeBindingPolicy()
    private val readinessMutex = Mutex()
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

    private var serviceConnection: ServiceConnection? = null

    private fun newServiceConnection() = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            synchronized(this@PrivilegedBridgeManager) {
                // An old callback must never publish or remove the replacement generation.
                if (!started || serviceConnection !== this) return
                Log.i(TAG, "Privileged bridge connected")
                bindingPolicy.reset()
                bridge = IPrivilegedBridge.Stub.asInterface(binder)
                _status.value = if (binder.pingBinder()) {
                    PrivilegedBridgeStatus.Ready
                } else {
                    PrivilegedBridgeStatus.Error
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            synchronized(this@PrivilegedBridgeManager) {
                if (serviceConnection !== this) return
                Log.w(TAG, "Privileged bridge disconnected")
                bindingPolicy.reset()
                bridge = null
                _status.value = PrivilegedBridgeStatus.Unavailable
            }
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        if (started) refreshAndBind()
    }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        synchronized(this) {
            destroyDetachedBridge()
            bindingPolicy.reset()
            if (started) _status.value = PrivilegedBridgeStatus.Unavailable
        }
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

    /**
     * Releases the non-daemon UserService when NetworkService is explicitly torn down. The
     * singleton remains restartable in the same app process, so its executor is intentionally kept.
     */
    @Synchronized
    fun stop() {
        if (!started && bridge == null) return

        runCatching { bridge?.stopSftpServer() }
            .onFailure { Log.w(TAG, "Failed to stop privileged SFTP during bridge shutdown", it) }

        val removedByShizuku = runCatching {
            if (Shizuku.pingBinder()) {
                val connection = serviceConnection
                serviceConnection = null
                connection?.let { Shizuku.unbindUserService(userServiceArgs, it, true) }
                true
            } else {
                false
            }
        }.onFailure {
            Log.w(TAG, "Failed to remove privileged bridge through Shizuku", it)
        }.getOrDefault(false)

        if (!removedByShizuku) destroyDetachedBridge() else bridge = null

        if (started) {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        }
        started = false
        bindingPolicy.reset()
        _status.value = PrivilegedBridgeStatus.Unavailable
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
        if (bridge?.asBinder()?.pingBinder() != true && !ensureReady()) return@withContext null
        runCatching { bridge?.readClipboardText() }.getOrNull()
    }

    suspend fun getBluetoothCatalog(): String? = withContext(Dispatchers.IO) {
        callBluetoothBridge("catalog") { it.getBluetoothCatalog() }
    }

    suspend fun ensureReady(timeoutMillis: Long = BIND_TIMEOUT_MS): Boolean =
        withContext(Dispatchers.IO) {
            readinessMutex.withLock {
                start()
                if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
                    bridge = null
                    _status.value = PrivilegedBridgeStatus.Unavailable
                    return@withLock false
                }

                refreshAndBind()
                val deadline = SystemClock.elapsedRealtime() + timeoutMillis
                while (SystemClock.elapsedRealtime() < deadline) {
                    if (bridge?.asBinder()?.pingBinder() == true) {
                        _status.value = PrivilegedBridgeStatus.Ready
                        return@withLock true
                    }
                    if (_status.value != PrivilegedBridgeStatus.Binding) return@withLock false
                    delay(BIND_RETRY_INTERVAL_MS)
                }
                if (bridge?.asBinder()?.pingBinder() == true) return@withLock true
                Log.w(TAG, "Privileged bridge did not become ready within ${timeoutMillis}ms; status=${_status.value}")
                recycleBridge()
                false
            }
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
        if (!started) return
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
        if (!started) return
        if (bridge?.asBinder()?.pingBinder() == true) {
            _status.value = PrivilegedBridgeStatus.Ready
            return
        }
        if (!bindingPolicy.begin(SystemClock.elapsedRealtime())) return
        _status.value = PrivilegedBridgeStatus.Binding
        Log.i(TAG, "Binding privileged bridge")
        runCatching {
            val connection = serviceConnection ?: newServiceConnection().also { serviceConnection = it }
            Shizuku.bindUserService(userServiceArgs, connection)
        }.onFailure {
            Log.e(TAG, "Failed to bind privileged bridge", it)
            bindingPolicy.failed(SystemClock.elapsedRealtime())
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
        _status.value = PrivilegedBridgeStatus.Error
        bindingPolicy.failed(SystemClock.elapsedRealtime())
        val connection = serviceConnection
        serviceConnection = null
        runCatching {
            connection?.let { Shizuku.unbindUserService(userServiceArgs, it, true) }
        }.onFailure {
            Log.w(TAG, "Failed to recycle privileged bridge", it)
        }
    }

    private fun destroyDetachedBridge() {
        serviceConnection = null
        val detached = bridge
        bridge = null
        if (detached?.asBinder()?.pingBinder() != true) return
        runCatching { detached.destroy() }
            .onFailure { Log.w(TAG, "Failed to destroy detached privileged bridge", it) }
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
