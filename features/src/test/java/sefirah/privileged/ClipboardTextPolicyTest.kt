package sefirah.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClipboardTextPolicyTest {
    @Test
    fun `rejects missing and empty text`() {
        assertNull(ClipboardTextPolicy.acceptedText(null, isSensitive = false))
        assertNull(ClipboardTextPolicy.acceptedText("", isSensitive = false))
    }

    @Test
    fun `accepts sensitive text such as verification codes`() {
        assertEquals("040006", ClipboardTextPolicy.acceptedText("040006", isSensitive = true))
    }

    @Test
    fun `accepts text at the size limit without changing it`() {
        val text = "x".repeat(ClipboardTextPolicy.MAX_TEXT_LENGTH)

        assertEquals(text, ClipboardTextPolicy.acceptedText(text, isSensitive = false))
    }

    @Test
    fun `rejects text over the size limit`() {
        val text = "x".repeat(ClipboardTextPolicy.MAX_TEXT_LENGTH + 1)

        assertNull(ClipboardTextPolicy.acceptedText(text, isSensitive = false))
    }
}
