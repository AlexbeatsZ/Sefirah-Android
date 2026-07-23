package sefirah.domain.model

object ReconnectBackoffPolicy {
    const val BASE_DELAY_MS = 5_000L
    const val MAX_DELAY_MS = 60_000L

    fun delayAfterFailure(consecutiveFailures: Int): Long {
        val exponent = (consecutiveFailures - 1).coerceIn(0, 30)
        val multiplier = 1L shl exponent
        return (BASE_DELAY_MS * multiplier).coerceAtMost(MAX_DELAY_MS)
    }
}
