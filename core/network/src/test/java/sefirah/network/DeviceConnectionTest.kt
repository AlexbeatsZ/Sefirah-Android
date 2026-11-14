package sefirah.network

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sefirah.domain.model.BatteryState
import sefirah.domain.model.Disconnect
import sefirah.domain.model.PairedDevice
import sefirah.domain.util.MessageSerializer

class DeviceConnectionTest {
    private val peer = PairedDevice("peer", "Peer", byteArrayOf(1))

    @Test
    fun `handlers complete in wire order`() = runBlocking {
        val input = ByteChannel(autoFlush = true)
        val output = ByteChannel(autoFlush = true)
        val connection = DeviceConnection(peer.deviceId, readChannel = input, writeChannel = output)
        val done = CompletableDeferred<Unit>()
        val values = mutableListOf<Int>()
        try {
            connection.startListening({ peer }, { _, message ->
                val level = (message as BatteryState).batteryLevel
                if (level == 1) delay(50)
                values += level
                if (values.size == 2) done.complete(Unit)
            }, {})
            input.writeStringUtf8(MessageSerializer.serialize(BatteryState(1, false))!! + "\n")
            input.writeStringUtf8(MessageSerializer.serialize(BatteryState(2, false))!! + "\n")
            withTimeout(3000) { done.await() }
            assertEquals(listOf(1, 2), values)
        } finally { connection.close() }
    }

    @Test
    fun `end of stream reports one closure`() = runBlocking {
        val input = ByteChannel(autoFlush = true)
        val connection = DeviceConnection(peer.deviceId, readChannel = input, writeChannel = ByteChannel(true))
        val closed = CompletableDeferred<Unit>()
        try {
            connection.startListening({ peer }, { _, _ -> }, { closed.complete(Unit) })
            input.flushAndClose()
            withTimeout(3000) { closed.await() }
            assertTrue(closed.isCompleted)
        } finally { connection.close() }
    }

    @Test
    fun `graceful disconnect drains previously queued messages`() = runBlocking {
        val output = ByteChannel(autoFlush = true)
        val connection = DeviceConnection(peer.deviceId, writeChannel = output)
        try {
            assertTrue(connection.sendMessage(BatteryState(5, false)))
            withTimeout(3000) { connection.sendDisconnect() }
            assertEquals(BatteryState(5, false), MessageSerializer.deserialize(output.readUTF8Line()!!))
            assertEquals(Disconnect, MessageSerializer.deserialize(output.readUTF8Line()!!))
            assertFalse(connection.sendMessage(BatteryState(6, false)))
        } finally { connection.close() }
    }

    @Test
    fun `handler can close its owning connection`() = runBlocking {
        val input = ByteChannel(autoFlush = true)
        val connection = DeviceConnection(peer.deviceId, readChannel = input, writeChannel = ByteChannel(true))
        val done = CompletableDeferred<Unit>()
        connection.startListening({ peer }, { _, _ ->
            connection.close()
            done.complete(Unit)
        }, {})
        input.writeStringUtf8(MessageSerializer.serialize(Disconnect)!! + "\n")
        withTimeout(3000) { done.await() }
        assertFalse(connection.sendMessage(Disconnect))
    }
}
