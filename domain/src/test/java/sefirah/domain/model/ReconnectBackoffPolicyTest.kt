package sefirah.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ReconnectBackoffPolicyTest {
    @Test
    fun `failure delay grows exponentially and is capped`() {
        assertEquals(5_000L, ReconnectBackoffPolicy.delayAfterFailure(1))
        assertEquals(10_000L, ReconnectBackoffPolicy.delayAfterFailure(2))
        assertEquals(20_000L, ReconnectBackoffPolicy.delayAfterFailure(3))
        assertEquals(40_000L, ReconnectBackoffPolicy.delayAfterFailure(4))
        assertEquals(60_000L, ReconnectBackoffPolicy.delayAfterFailure(5))
        assertEquals(60_000L, ReconnectBackoffPolicy.delayAfterFailure(30))
    }

    @Test
    fun `invalid failure count still uses the base delay`() {
        assertEquals(5_000L, ReconnectBackoffPolicy.delayAfterFailure(0))
        assertEquals(5_000L, ReconnectBackoffPolicy.delayAfterFailure(-1))
    }
}
