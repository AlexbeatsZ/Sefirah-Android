package sefirah.domain.model

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
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
    controlCapacity: Int = DEFAULT_CONTROL_CAPACITY,
    private val maxControlQueuedChars: Int = DEFAULT_CONTROL_MAX_QUEUED_CHARS,
) {
    init {
        require(capacity > 0) { "capacity must be positive" }
        require(maxQueuedChars > 0) { "maxQueuedChars must be positive" }
        require(controlCapacity > 0) { "controlCapacity must be positive" }
        require(maxControlQueuedChars > 0) { "maxControlQueuedChars must be positive" }
    }

    private val applicationChannel = Channel<OutgoingMessage>(capacity)
    private val controlChannel = Channel<OutgoingMessage>(controlCapacity)
    private val spaceAvailable = Channel<Unit>(Channel.CONFLATED)
    private val closed = AtomicBoolean(false)
    private val queuedChars = AtomicLong(0)
    private val controlQueuedChars = AtomicLong(0)

    fun trySend(frame: String): Boolean = enqueueApplication(frame, delivery = null)

    fun trySendControl(frame: String): Boolean = enqueueControl(frame, delivery = null)

    suspend fun sendAndAwait(frame: String): Boolean {
        if (frame.length > maxQueuedChars) return false

        while (!closed.get()) {
            trySendAcknowledged(frame)?.let { return it.await() }
            if (spaceAvailable.receiveCatching().getOrNull() == null) return false
        }
        return false
    }

    fun trySendAcknowledged(frame: String): CompletableDeferred<Boolean>? {
        val delivery = CompletableDeferred<Boolean>()
        return delivery.takeIf { enqueueApplication(frame, delivery) }
    }

    suspend fun sendControlAndAwait(frame: String): Boolean {
        val delivery = CompletableDeferred<Boolean>()
        return if (enqueueControl(frame, delivery)) delivery.await() else false
    }

    suspend fun receive(): OutgoingMessage? {
        controlChannel.tryReceive().getOrNull()?.let { return it }
        applicationChannel.tryReceive().getOrNull()?.let { return it }
        return select {
            controlChannel.onReceiveCatching { it.getOrNull() }
            applicationChannel.onReceiveCatching { it.getOrNull() }
        }
    }

    fun closeAndFailPending() {
        if (!closed.compareAndSet(false, true)) return
        controlChannel.close()
        applicationChannel.close()
        spaceAvailable.close()
        drainAndFail(controlChannel)
        drainAndFail(applicationChannel)
    }

    private fun enqueueApplication(frame: String, delivery: CompletableDeferred<Boolean>?): Boolean =
        enqueue(
            frame = frame,
            delivery = delivery,
            channel = applicationChannel,
            queuedCharacterCount = queuedChars,
            characterLimit = maxQueuedChars,
            release = ::releaseApplicationQueued,
        )

    private fun enqueueControl(frame: String, delivery: CompletableDeferred<Boolean>?): Boolean =
        enqueue(
            frame = frame,
            delivery = delivery,
            channel = controlChannel,
            queuedCharacterCount = controlQueuedChars,
            characterLimit = maxControlQueuedChars,
            release = ::releaseControlQueued,
        )

    private fun enqueue(
        frame: String,
        delivery: CompletableDeferred<Boolean>?,
        channel: Channel<OutgoingMessage>,
        queuedCharacterCount: AtomicLong,
        characterLimit: Int,
        release: (Int) -> Unit,
    ): Boolean {
        if (frame.length > characterLimit || !reserve(queuedCharacterCount, frame.length, characterLimit)) return false

        val outgoing = OutgoingMessage(frame, delivery, release)
        if (channel.trySend(outgoing).isSuccess) return true

        // This reservation never entered the queue, so it must not wake the same
        // waiting sender and create a retry loop while the queue is still full.
        releaseReservation(queuedCharacterCount, frame.length)
        delivery?.complete(false)
        return false
    }

    private fun reserve(counter: AtomicLong, charCount: Int, limit: Int): Boolean {
        while (true) {
            val current = counter.get()
            val updated = current + charCount
            if (updated > limit) return false
            if (counter.compareAndSet(current, updated)) return true
        }
    }

    private fun releaseApplicationQueued(charCount: Int) {
        releaseReservation(queuedChars, charCount)
        spaceAvailable.trySend(Unit)
    }

    private fun releaseControlQueued(charCount: Int) {
        releaseReservation(controlQueuedChars, charCount)
    }

    private fun releaseReservation(counter: AtomicLong, charCount: Int) {
        counter.addAndGet(-charCount.toLong())
    }

    private fun drainAndFail(channel: Channel<OutgoingMessage>) {
        while (true) {
            val pending = channel.tryReceive().getOrNull() ?: break
            pending.complete(false)
        }
    }

    private companion object {
        const val DEFAULT_CONTROL_CAPACITY = 8
        const val DEFAULT_CONTROL_MAX_QUEUED_CHARS = 64 * 1024
    }
}
