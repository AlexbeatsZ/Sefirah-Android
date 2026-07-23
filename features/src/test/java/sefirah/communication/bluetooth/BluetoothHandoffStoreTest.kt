package sefirah.communication.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Test
import sefirah.domain.model.BluetoothEndpointDescriptor
import sefirah.domain.model.BluetoothHandoffConfiguration

class BluetoothHandoffStoreTest {
    @Test
    fun `older configuration cannot replace newer snapshot`() {
        val store = BluetoothHandoffStore()
        val newest = BluetoothHandoffConfiguration(
            endpoints = listOf(BluetoothEndpointDescriptor("new", "New state")),
            revision = 200,
        )
        val stale = BluetoothHandoffConfiguration(
            endpoints = listOf(BluetoothEndpointDescriptor("stale", "Stale state")),
            revision = 100,
        )

        store.updateConfiguration(newest)
        store.updateConfiguration(stale)

        assertEquals(newest, store.configuration.value)
    }
}
