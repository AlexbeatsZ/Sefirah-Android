package sefirah.worker

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.DeadObjectException
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import sefirah.domain.interfaces.NetworkManager
import sefirah.domain.model.RequestWorkerLaunch
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkerManager @Inject constructor(
    private val context: Context,
    private val networkManager: NetworkManager,
) {
    enum class RootStatus { Idle, Starting, Ready, Unavailable }

    private data class Registration(val service: IWorkerService, val uid: Int, val pid: Int, val root: Boolean)

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refresh = Channel<Unit>(Channel.CONFLATED)
    private val _rootStatus = MutableStateFlow(RootStatus.Idle)
    val rootStatus = _rootStatus.asStateFlow()

    @Volatile private var registration: Registration? = null
    private val session = ClipboardWorkerSession()
    private val mode get() = session.mode
    private val shizukuEpoch = AtomicInteger()
    private var attemptedLegacy: Triple<ClipboardWorkerSession.Mode, Boolean, Int>? = null
    private val worker: IWorkerService? get() = registration?.service

    private val userServiceArgs: Shizuku.UserServiceArgs by lazy { userServiceArgs() }

    private val shizukuBinderReceivedListener = Shizuku.OnBinderReceivedListener {
        shizukuEpoch.incrementAndGet()
        if (mode.enabled) refresh.trySend(Unit)
    }

    init {
        Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceivedListener)
        scope.launch {
            for (event in refresh) {
                try {
                    reconcile()
                } catch (e: Exception) {
                    Log.e(TAG, "Clipboard watcher update failed", e)
                }
            }
        }
    }

    fun updateClipboardWatcher(enabled: Boolean, preferRoot: Boolean = false) {
        val revoked = synchronized(lock) {
            if (!session.update(enabled, preferRoot)) return
            _rootStatus.value = RootStatus.Idle
            registration?.takeIf { it.root && !mode.preferRoot }?.also { registration = null }
        }
        if (revoked != null) scope.launch { destroy(revoked.service) }
        refresh.trySend(Unit)
    }

    private suspend fun reconcile() {
        val current = mode
        if (!current.enabled) {
            requestStopWatching()
            return
        }
        val launch = synchronized(lock) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && registration?.root != true)
                session.begin(current) else null
        }
        if (launch != null) {
            _rootStatus.value = RootStatus.Starting
            try {
                if (RootWorkerLauncher.launch(context, launch.token)) {
                    withTimeoutOrNull(5_000) { launch.registered.await() }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Root clipboard launch failed", e)
            } finally {
                synchronized(lock) {
                    val registered = registration?.root == true
                    session.finish(launch, registered)
                    if (mode === current) {
                        // Retry once after a worker that registered dies. Failed grants/startups fall back.
                        _rootStatus.value = if (registered) RootStatus.Ready else RootStatus.Unavailable
                    }
                }
            }
        }

        if (mode !== current) return
        if (isWorkerAlive()) {
            requestStartWatching()
            attemptedLegacy = null
        } else {
            val shizuku = ShizukuHelper.isAuthorized()
            val attempt = attemptedLegacy
            val epoch = shizukuEpoch.get()
            if (attempt?.first === current && attempt.second == shizuku && attempt.third == epoch) return
            attemptedLegacy = Triple(current, shizuku, epoch)
            if (shizuku) {
                startWorkerViaShizuku()
            } else {
                networkManager.broadcastMessage(RequestWorkerLaunch(WorkerStarter.command(context)))
            }
        }
    }

    /** Count one inbound setPrimaryClip; worker skips that clip timestamp (and its classification re-fires). */
    fun suppressNextOutbound() {
        try {
            worker?.suppressNextOutbound()
        } catch (e: Exception) {
            Log.w(TAG, "suppressNextOutbound failed", e)
        }
    }

    /** A live shell-UID worker (root launcher or Shizuku) can switch Bluetooth without a local prompt. */
    fun canControlBluetooth(): Boolean = registration?.uid == SHELL_UID && isWorkerAlive()

    fun setBluetoothEnabled(enabled: Boolean): Boolean = try {
        canControlBluetooth() && worker?.setBluetoothEnabled(enabled) == true
    } catch (e: Exception) {
        Log.w(TAG, "setBluetoothEnabled failed", e)
        false
    }

    fun isWorkerAlive(): Boolean =
        try {
            if (!mode.enabled) return false
            val w = worker ?: return false
            w.asBinder().pingBinder()
        } catch (_: Exception) {
            false
        }

    fun isActiveWorker(uid: Int, pid: Int): Boolean {
        val current = registration
        return current != null && mode.enabled && current.uid == uid && current.pid == pid
    }

    fun registerWorker(workerBinder: IBinder, uid: Int, pid: Int, token: String?): Boolean {
        val root = token != null
        if (root && uid != SHELL_UID) return false
        val w = IWorkerService.Stub.asInterface(workerBinder)
        val version = runCatching { w.version }.getOrNull()
        if (version != WORKER_VERSION) {
            Log.w(TAG, "Rejecting worker version=$version expected=$WORKER_VERSION")
            scope.launch {
                destroy(w)
                if (!root) {
                    shizukuEpoch.incrementAndGet()
                    refresh.trySend(Unit)
                }
            }
            return false
        }

        val next = Registration(w, uid, pid, root)
        val previous = synchronized(lock) {
            if (root && !session.accept(token!!)) return false
            if (!root && registration?.root == true) return false

            try {
                workerBinder.linkToDeath({
                    val wasCurrent = synchronized(lock) {
                        if (registration !== next) false else {
                            registration = null
                            if (root) _rootStatus.value = RootStatus.Unavailable
                            true
                        }
                    }
                    if (wasCurrent) refresh.trySend(Unit)
                }, 0)
            } catch (e: Exception) {
                Log.w(TAG, "linkToDeath failed", e)
                return false
            }

            registration.also {
                registration = next
            }
        }
        if (previous != null && previous.service.asBinder() != workerBinder) {
            scope.launch { destroy(previous.service) }
        }
        Log.i(TAG, "Worker registered (version=$version, rootLauncher=$root)")
        refresh.trySend(Unit)
        return true
    }

    private fun destroy(w: IWorkerService) {
        try {
            w.destroy()
        } catch (_: DeadObjectException) {
            // destroy() may exit before the binder reply
        } catch (e: Exception) {
            Log.w(TAG, "Destroy worker failed", e)
        }
    }

    private fun requestStartWatching() {
        try {
            worker?.startWatching()
        } catch (e: Exception) {
            Log.w(TAG, "startWatching failed", e)
        }
    }

    private fun requestStopWatching() {
        try {
            worker?.stopWatching()
        } catch (e: Exception) {
            Log.w(TAG, "stopWatching failed", e)
        }
    }

    private fun startWorkerViaShizuku() {
        Log.i(TAG, "Starting worker via Shizuku: ${WorkerStarter.command(context)}")
        val args = userServiceArgs
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                try {
                    IWorkerLauncherService.Stub.asInterface(service).startWorker()
                } catch (e: Exception) {
                    Log.e(TAG, "startWorker failed", e)
                } finally {
                    try {
                        Shizuku.unbindUserService(args, this, true)
                    } catch (e: Exception) {
                        Log.w(TAG, "unbindUserService failed", e)
                    }
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        Shizuku.bindUserService(args, connection)
    }

    private fun userServiceArgs(): Shizuku.UserServiceArgs {
        val version = try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            @Suppress("DEPRECATION")
            info.versionCode
        } catch (_: PackageManager.NameNotFoundException) {
            1
        }
        val debuggable =
            (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        return Shizuku.UserServiceArgs(
            ComponentName(context.packageName, WorkerLauncherUserService::class.java.name),
        )
            .daemon(false)
            .processNameSuffix(USER_SERVICE_TAG)
            .debuggable(debuggable)
            .version(version)
    }

    companion object {
        private const val TAG = "WorkerManager"
        const val NICE_NAME = "sefirah_worker"

        /**
         * Must match `:worker` `BuildConfig.VERSION_CODE`. Bump when worker code / AIDL changes
         * so an old still-running `app_process` is replaced.
         */
        const val WORKER_VERSION = 3
        internal const val SHELL_UID = 2000

        private const val USER_SERVICE_TAG = "worker-launcher"
    }
}
