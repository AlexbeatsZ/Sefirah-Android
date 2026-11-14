package sefirah.transfer

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readByte
import kotlinx.coroutines.withTimeout
import java.io.IOException

/** New peers delimit controls with LF. Legacy peers send the same fixed ASCII tokens without LF. */
internal object TransferProtocol {
    suspend fun expect(channel: ByteReadChannel, expected: String) = withTimeout(30_000) {
        var first = channel.readByte().toInt().toChar()
        while (first == '\n' || first == '\r') first = channel.readByte().toInt().toChar()
        if (first != expected.first()) throw IOException("Invalid transfer control")
        for (character in expected.drop(1)) {
            if (channel.readByte().toInt().toChar() != character) throw IOException("Invalid transfer control")
        }
    }
}
