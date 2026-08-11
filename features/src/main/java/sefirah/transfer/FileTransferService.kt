package sefirah.transfer

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import sefirah.clipboard.ClipboardHandler
import sefirah.domain.interfaces.DeviceManager
import sefirah.domain.interfaces.NetworkManager
import sefirah.domain.interfaces.PreferencesRepository
import sefirah.domain.interfaces.SocketFactory
import sefirah.domain.model.FileTransferInfo
import sefirah.domain.model.ServerInfo
import sefirah.transfer.util.getFileMetadata
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileTransferService @Inject constructor(
    private val context: Context,
    private val socketFactory: SocketFactory,
    private val deviceManager: DeviceManager,
    private val preferencesRepository: PreferencesRepository,
    private val notifications: TransferNotificationHelper,
    private val networkManager: NetworkManager,
    private val clipboardHandler: ClipboardHandler
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeTransfers = ConcurrentHashMap<String, ActiveTransfer>()
    private val transferSlots = Semaphore(MAX_CONCURRENT_TRANSFERS)

    fun sendFiles(deviceId: String, fileUris: List<Uri>) {
        if (!transferSlots.tryAcquire()) {
            Log.w(TAG, "Rejecting send request: transfer limit reached")
            return
        }
        val transferId = UUID.randomUUID().toString()

        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val device = deviceManager.getPairedDevice(deviceId)
                    ?: throw IOException("Device $deviceId not found")

                val filesMetadata = fileUris.map { getFileMetadata(context, it) }

                val serverSocket = socketFactory.tcpServerSocket(PORT_RANGE, device.certificate)
                    ?: throw IOException("Failed to create server socket")

                val serverInfo = ServerInfo(serverSocket.localPort)

                val handler = SendFileHandler(
                    context = context,
                    transferId = transferId,
                    serverSocket = serverSocket,
                    fileUris = fileUris,
                    filesMetadata = filesMetadata,
                    deviceName = device.deviceName,
                    notifications = notifications
                )

                val announced = networkManager.sendMessageAwait(
                    deviceId,
                    FileTransferInfo(files = filesMetadata, serverInfo = serverInfo),
                )
                if (!announced) throw IOException("Failed to announce file transfer")
                handler.send()
            } catch (e: CancellationException) {
                Log.d(TAG, "Transfer $transferId cancelled")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Send files failed", e)
            }
        }
        job.invokeOnCompletion {
            activeTransfers.remove(transferId)
            transferSlots.release()
        }
        activeTransfers[transferId] = ActiveTransfer(deviceId, job)
        job.start()
    }

    fun receiveFiles(deviceId: String, transfer: FileTransferInfo) {
        if (!transferSlots.tryAcquire()) {
            Log.w(TAG, "Rejecting receive request: transfer limit reached")
            return
        }
        val transferId = UUID.randomUUID().toString()

        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val device = deviceManager.getPairedDevice(deviceId)
                    ?: throw IOException("Device $deviceId not found")

                val address = device.address
                    ?: throw IOException("No connected address for device $deviceId")

                val clientSocket = socketFactory.tcpClientSocket(address, transfer.serverInfo.port, device.certificate)
                    ?: throw IOException("Failed to establish connection")

                val handler = ReceiveFileHandler(
                    context = context,
                    transferId = transferId,
                    clientSocket = clientSocket,
                    files = transfer.files,
                    deviceName = device.deviceName,
                    preferencesRepository = if (transfer.isClipboard) null else preferencesRepository,
                    notifications = if (transfer.isClipboard) null else notifications
                )

                val fileUri = handler.receive()
                fileUri?.let { clipboardHandler.setClipboardUri(it) }
            } catch (e: CancellationException) {
                Log.d(TAG, "Transfer $transferId cancelled")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Receive files failed", e)
            }
        }
        job.invokeOnCompletion {
            activeTransfers.remove(transferId)
            transferSlots.release()
        }
        activeTransfers[transferId] = ActiveTransfer(deviceId, job)
        job.start()
    }

    fun cancelTransfer(transferId: String) {
        activeTransfers[transferId]?.job?.cancel()
        notifications.cancel(transferId)
    }

    fun cancelTransfersForDevice(deviceId: String) {
        activeTransfers.values
            .filter { it.deviceId == deviceId }
            .forEach { it.job.cancel() }
    }

    fun shutdown() {
        activeTransfers.values.forEach { it.job.cancel() }
    }

    private data class ActiveTransfer(val deviceId: String, val job: Job)

    companion object {
        private const val TAG = "FileTransferManager"
        val PORT_RANGE = 5152..5169
        const val ACTION_CANCEL_TRANSFER = "CANCEL_TRANSFER"
        const val EXTRA_TRANSFER_ID = "extra_transfer_id"
        private const val MAX_CONCURRENT_TRANSFERS = 4
    }
}
