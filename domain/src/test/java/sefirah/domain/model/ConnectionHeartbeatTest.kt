package sefirah.domain.model

import org.junit.Assert.assertSame
import org.junit.Test
import sefirah.domain.util.MessageSerializer

class ConnectionHeartbeatTest {
    @Test
    fun heartbeatRoundTripsAsDedicatedControlMessage() {
        val encoded = requireNotNull(MessageSerializer.serialize(ConnectionHeartbeat))

        assertSame(ConnectionHeartbeat, MessageSerializer.deserialize(encoded))
    }
}
