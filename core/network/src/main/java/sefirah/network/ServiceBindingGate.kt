package sefirah.network

/**
 * Keeps a single Context service-binding registration balanced with one unbind.
 *
 * A registered binding stays registered while Android temporarily disconnects the
 * service; ServiceConnection will reconnect it without another bindService call.
 */
internal class ServiceBindingGate {
    private val lock = Any()
    private var registered = false

    fun bindOnce(bind: () -> Boolean): Boolean = synchronized(lock) {
        if (registered) return false

        val didBind = bind()
        if (didBind) {
            registered = true
        }
        didBind
    }

    fun unbindOnce(unbind: () -> Unit): Boolean = synchronized(lock) {
        if (!registered) return false

        // Consume the registration before calling Android so a failed/unmatched
        // unbind cannot cause every later stop request to unbind again.
        registered = false
        unbind()
        true
    }
}
