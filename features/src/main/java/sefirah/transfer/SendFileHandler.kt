package sefirah.transfer

import android.content.Context
import android.util.Log
import io.ktor.utils.io.cancel
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import io.ktor.utils.io.streams.asByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import sefirah.common.R
import sefirah.common.notifications.NotificationCenter
import java.io.IOException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * Handles sending files to a remote device.
 */
class SendFileHandler(
    private val context: Context,
    transferId: String,
    private val serverSocket: SSLServerSocket,
    private val files: List<TransferSource>,
    private val deviceName: String,
    notificationCenter: NotificationCenter?,
) {
    private val totalBytes: Long = files.sumOf { it.metadata.fileSize }
    private var totalBytesTransferred: Long = 0

    private val notification: TransferNotification? =
        notificationCenter?.let { TransferNotification(context, transferId, it) }

    suspend fun send() {
        var sslSocket: SSLSocket? = null
        try {
            notification?.let {
                val title = context.getString(
                    R.string.notification_sending_title_format,
                    context.getString(R.string.notification_sending_action),
                    files.size,
                    if (files.size == 1) {
                        context.getString(R.string.notification_file)
                    } else {
                        context.getString(R.string.notification_files)
                    },
                    context.getString(R.string.notification_to),
                    deviceName,
                )
                it.showPreparing(title)
            }

            serverSocket.soTimeout = 30_000
            sslSocket = withContext(Dispatchers.IO) {
                serverSocket.accept() as? SSLSocket
            } ?: throw IOException("Failed to accept SSL connection")

            sslSocket.soTimeout = 30_000
            sslSocket.startHandshake()
            val readChannel = sslSocket.inputStream.toByteReadChannel()
            val writeChannel = sslSocket.outputStream.asByteWriteChannel()

            files.forEachIndexed { index, file ->
                currentCoroutineContext().ensureActive()

                TransferProtocol.expect(readChannel, TRANSFER_START_MESSAGE)

                val metadata = file.metadata

                file.open().use { inputStream ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int

                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        currentCoroutineContext().ensureActive()

                        writeChannel.writeFully(buffer, 0, bytesRead)
                        writeChannel.flush()
                        totalBytesTransferred += bytesRead

                        notification?.updateProgress(
                            bytesTransferred = totalBytesTransferred,
                            totalBytes = totalBytes,
                            fileName = metadata.fileName,
                            fileIndex = index + 1,
                            fileCount = files.size,
                        )
                    }
                }

                TransferProtocol.expect(readChannel, TRANSFER_COMPLETE_MESSAGE)
            }

            notification?.showCompleted(files.size)

            writeChannel.flushAndClose()
            readChannel.cancel()
        } catch (e: CancellationException) {
            notification?.cancel()
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Send failed", e)
            notification?.showError(e.message ?: "Transfer failed")
            throw e
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                try { sslSocket?.close() } finally { serverSocket.close() }
            }
        }
    }

    companion object {
        private const val TAG = "SendFileHandler"
        private const val BUFFER_SIZE = 131072 * 4 // 512 KB
        const val TRANSFER_START_MESSAGE = "start"
        const val TRANSFER_COMPLETE_MESSAGE = "complete"
    }
}
