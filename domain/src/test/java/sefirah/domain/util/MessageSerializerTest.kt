package sefirah.domain.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sefirah.domain.model.BluetoothHandoffCommand
import sefirah.domain.model.BluetoothDisconnectRequest
import sefirah.domain.model.BluetoothHandoffRefreshRequest
import sefirah.domain.model.BluetoothHandoffConfiguration
import sefirah.domain.model.BluetoothHeadsetVisibilityRequest
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

    @Test
    fun `bluetooth UI requests round trip through socket serializer`() {
        val messages = listOf(
            BluetoothDisconnectRequest("operation-2", "headset-1", "phone-1"),
            BluetoothHandoffRefreshRequest,
            BluetoothHeadsetVisibilityRequest("headset-1", false),
        )

        messages.forEach { message ->
            val encoded = requireNotNull(MessageSerializer.serialize(message))
            assertEquals(message, MessageSerializer.deserialize(encoded))
        }
    }

    @Test
    fun `legacy headset descriptor defaults to visible with no endpoint list`() {
        val encoded = """{"type":"BluetoothHandoffConfiguration","headsets":[{"id":"headset-1","displayName":"QCY","activeEndpointId":null}],"endpoints":[]}"""

        val configuration = MessageSerializer.deserialize(encoded) as BluetoothHandoffConfiguration
        val descriptor = configuration.headsets.single()

        assertTrue(descriptor.isVisible)
        assertTrue(descriptor.endpointIds.isEmpty())
    }
}
