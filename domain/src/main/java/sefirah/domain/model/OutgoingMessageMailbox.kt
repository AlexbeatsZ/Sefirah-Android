package sefirah.domain.model

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal class OutgoingMessage(
    val frame: String,
    val delivery: CompletableDeferred<Boolean>? = null,
    private val releaseBudget: (Int) -> Unit,
) {
    private val completed = AtomicBoolean(false)

    fun complete(delivered: Boolean) {
        if (!completed.compareAndSet(false, true)) return
        releaseBudget(frame.length)
        delivery?.complete(delivered)
    }
}

/**
 * A bounded per-connection mailbox. Regular traffic never creates one coroutine per frame,
 * while callers that must flush before continuing can await the writer's acknowledgement.
 */
internal class OutgoingMessageMailbox(
    capacity: Int = ProtocolLimits.OUTGOING_MESSAGE_CAPACITY,
    private val maxQueuedChars: Int = ProtocolLimits.OUTGOING_MESSAGE_MAX_QUEUED_CHARS,
) {
    init {
        require(capacity > 0) { "capacity must be positive" }
        require(maxQueuedChars > 0) { "maxQueuedChars must be positive" }
    }

    private val channel = Channel<OutgoingMessage>(capacity)
    private val queuedChars = AtomicLong(0)
    val messages: ReceiveChannel<OutgoingMessage> = channel

    fun trySend(frame: String): Boolean = enqueue(frame, delivery = null)

    suspend fun sendAndAwait(frame: String): Boolean {
        return trySendAcknowledged(frame)?.await() ?: false
    }

    fun trySendAcknowledged(frame: String): CompletableDeferred<Boolean>? {
        val delivery = CompletableDeferred<Boolean>()
        return delivery.takeIf { enqueue(frame, delivery) }
    }

    fun closeAndFailPending() {
        channel.close()
        while (true) {
            val pending = channel.tryReceive().getOrNull() ?: break
            pending.complete(false)
        }
    }

    private fun enqueue(frame: String, delivery: CompletableDeferred<Boolean>?): Boolean {
        if (frame.length > maxQueuedChars || !reserve(frame.length)) return false

        val outgoing = OutgoingMessage(frame, delivery, ::release)
        if (channel.trySend(outgoing).isSuccess) return true

        outgoing.complete(false)
        return false
    }

    private fun reserve(charCount: Int): Boolean {
        while (true) {
            val current = queuedChars.get()
            val updated = current + charCount
            if (updated > maxQueuedChars) return false
            if (queuedChars.compareAndSet(current, updated)) return true
        }
    }

    private fun release(charCount: Int) {
        queuedChars.addAndGet(-charCount.toLong())
    }
}
