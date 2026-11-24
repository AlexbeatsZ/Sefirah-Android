package sefirah.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sefirah.domain.model.Authentication
import sefirah.domain.util.MessageSerializer

class ProtocolContractTest {
    @Test
    fun `shared desktop and Android samples round trip through the production serializer`() {
        val samples = javaClass.getResourceAsStream("/protocol-messages.json")!!.bufferedReader().use { it.readText() }
        Json.parseToJsonElement(samples).jsonArray.forEach { sample ->
            val message = MessageSerializer.deserialize(sample.toString())
            assertNotNull(sample.toString(), message)
            val roundTrip = MessageSerializer.deserialize(MessageSerializer.serialize(message!!)!!)
            assertEquals(message::class, roundTrip!!::class)
            assertEquals(message, roundTrip)
        }
    }

    @Test
    fun `only an explicit connect announces userInitiated`() {
        val explicit = MessageSerializer.deserialize(
            """{"type":"Authentication","deviceId":"p","deviceName":"p","publicKey":"k","model":"m","userInitiated":true}"""
        ) as Authentication
        assertTrue(explicit.userInitiated)
        val automatic = MessageSerializer.serialize(Authentication("p", "p", "k", "m", protocolVersion = 1))!!
        assertFalse(automatic, automatic.contains("userInitiated"))
        val legacy = MessageSerializer.deserialize(automatic) as Authentication
        assertFalse(legacy.userInitiated)
    }
}
