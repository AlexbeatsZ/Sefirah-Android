package sefirah.privileged

/** One in-flight bind, followed by a cooldown after failure. Clock supplied is monotonic. */
internal class BridgeBindingPolicy(private val retryDelayMillis: Long = 5_000L) {
    private var pending = false
    private var retryAt = 0L

    @Synchronized
    fun begin(now: Long): Boolean {
        if (pending || now < retryAt) return false
        pending = true
        return true
    }

    @Synchronized
    fun failed(now: Long) {
        pending = false
        retryAt = now + retryDelayMillis
    }

    @Synchronized
    fun reset() {
        pending = false
        retryAt = 0L
    }
}
