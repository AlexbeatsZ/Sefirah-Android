package sefirah.domain.model

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OutgoingMessageMailboxTest {
    @Test
    fun `mailbox rejects frames beyond its fixed capacity and preserves order`() = runBlocking {
        val mailbox = OutgoingMessageMailbox(capacity = 2, maxQueuedChars = 32)
        val first = "first"
        val second = "second"
        val overflow = "overflow"

        assertTrue(mailbox.trySend(first))
        assertTrue(mailbox.trySend(second))
        assertFalse(mailbox.trySend(overflow))
        val firstOutgoing = mailbox.messages.receive()
        assertEquals(first, firstOutgoing.frame)
        firstOutgoing.complete(true)
        val secondOutgoing = mailbox.messages.receive()
        assertEquals(second, secondOutgoing.frame)
        secondOutgoing.complete(true)
    }

    @Test
    fun `waiting sender receives writer acknowledgement`() = runBlocking {
        val mailbox = OutgoingMessageMailbox(capacity = 1, maxQueuedChars = 32)
        val message = "flush me"
        val delivered = async { mailbox.sendAndAwait(message) }

        yield()
        val outgoing = mailbox.messages.receive()
        assertEquals(message, outgoing.frame)
        outgoing.complete(true)

        assertTrue(delivered.await())
    }

    @Test
    fun `closing mailbox fails pending delivery without draining it through a writer`() = runBlocking {
        val mailbox = OutgoingMessageMailbox(capacity = 1, maxQueuedChars = 32)
        val delivered = async {
            mailbox.sendAndAwait("pending")
        }

        yield()
        mailbox.closeAndFailPending()

        assertFalse(delivered.await())
        assertFalse(mailbox.trySend("heartbeat"))
    }

    @Test
    fun `mailbox enforces aggregate character budget and releases it after delivery`() = runBlocking {
        val mailbox = OutgoingMessageMailbox(capacity = 4, maxQueuedChars = 10)

        assertTrue(mailbox.trySend("123456"))
        assertFalse(mailbox.trySend("78901"))

        mailbox.messages.receive().complete(true)
        assertTrue(mailbox.trySend("78901"))
    }
}
