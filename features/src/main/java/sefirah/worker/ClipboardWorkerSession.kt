package sefirah.worker

import kotlinx.coroutines.CompletableDeferred
import java.util.UUID

/** Access is guarded by WorkerManager's lock; each setting change invalidates outstanding launches. */
internal class ClipboardWorkerSession {
    data class Mode(val enabled: Boolean, val preferRoot: Boolean)
    data class Launch(val mode: Mode, val token: String, val registered: CompletableDeferred<Unit>)

    @Volatile var mode = Mode(false, false)
        private set
    private var pending: Launch? = null
    private var attempts = 0

    fun update(enabled: Boolean, preferRoot: Boolean): Boolean {
        val next = Mode(enabled, enabled && preferRoot)
        if (mode == next) return false
        mode = next
        attempts = 0
        pending?.registered?.complete(Unit)
        pending = null
        return true
    }

    fun begin(current: Mode): Launch? {
        if (current !== mode || !current.enabled || !current.preferRoot || attempts >= 2 || pending != null) return null
        attempts++
        return Launch(current, UUID.randomUUID().toString(), CompletableDeferred()).also { pending = it }
    }

    fun accept(token: String): Boolean {
        val launch = pending ?: return false
        if (launch.mode !== mode || launch.token != token) return false
        pending = null
        launch.registered.complete(Unit)
        return true
    }

    fun finish(launch: Launch, registered: Boolean) {
        if (pending === launch) pending = null
        if (mode === launch.mode && !registered) attempts = 2
    }
}
