package sefirah.clipboard

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ClipboardEventTrackerTest {
    @Test
    fun `remote clipboard write is not echoed back`() {
        val tracker = ClipboardEventTracker()

        tracker.recordRemoteText("received from desktop")

        assertNull(tracker.recordLocalText("received from desktop"))
    }

    @Test
    fun `new local content creates exactly one event`() {
        val tracker = ClipboardEventTracker()

        assertNotNull(tracker.recordLocalText("copied on phone"))
        assertNull(tracker.recordLocalText("copied on phone"))
    }
}
