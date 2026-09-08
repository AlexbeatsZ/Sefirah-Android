package sefirah.privileged

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeBindingPolicyTest {
    @Test
    fun `pollers cannot create another bind while one is pending`() {
        val policy = BridgeBindingPolicy()
        assertTrue(policy.begin(0))
        for (now in 1L..60_000L) assertFalse(policy.begin(now))
    }

    @Test
    fun `timeout backs off then allows one retry`() {
        val policy = BridgeBindingPolicy()
        assertTrue(policy.begin(0))
        policy.failed(6_000)
        assertFalse(policy.begin(10_999))
        assertTrue(policy.begin(11_000))
        assertFalse(policy.begin(11_001))
    }

    @Test
    fun `daemon restart clears the old binding attempt`() {
        val policy = BridgeBindingPolicy()
        assertTrue(policy.begin(0))
        policy.failed(6_000)
        policy.reset()
        assertTrue(policy.begin(6_001))
    }
}
