package sefirah.domain.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sefirah.domain.model.BluetoothHandoffCommand
import sefirah.domain.model.DeviceInfo

class MessageSerializerTest {
    @Test
    fun `handoff command round trips through socket serializer`() {
        val message = BluetoothHandoffCommand(
            operationId = "operation-1",
            action = "setRadio",
            enabled = false,
        )

        val encoded = requireNotNull(MessageSerializer.serialize(message))
        val decoded = MessageSerializer.deserialize(encoded)

        assertEquals(message, decoded)
    }

    @Test
    fun `legacy device info without capabilities remains compatible`() {
        val encoded = """{"type":"DeviceInfo","deviceName":"Desktop","avatar":null,"phoneNumbers":[]}"""

        val decoded = MessageSerializer.deserialize(encoded) as DeviceInfo

        assertTrue(decoded.capabilities.isEmpty())
    }
}
