package sefirah.domain.model

import android.os.SystemClock
import android.util.Log
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import sefirah.domain.util.MessageSerializer
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLSocket

class DeviceConnection(
    val deviceId: String,
    val direction: ConnectionDirection,
    var sslSocket: SSLSocket? = null,
    var readChannel: ByteReadChannel? = null,
    var writeChannel: ByteWriteChannel? = null,
    private val monotonicClock: () -> Long = SystemClock::uptimeMillis,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val outgoingMessages = OutgoingMessageMailbox()
    private val closed = AtomicBoolean(false)
    private val submissionLock = Any()
    private var listeningJob: Job? = null
    private var messageHandlerJob: Job? = null
    private val writerJob = scope.launch { writeMessages() }
    @Volatile var lastReceivedAt: Long = monotonicClock()
        private set

    /** Returns false when the bounded mailbox is full or the connection is already closed. */
    fun sendMessage(message: SocketMessage): Boolean = synchronized(submissionLock) {
        if (closed.get()) return@synchronized false
        val frame = serializeFrame(message) ?: return@synchronized false
        outgoingMessages.trySend(frame)
    }

    /** Queues liveness/control traffic ahead of regular application frames. */
    fun sendControlMessage(message: SocketMessage): Boolean = synchronized(submissionLock) {
        if (closed.get()) return@synchronized false
        val frame = serializeFrame(message) ?: return@synchronized false
        outgoingMessages.trySendControl(frame)
    }

    /** Waits for bounded mailbox space and for the single writer to flush the frame. */
    suspend fun sendMessageAndAwait(message: SocketMessage): Boolean {
        val frame = synchronized(submissionLock) {
            if (closed.get()) return@synchronized null
            serializeFrame(message)
        } ?: return false
        return outgoingMessages.sendAndAwait(frame)
    }

    /** Flushes a control frame using the mailbox's reserved high-priority lane. */
    suspend fun sendControlMessageAndAwait(message: SocketMessage): Boolean {
        val frame = synchronized(submissionLock) {
            if (closed.get()) return@synchronized null
            serializeFrame(message)
        } ?: return false
        return outgoingMessages.sendControlAndAwait(frame)
    }

    /**
     * Starts listening for messages from the device.
     * @param scope The coroutine scope to launch the listener in
     * @param getDevice Function to get the device by deviceId
     * @param onMessage Callback to handle received messages
     * @param onClose Callback when connection closes
     */
    fun startListening(
        getDevice: suspend (String) -> BaseRemoteDevice?,
        onMessage: suspend (BaseRemoteDevice, SocketMessage) -> Unit,
        onClose: (DeviceConnection) -> Unit,
    ) {
        // Stop existing listener if any
        listeningJob?.cancel()
        messageHandlerJob?.cancel()

        val channel = readChannel ?: return
        val queuedMessageChars = AtomicLong(0)
        val messageQueue = Channel<IncomingMessage>(ProtocolLimits.INCOMING_MESSAGE_CAPACITY)

        messageHandlerJob = scope.launch {
            for (incoming in messageQueue) {
                try {
                    onMessage(incoming.device, incoming.message)
                } finally {
                    queuedMessageChars.addAndGet(-incoming.frameChars.toLong())
                }
            }
        }

        listeningJob = scope.launch {
            try {
                while (isActive && !channel.isClosedForRead) {
                    try {
                        val line = channel.readUTF8Line(ProtocolLimits.MESSAGE_FRAME_MAX_CHARS)
                            ?: break
                        lastReceivedAt = monotonicClock()
                        MessageSerializer.deserialize(line)?.let { socketMessage ->
                            val device = getDevice(deviceId) ?: return@let
                            if (socketMessage is ConnectionHeartbeat) {
                                // Liveness must not wait behind a slow Bluetooth, media, or database
                                // handler. It is deliberately the only concurrently dispatched frame.
                                onMessage(device, socketMessage)
                            } else {
                                val reserved = reserveIncomingChars(queuedMessageChars, line.length)
                                if (!reserved || !messageQueue.trySend(
                                        IncomingMessage(device, socketMessage, line.length),
                                    ).isSuccess
                                ) {
                                    if (reserved) queuedMessageChars.addAndGet(-line.length.toLong())
                                    Log.w(TAG, "Inbound message queue is full for $deviceId; closing connection")
                                    return@launch
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Read error for $deviceId", e)
                        break
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Session error for $deviceId", e)
            } finally {
                messageQueue.close()
                onClose(this@DeviceConnection)
            }
        }
    }

    /**
     * Closes all connection resources and stops listening.
     */
    fun close() {
        closeIfOpen()
    }

    /** Closes once and reports whether this call performed the transition. */
    fun closeIfOpen(): Boolean {
        if (!closed.compareAndSet(false, true)) return false

        outgoingMessages.closeAndFailPending()
        listeningJob?.cancel()
        listeningJob = null
        messageHandlerJob?.cancel()
        messageHandlerJob = null
        try {
            sslSocket?.close()
            readChannel?.cancel(kotlinx.io.IOException())
            writeChannel?.cancel(kotlinx.io.IOException())
        } catch (_: Exception) {
        }
        writerJob.cancel()
        scope.cancel()
        sslSocket = null
        readChannel = null
        writeChannel = null
        return true
    }

    private suspend fun writeMessages() {
        var inFlight: OutgoingMessage? = null
        try {
            while (true) {
                val outgoing = outgoingMessages.receive() ?: break
                inFlight = outgoing
                val channel = writeChannel ?: run {
                    throw IOException("Write channel is unavailable")
                }
                channel.writeStringUtf8(outgoing.frame)
                channel.writeStringUtf8("\n")
                channel.flush()
                outgoing.complete(true)
                inFlight = null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!closed.get()) {
                Log.e(TAG, "Writer failed for $deviceId; closing connection", e)
                close()
            }
        } finally {
            inFlight?.complete(false)
            outgoingMessages.closeAndFailPending()
        }
    }

    private fun serializeFrame(message: SocketMessage): String? {
        val serialized = MessageSerializer.serialize(message) ?: return null
        if (serialized.length > ProtocolLimits.MESSAGE_FRAME_MAX_CHARS) return null
        return serialized
    }

    private fun reserveIncomingChars(queuedChars: AtomicLong, frameChars: Int): Boolean {
        while (true) {
            val current = queuedChars.get()
            if (frameChars.toLong() > ProtocolLimits.INCOMING_MESSAGE_MAX_QUEUED_CHARS - current) {
                return false
            }
            if (queuedChars.compareAndSet(current, current + frameChars)) return true
        }
    }

    private data class IncomingMessage(
        val device: BaseRemoteDevice,
        val message: SocketMessage,
        val frameChars: Int,
    )

    private companion object {
        const val TAG = "DeviceConnection"
    }
}
