package sefirah.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
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
}
