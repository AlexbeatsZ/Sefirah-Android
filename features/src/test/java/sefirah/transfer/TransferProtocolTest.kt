package sefirah.transfer

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.IOException

class TransferProtocolTest {
    @Test
    fun `reads legacy raw controls and new line delimited controls`() = runBlocking {
        for (wire in listOf("startcomplete", "start\ncomplete\n", "start\r\ncomplete\r\n")) {
            val channel = ByteChannel(autoFlush = true)
            channel.writeStringUtf8(wire)
            TransferProtocol.expect(channel, "start")
            TransferProtocol.expect(channel, "complete")
            channel.cancel(IOException("Test finished"))
        }
    }

    @Test(expected = IOException::class)
    fun `rejects invalid controls`() = runBlocking {
        val channel = ByteChannel(autoFlush = true)
        channel.writeStringUtf8("stop\n")
        TransferProtocol.expect(channel, "start")
    }
}
