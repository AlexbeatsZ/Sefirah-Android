package sefirah.transfer

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import kotlin.random.Random

class PositionalFdInputStreamTest {
    @Test
    fun streamsSharingOneDescriptorEachReadWholeFile() {
        val bytes = Random(7).nextBytes(3 * 1024 * 1024 + 17)
        val file = File.createTempFile("clipboard", ".png").apply { deleteOnExit(); writeBytes(bytes) }
        RandomAccessFile(file, "r").use { raf ->
            val first = PositionalFdInputStream(raf.fd)
            val second = PositionalFdInputStream(raf.fd)
            val a = java.io.ByteArrayOutputStream()
            val b = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(65536)
            var doneA = false
            var doneB = false
            while (!doneA || !doneB) {
                if (!doneA) first.read(buffer).let { if (it == -1) doneA = true else a.write(buffer, 0, it) }
                if (!doneB) second.read(buffer).let { if (it == -1) doneB = true else b.write(buffer, 0, it) }
            }
            assertArrayEquals(bytes, a.toByteArray())
            assertArrayEquals(bytes, b.toByteArray())
        }
    }
}
