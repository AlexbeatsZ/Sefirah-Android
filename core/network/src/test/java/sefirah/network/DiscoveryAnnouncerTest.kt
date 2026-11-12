package sefirah.network

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sefirah.domain.model.AddressEntry
import sefirah.domain.model.ConnectionState
import sefirah.domain.model.PairedDevice

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoveryAnnouncerTest {
    private fun device(id: String, state: ConnectionState = ConnectionState.Disconnected()) = PairedDevice(
        deviceId = id,
        deviceName = id,
        certificate = byteArrayOf(1),
        addresses = listOf(AddressEntry("192.0.2.1")),
        connectionState = state,
    )

    @Test
    fun `announces on the full schedule while a peer stays disconnected`() = runTest {
        val sent = mutableListOf<Long>()
        val announcer = DiscoveryAnnouncer(backgroundScope, { listOf(device("mac")) }) { sent += currentTime }
        announcer.start()
        advanceTimeBy(DiscoveryAnnouncer.SCHEDULE_MS.last() + 1)
        runCurrent()
        assertEquals(DiscoveryAnnouncer.SCHEDULE_MS.toList(), sent)
    }

    @Test
    fun `stops once every peer is connected`() = runTest {
        var devices = listOf(device("mac"), device("rog"))
        val sent = mutableListOf<Long>()
        val announcer = DiscoveryAnnouncer(backgroundScope, { devices }) { sent += currentTime }
        announcer.start()
        advanceTimeBy(2_001)
        runCurrent()
        devices = devices.map { it.copy(connectionState = ConnectionState.Connected) }
        advanceTimeBy(200_000)
        runCurrent()
        assertEquals(listOf(0L, 2_000L), sent)
    }

    @Test
    fun `restart replaces the running schedule`() = runTest {
        val sent = mutableListOf<Long>()
        val announcer = DiscoveryAnnouncer(backgroundScope, { emptyList() }) { sent += currentTime }
        announcer.start()
        advanceTimeBy(3_000)
        announcer.start()
        runCurrent()
        assertEquals(listOf(0L, 2_000L, 3_000L), sent)
        announcer.cancel()
        advanceTimeBy(200_000)
        runCurrent()
        assertEquals(3, sent.size)
    }

    @Test
    fun `forced disconnects and loading state are respected`() {
        assertTrue(DiscoveryAnnouncer.needsAnnouncement(emptyList()))
        assertFalse(DiscoveryAnnouncer.needsAnnouncement(listOf(device("a", ConnectionState.Disconnected(true)))))
        assertFalse(DiscoveryAnnouncer.needsAnnouncement(listOf(device("a", ConnectionState.Connected))))
        assertTrue(DiscoveryAnnouncer.needsAnnouncement(listOf(device("a", ConnectionState.Connected), device("b"))))
    }
}
