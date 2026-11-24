package sefirah.transfer

import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * Reads a regular file with pread so streams over dup()'d descriptors do not share a file offset.
 * Concurrent clipboard sends to several peers each get the whole file.
 */
internal class PositionalFdInputStream(fd: FileDescriptor) : InputStream() {
    private val channel = FileInputStream(fd).channel
    private var position = 0L

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val count = channel.read(ByteBuffer.wrap(b, off, len), position)
        if (count > 0) position += count
        return count
    }

    override fun skip(n: Long): Long {
        if (n <= 0) return 0
        val skipped = minOf(n, (channel.size() - position).coerceAtLeast(0))
        position += skipped
        return skipped
    }
}
