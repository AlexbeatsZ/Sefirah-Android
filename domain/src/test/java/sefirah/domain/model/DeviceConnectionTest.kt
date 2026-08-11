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
import org.junit.Test
import sefirah.domain.util.MessageSerializer
import java.util.concurrent.atomic.AtomicInteger

class DeviceConnectionTest {
    @Test
    fun `writer failure fails in-flight acknowledgement and closes connection`() = runBlocking {
        val connection = DeviceConnection(
            deviceId = "peer",
            direction = ConnectionDirection.Outgoing,
        )

        assertFalse(withTimeout(2_000) { connection.sendMessageAndAwait(ConnectionHeartbeat) })
        assertFalse(connection.sendMessage(ConnectionHeartbeat))
        connection.close()
    }

    @Test
    fun `reader awaits each handler and preserves per-device order`() = runBlocking {
        val input = ByteChannel()
        val connection = DeviceConnection(
            deviceId = "peer",
            direction = ConnectionDirection.Incoming,
            readChannel = input,
            writeChannel = ByteChannel(),
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
            repeat(100) { index ->
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

        assertEquals((0 until 100).map(Int::toString), handled)
        assertEquals(1, maxConcurrentHandlers.get())
        connection.close()
    }
}
