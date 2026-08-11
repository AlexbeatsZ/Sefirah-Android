package sefirah.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceBindingGateTest {
    @Test
    fun repeatedStartsBindOnlyOnceAndRepeatedStopsUnbindOnlyOnce() {
        val gate = ServiceBindingGate()
        var bindCount = 0
        var unbindCount = 0

        repeat(3) {
            gate.bindOnce {
                bindCount += 1
                true
            }
        }
        repeat(3) {
            gate.unbindOnce { unbindCount += 1 }
        }

        assertEquals(1, bindCount)
        assertEquals(1, unbindCount)
    }

    @Test
    fun falseBindResultLeavesGateRetryable() {
        val gate = ServiceBindingGate()
        var bindCount = 0

        assertFalse(gate.bindOnce {
            bindCount += 1
            false
        })
        assertTrue(gate.bindOnce {
            bindCount += 1
            true
        })

        assertEquals(2, bindCount)
    }

    @Test
    fun throwingBindLeavesGateRetryable() {
        val gate = ServiceBindingGate()
        var bindCount = 0

        assertThrows(SecurityException::class.java) {
            gate.bindOnce {
                bindCount += 1
                throw SecurityException("denied")
            }
        }
        assertTrue(gate.bindOnce {
            bindCount += 1
            true
        })

        assertEquals(2, bindCount)
    }

    @Test
    fun explicitUnbindAllowsLaterRebind() {
        val gate = ServiceBindingGate()
        var bindCount = 0

        assertTrue(gate.bindOnce {
            bindCount += 1
            true
        })
        assertTrue(gate.unbindOnce {})
        assertTrue(gate.bindOnce {
            bindCount += 1
            true
        })

        assertEquals(2, bindCount)
    }
}
