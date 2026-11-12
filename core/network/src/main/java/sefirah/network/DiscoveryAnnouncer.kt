package sefirah.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sefirah.domain.model.PairedDevice

/**
 * Repeats the UDP announcement for a short while after the service starts or Wi-Fi comes up.
 *
 * A single announcement at boot is easily lost: paired addresses are still loading from the
 * database, Wi-Fi may not be associated yet, and peers whose mDNS cannot see this phone (macOS)
 * only dial back when they receive this packet. Stops once every non-forced peer is connected.
 */
internal class DiscoveryAnnouncer(
    private val scope: CoroutineScope,
    private val pairedDevices: () -> List<PairedDevice>,
    private val announce: () -> Unit,
) {
    private var job: Job? = null

    @Synchronized
    fun start() {
        job?.cancel()
        job = scope.launch {
            for (offset in SCHEDULE_MS.indices) {
                delay(if (offset == 0) SCHEDULE_MS[0] else SCHEDULE_MS[offset] - SCHEDULE_MS[offset - 1])
                if (offset > 0 && !needsAnnouncement(pairedDevices())) break
                announce()
            }
        }
    }

    @Synchronized
    fun cancel() {
        job?.cancel()
        job = null
    }

    companion object {
        /** Offsets from the trigger; the first packet goes out immediately. */
        internal val SCHEDULE_MS = longArrayOf(0, 2_000, 5_000, 10_000, 20_000, 40_000, 70_000, 120_000)

        /** An empty list means the paired devices have not been loaded yet, so keep announcing. */
        internal fun needsAnnouncement(devices: List<PairedDevice>): Boolean =
            devices.isEmpty() || devices.any {
                !it.connectionState.isConnectedOrConnecting && !it.connectionState.isForcedDisconnect
            }
    }
}
