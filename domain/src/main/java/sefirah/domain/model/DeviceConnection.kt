package sefirah.domain.model

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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import sefirah.domain.util.MessageSerializer
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket

class DeviceConnection(
    val deviceId: String,
    val direction: ConnectionDirection,
    var sslSocket: SSLSocket? = null,
    var readChannel: ByteReadChannel? = null,
    var writeChannel: ByteWriteChannel? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val outgoingMessages = OutgoingMessageMailbox()
    private val closed = AtomicBoolean(false)
    private val submissionLock = Any()
    private var listeningJob: Job? = null
    private val writerJob = scope.launch { writeMessages() }
    @Volatile var lastReceivedAt: Long = System.currentTimeMillis()
        private set

    /** Returns false when the bounded mailbox is full or the connection is already closed. */
    fun sendMessage(message: SocketMessage): Boolean = synchronized(submissionLock) {
        if (closed.get()) return@synchronized false
        val frame = serializeFrame(message) ?: return@synchronized false
        outgoingMessages.trySend(frame)
    }

    /** Queues a frame with backpressure and waits until the single writer flushes it. */
    suspend fun sendMessageAndAwait(message: SocketMessage): Boolean {
        val delivery = synchronized(submissionLock) {
            if (closed.get()) return@synchronized null
            val frame = serializeFrame(message) ?: return@synchronized null
            outgoingMessages.trySendAcknowledged(frame)
        } ?: return false
        return delivery.await()
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

        val channel = readChannel ?: return

        listeningJob = scope.launch {
            try {
                while (isActive && !channel.isClosedForRead) {
                    try {
                        val line = channel.readUTF8Line(ProtocolLimits.MESSAGE_FRAME_MAX_CHARS)
                            ?: break
                        lastReceivedAt = System.currentTimeMillis()
                        MessageSerializer.deserialize(line)?.let { socketMessage ->
                            val device = getDevice(deviceId) ?: return@let
                            onMessage(device, socketMessage)
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
                onClose(this@DeviceConnection)
            }
        }
    }

    /**
     * Closes all connection resources and stops listening.
     */
    fun close() {
        if (!closed.compareAndSet(false, true)) return

        outgoingMessages.closeAndFailPending()
        listeningJob?.cancel()
        listeningJob = null
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
    }

    private suspend fun writeMessages() {
        var inFlight: OutgoingMessage? = null
        try {
            for (outgoing in outgoingMessages.messages) {
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

    private companion object {
        const val TAG = "DeviceConnection"
    }
}
