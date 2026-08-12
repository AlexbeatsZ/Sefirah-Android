package sefirah.domain.model

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sefirah.domain.util.MessageSerializer
import java.util.concurrent.atomic.AtomicInteger

class DeviceConnectionTest {
    @Test
    fun `close transition is reported only once`() {
        val connection = DeviceConnection(
            deviceId = "peer",
            direction = ConnectionDirection.Outgoing,
            monotonicClock = { 0L },
        )

        assertTrue(connection.closeIfOpen())
        assertFalse(connection.closeIfOpen())
    }

    @Test
    fun `writer failure fails in-flight acknowledgement and closes connection`() = runBlocking {
        val connection = DeviceConnection(
            deviceId = "peer",
            direction = ConnectionDirection.Outgoing,
            monotonicClock = { 0L },
        )

        assertFalse(withTimeout(2_000) { connection.sendMessageAndAwait(ConnectionHeartbeat) })
        assertFalse(connection.sendMessage(ConnectionHeartbeat))
        connection.close()
    }

    @Test
    fun `reader serializes application handlers and preserves per-device order`() = runBlocking {
        val input = ByteChannel()
        val connection = DeviceConnection(
            deviceId = "peer",
            direction = ConnectionDirection.Incoming,
            readChannel = input,
            writeChannel = ByteChannel(),
            monotonicClock = { 0L },
        )
        val device = object : BaseRemoteDevice() {
            override val deviceId = "peer"
            override val deviceName = "Peer"
        }
        val firstHandlerStarted = CompletableDeferred<Unit>()
        val releaseFirstHandler = CompletableDeferred<Unit>()
        val connectionClosed = CompletableDeferred<Unit>()
        val activeHandlers = AtomicInteger()
        val maxConcurrentHandlers = AtomicInteger()
        val handled = mutableListOf<String>()

        connection.startListening(
            getDevice = { device },
            onMessage = { _, message ->
                val active = activeHandlers.incrementAndGet()
                maxConcurrentHandlers.updateAndGet { current -> maxOf(current, active) }
                if (handled.isEmpty()) {
                    firstHandlerStarted.complete(Unit)
                    releaseFirstHandler.await()
                }
                handled += (message as ClipboardInfo).content
                activeHandlers.decrementAndGet()
            },
            onClose = { connectionClosed.complete(Unit) },
        )

        val writer = async {
            repeat(50) { index ->
                val frame = requireNotNull(
                    MessageSerializer.serialize(ClipboardInfo("text/plain", index.toString())),
                )
                input.writeStringUtf8("$frame\n")
                input.flush()
            }
        }

        withTimeout(2_000) { firstHandlerStarted.await() }
        delay(100)
        assertEquals(1, maxConcurrentHandlers.get())

        releaseFirstHandler.complete(Unit)
        withTimeout(5_000) { writer.await() }
        input.close()
        withTimeout(5_000) { connectionClosed.await() }

        assertEquals((0 until 50).map(Int::toString), handled)
        assertEquals(1, maxConcurrentHandlers.get())
        connection.close()
    }

    @Test
    fun `heartbeat bypasses a slow application handler`() = runBlocking {
        val input = ByteChannel()
        val connection = DeviceConnection(
            deviceId = "peer",
            direction = ConnectionDirection.Incoming,
            readChannel = input,
            writeChannel = ByteChannel(),
            monotonicClock = { 0L },
        )
        val device = object : BaseRemoteDevice() {
            override val deviceId = "peer"
            override val deviceName = "Peer"
        }
        val applicationHandlerStarted = CompletableDeferred<Unit>()
        val releaseApplicationHandler = CompletableDeferred<Unit>()
        val heartbeatHandled = CompletableDeferred<Unit>()

        connection.startListening(
            getDevice = { device },
            onMessage = { _, message ->
                when (message) {
                    is ClipboardInfo -> {
                        applicationHandlerStarted.complete(Unit)
                        releaseApplicationHandler.await()
                    }
                    is ConnectionHeartbeat -> heartbeatHandled.complete(Unit)
                    else -> Unit
                }
            },
            onClose = {},
        )

        val applicationFrame = requireNotNull(
            MessageSerializer.serialize(ClipboardInfo("text/plain", "blocked")),
        )
        val heartbeatFrame = requireNotNull(MessageSerializer.serialize(ConnectionHeartbeat))
        input.writeStringUtf8("$applicationFrame\n$heartbeatFrame\n")
        input.flush()

        withTimeout(2_000) { applicationHandlerStarted.await() }
        withTimeout(2_000) { heartbeatHandled.await() }

        releaseApplicationHandler.complete(Unit)
        input.close()
        connection.close()
    }
}
