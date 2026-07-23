package sefirah.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionCollisionPolicyTest {
    @Test
    fun lowerDeviceIdPrefersOutgoingConnection() {
        assertEquals(ConnectionDirection.Outgoing, ConnectionCollisionPolicy.preferredDirection("a", "b"))
        assertEquals(ConnectionDirection.Incoming, ConnectionCollisionPolicy.preferredDirection("b", "a"))
    }

    @Test
    fun acceptsOnlyPreferredCandidateWhenDuplicateExists() {
        assertTrue(ConnectionCollisionPolicy.shouldAcceptCandidate("a", "b", null, ConnectionDirection.Incoming))
        assertTrue(
            ConnectionCollisionPolicy.shouldAcceptCandidate(
                "a",
                "b",
                ConnectionDirection.Incoming,
                ConnectionDirection.Incoming,
            )
        )
        assertTrue(
            ConnectionCollisionPolicy.shouldAcceptCandidate(
                "a",
                "b",
                ConnectionDirection.Incoming,
                ConnectionDirection.Outgoing,
            )
        )
        assertFalse(
            ConnectionCollisionPolicy.shouldAcceptCandidate(
                "a",
                "b",
                ConnectionDirection.Outgoing,
                ConnectionDirection.Incoming,
            )
        )
    }
}
