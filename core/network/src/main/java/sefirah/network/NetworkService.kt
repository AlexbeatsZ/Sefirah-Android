package sefirah.network

import android.annotation.SuppressLint
import android.app.Notification
import android.app.Service
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.streams.asByteWriteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import sefirah.actions.ActionFeature
import sefirah.apps.AppListHandler
import sefirah.clipboard.ClipboardHandler
import sefirah.clipboard.ClipboardEventTracker
import sefirah.common.notifications.AppNotifications
import sefirah.common.notifications.NotificationCenter
import sefirah.common.util.drawableToBase64Compressed
import sefirah.common.util.isContactsPermissionGranted
import sefirah.communication.bluetooth.BluetoothPairingHandler
import sefirah.communication.bluetooth.BluetoothHandoffHandler
import sefirah.communication.bluetooth.BluetoothHandoffStore
import sefirah.communication.sms.SmsFeature
import sefirah.communication.utils.ContactsHelper
import sefirah.communication.utils.TelephonyHelper
import sefirah.database.AppRepository
import sefirah.domain.interfaces.DeviceManager
import sefirah.domain.interfaces.PreferencesRepository
import sefirah.domain.interfaces.SocketFactory
import sefirah.FeatureManager
import sefirah.domain.model.AddressEntry
import sefirah.domain.model.Authentication
import sefirah.domain.model.BaseRemoteDevice
import sefirah.domain.model.ClipboardInfo
import sefirah.domain.model.ConnectionAck
import sefirah.domain.model.ConnectionHeartbeat
import sefirah.domain.model.ConnectionDetails
import sefirah.domain.model.ConnectionCollisionPolicy
import sefirah.domain.model.ConnectionDirection
import sefirah.domain.model.ConnectionState
import sefirah.domain.model.DeviceConnection
import sefirah.domain.model.DeviceInfo
import sefirah.domain.model.Disconnect
import sefirah.domain.model.DiscoveredDevice
import sefirah.domain.model.PairMessage
import sefirah.domain.model.PairedDevice
import sefirah.domain.model.PendingDeviceApproval
import sefirah.domain.model.ProtocolCapabilities
import sefirah.domain.model.ProtocolLimits
import sefirah.domain.model.ReconnectBackoffPolicy
import sefirah.domain.model.SocketMessage
import sefirah.domain.util.MessageSerializer
import sefirah.network.extensions.cancelPairingVerificationNotification
import sefirah.network.extensions.handleMessage
import sefirah.network.extensions.setNotification
import sefirah.network.extensions.showPairingVerificationNotification
import sefirah.network.util.SslHelper
import sefirah.notification.NotificationFeature
import sefirah.privileged.PrivilegedBridgeManager
import sefirah.privileged.PrivilegedBridgeStatus
import sefirah.media.PlaybackFeature
import sefirah.media.RemotePlaybackFeature
import sefirah.status.DeviceControlHandler
import sefirah.status.RemoteDeviceStatusFeature
import sefirah.transfer.FileTransferService
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.net.ssl.SSLSocket
import kotlin.properties.Delegates
import kotlin.time.Duration.Companion.milliseconds

@AndroidEntryPoint
class NetworkService : Service() {
    @Inject lateinit var socketFactory: SocketFactory

    @Inject lateinit var appRepository: AppRepository

    @Inject lateinit var notificationFeature: NotificationFeature

    @Inject lateinit var notificationCenter: NotificationCenter

    @Inject lateinit var clipboardHandler: ClipboardHandler

    @Inject lateinit var clipboardEventTracker: ClipboardEventTracker

    @Inject lateinit var privilegedBridgeManager: PrivilegedBridgeManager

    @Inject lateinit var networkDiscovery: NetworkDiscovery

    @Inject lateinit var remotePlaybackFeature: RemotePlaybackFeature

    @Inject lateinit var playbackFeature: PlaybackFeature

    @Inject lateinit var preferencesRepository: PreferencesRepository

    @Inject lateinit var smsFeature: SmsFeature

    @Inject lateinit var actionFeature: ActionFeature

    @Inject lateinit var remoteDeviceStatusFeature: RemoteDeviceStatusFeature

    @Inject lateinit var deviceManager: DeviceManager

    @Inject lateinit var fileTransferService: FileTransferService

    @Inject lateinit var featureManager: FeatureManager

    @Inject lateinit var deviceControlHandler: DeviceControlHandler

    @Inject lateinit var appListHandler: AppListHandler

    @Inject lateinit var bluetoothPairingHandler: BluetoothPairingHandler

    @Inject lateinit var bluetoothHandoffHandler: BluetoothHandoffHandler

    @Inject lateinit var bluetoothHandoffStore: BluetoothHandoffStore

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val binder = LocalBinder()

    fun emitPendingApproval(device: PendingDeviceApproval) {
        deviceManager.setPendingApproval(device)
    }

    fun clearPendingApproval(deviceId: String) {
        deviceManager.clearPendingApproval(deviceId)
    }

    inner class LocalBinder : Binder() {
        fun getService(): NetworkService = this@NetworkService
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    lateinit var notificationBuilder: NotificationCompat.Builder

    private var tcpServerSocket: javax.net.ssl.SSLServerSocket? = null
    private var serverAcceptJob: Job? = null
    @Volatile private var shuttingDown = false
    private val pendingHandshakeSlots = Semaphore(MAX_PENDING_HANDSHAKES)
    private val pendingHandshakeSockets = ConcurrentHashMap.newKeySet<SSLSocket>()
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var foregroundStarted = false
    private val wallpaperCacheLock = Any()
    @Volatile private var cachedWallpaperLoaded = false
    @Volatile private var cachedWallpaper: String? = null

    private val connections = ConcurrentHashMap<String, DeviceConnection>()
    private val connectingDeviceIds = ConcurrentHashMap.newKeySet<String>()
    private val reconnectFailureCounts = ConcurrentHashMap<String, Int>()
    private val reconnectNotBefore = ConcurrentHashMap<String, Long>()

    private var tcpServerPort by Delegates.notNull<Int>()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            Actions.CONNECT.name -> {
                val connectionDetails = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_CONNECTION_DETAILS, ConnectionDetails::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_CONNECTION_DETAILS)
                }

                scope.launch {
                    if (connectionDetails != null) {
                        // Check if device is already paired - if so, use connectPaired, otherwise connectTo
                        val pairedDevice = deviceManager.getPairedDevice(connectionDetails.deviceId)
                        if (pairedDevice != null) {
                            connectPaired(pairedDevice)
                        } else {
                            connectTo(connectionDetails)
                        }
                    } else {
                        val lastConnectedDevice = deviceManager.pairedDevices.value
                            .maxByOrNull { it.lastConnected ?: 0L }
                        
                        lastConnectedDevice?.let { device ->
                            connectPaired(device)
                        }
                    }
                }
            }

            Actions.APPROVE_DEVICE.name -> {
                scope.launch {
                    intent.getStringExtra(DEVICE_ID_EXTRA)?.let {
                        approveDeviceConnection(it)
                    }
                }
            }

            Actions.REJECT_DEVICE.name -> {
                scope.launch {
                    intent.getStringExtra(DEVICE_ID_EXTRA)?.let {
                        rejectDeviceConnection(it)
                    }
                }
            }

            Actions.DISCONNECT.name -> {
                scope.launch {
                    val deviceId = intent.getStringExtra(DEVICE_ID_EXTRA)
                    if (deviceId != null) {
                        disconnect(deviceId)
                    } else {
                        val lastConnectedDevice = deviceManager.pairedDevices.value
                            .maxByOrNull { it.lastConnected ?: 0L }

                        lastConnectedDevice?.let { device ->
                            disconnect(device.deviceId)
                        }
                    }
                }
            }

            Actions.CANCEL_TRANSFER.name -> {
                val transferId = intent.getStringExtra(FileTransferService.EXTRA_TRANSFER_ID)
                    ?: return START_STICKY
                fileTransferService.cancelTransfer(transferId)
            }

            Actions.SEND_FILES.name -> {
                val deviceId = intent.getStringExtra(DEVICE_ID_EXTRA) ?: return START_STICKY
                val clipData = intent.clipData ?: return START_STICKY
                val uris = (0 until clipData.itemCount).mapNotNull { clipData.getItemAt(it).uri }
                if (uris.isNotEmpty()) {
                    fileTransferService.sendFiles(deviceId, uris)
                }
            }
            Actions.SEND_CLIPBOARD.name -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!text.isNullOrEmpty()) {
                    sendClipboardMessage(ClipboardInfo("text/plain", text))
                }
            }
        }
        return START_STICKY
    }

    @SuppressLint("WakelockTimeout")
    override fun onCreate() {
        super.onCreate()

        deviceControlHandler.start()
        privilegedBridgeManager.start()
        registerReceiver(screenOnReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
        registerReceiver(wifiStateReceiver, IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION))
        registerReceiver(wallpaperChangedReceiver, IntentFilter(Intent.ACTION_WALLPAPER_CHANGED))
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "$packageName:network").apply {
            setReferenceCounted(false)
            acquire()
        }
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:network")
            .apply {
                setReferenceCounted(false)
                acquire()
            }

        setNotification(null, null, AppNotifications.DEVICE_CONNECTION_ID)

        scope.launch {
            tcpServerPort = startTcpServer()
            networkDiscovery.initialize(tcpServerPort)
        }

        // Some VPN/TUN configurations allow LAN egress but reject LAN ingress. Keep
        // every non-forced pairing alive by retrying outbound connections, while the
        // per-device guard prevents discovery and recovery from racing each other.
        scope.launch {
            while (isActive) {
                delay(RECONNECT_INTERVAL_MS)
                val now = System.currentTimeMillis()
                deviceManager.pairedDevices.value
                    .filter { device ->
                        device.connectionState.isDisconnected &&
                            !device.connectionState.isForcedDisconnect &&
                            !connections.containsKey(device.deviceId) &&
                            now >= (reconnectNotBefore[device.deviceId] ?: 0L)
                    }
                    .forEach { device ->
                        launch { connectPaired(device) }
                    }
            }
        }

        scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                val now = SystemClock.uptimeMillis()
                connections.values.toList().forEach { connection ->
                    val device = deviceManager.getPairedDevice(connection.deviceId) ?: return@forEach
                    if (ProtocolCapabilities.CONNECTION_HEARTBEAT_V1 !in device.capabilities) return@forEach

                    if (now - connection.lastReceivedAt >= CONNECTION_STALE_TIMEOUT_MS) {
                        Log.w(TAG, "Closing stale connection to ${connection.deviceId}")
                        connection.close()
                    } else {
                        Log.d(TAG, "Sending heartbeat to ${connection.deviceId}")
                        if (!connection.sendControlMessage(ConnectionHeartbeat) && connection.closeIfOpen()) {
                            Log.w(TAG, "Closing connection to ${connection.deviceId}: heartbeat control lane is full")
                        }
                    }
                }
            }
        }

        scope.launch {
            while (isActive) {
                val shouldReadClipboard = hasConnectedClipboardTarget()
                if (shouldReadClipboard && privilegedBridgeManager.status.value == PrivilegedBridgeStatus.Ready) {
                    privilegedBridgeManager.readClipboardText()?.let { content ->
                        clipboardEventTracker.recordLocalText(content)?.let { eventId ->
                            sendClipboardMessage(
                                ClipboardInfo(
                                    clipboardType = "text/plain",
                                    content = content,
                                    eventId = eventId,
                                    originDeviceId = deviceManager.localDevice.deviceId,
                                ),
                            )
                        }
                    }
                }
                delay(PRIVILEGED_CLIPBOARD_POLL_INTERVAL_MS)
            }
        }

        scope.launch {
            deviceManager.pairedDevices.collect { pairedDevices ->
                // Get connected devices
                val connectedDevices = pairedDevices.filter { it.connectionState.isConnected }

                if (connectedDevices.isNotEmpty()) {
                    val deviceNames = connectedDevices.joinToString(", ") { it.deviceName }
                    // Only pass deviceId for disconnect action when single device connected
                    val deviceId = if (connectedDevices.size == 1) connectedDevices.first().deviceId else null
                    setNotification(deviceNames, deviceId, AppNotifications.DEVICE_CONNECTION_ID)
                } else {
                    setNotification(null, null, AppNotifications.DEVICE_CONNECTION_ID)
                }
            }
        }
    }

    suspend fun startTcpServer(): Int {
        try {
            tcpServerSocket = socketFactory.tcpServerSocket(PORT_RANGE)
                ?: throw IllegalStateException("Failed to create TCP server socket")
            val port = tcpServerSocket!!.localPort
            Log.d(TAG, "TCP server started successfully on port $port")

            // Start accepting connections
            serverAcceptJob = scope.launch {
                while (tcpServerSocket?.isClosed == false) {
                    try {
                        val sslSocket = withContext(Dispatchers.IO) {
                            tcpServerSocket?.accept() as? SSLSocket
                        } ?: break

                        if (!pendingHandshakeSlots.tryAcquire()) {
                            Log.w(TAG, "Rejecting incoming connection: handshake limit reached")
                            sslSocket.close()
                            continue
                        }

                        pendingHandshakeSockets.add(sslSocket)
                        Log.d(TAG, "Accepted incoming connection from ${sslSocket.remoteSocketAddress}")
                        launch {
                            try {
                                handleIncomingConnection(sslSocket)
                            } finally {
                                pendingHandshakeSockets.remove(sslSocket)
                                pendingHandshakeSlots.release()
                            }
                        }
                    } catch (e: Exception) {
                        if (tcpServerSocket?.isClosed == true) {
                            Log.d(TAG, "Server socket closed, stopping acceptance loop")
                            break
                        }
                        Log.e(TAG, "Error accepting connection", e)
                    }
                }
                Log.d(TAG, "TCP server stopped")
            }
            return port
        } catch (e: Exception) {
            Log.e(TAG, "Error starting TCP server", e)
            throw e
        }
    }

    private suspend fun handleIncomingConnection(sslSocket: SSLSocket) {
        try {
            withContext(Dispatchers.IO) {
                sslSocket.soTimeout = TLS_HANDSHAKE_TIMEOUT_MS
                sslSocket.startHandshake()
                sslSocket.soTimeout = 0
            }

            val readChannel = sslSocket.inputStream.toByteReadChannel()
            val writeChannel = sslSocket.outputStream.asByteWriteChannel()

            val authMessage = withTimeoutOrNull(10_000.milliseconds) {
                readChannel.readUTF8Line(ProtocolLimits.AUTHENTICATION_FRAME_MAX_CHARS)?.let {
                    val message = MessageSerializer.deserialize(it)
                    message as? Authentication
                }
            } ?: run {
                Log.e(TAG, "Timeout waiting for Authentication message from incoming connection")
                sslSocket.close()
                return
            }
            val address = (sslSocket.remoteSocketAddress as? java.net.InetSocketAddress)?.address?.hostAddress ?: ""

            Log.d(TAG, "Received Authentication from ${authMessage.deviceId}: ${authMessage.deviceName}")

            val certificate = SslHelper.verifySessionCertificate(sslSocket.session, authMessage.publicKey)
            if (certificate == null) {
                Log.w(TAG, "No client certificate or public key mismatch with TLS peer; rejecting connection")
                sslSocket.close()
                return
            }

            when (val device = deviceManager.getDevice(authMessage.deviceId)) {
                is PairedDevice -> authenticatePairedDevice(
                    sslSocket,
                    readChannel,
                    writeChannel,
                    authMessage,
                    device,
                    address,
                    certificate
                )
                else -> authenticateNewDevice(
                    sslSocket,
                    readChannel,
                    writeChannel,
                    authMessage,
                    address,
                    certificate,
                    device
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling incoming connection", e)
            sslSocket.close()
        }
    }

    private suspend fun authenticatePairedDevice(
        sslSocket: SSLSocket,
        readChannel: ByteReadChannel,
        writeChannel: ByteWriteChannel,
        authMessage: Authentication,
        device: PairedDevice,
        address: String,
        certificate: X509Certificate
    ) {

        if (!certificate.encoded.contentEquals(device.certificate)) {
            Log.w(TAG, "Certificate does not match pinned cert for paired device ${authMessage.deviceId}")
            sslSocket.close()
            return
        }

        val connection = DeviceConnection(device.deviceId, ConnectionDirection.Incoming, sslSocket, readChannel, writeChannel)
        if (!setConnection(device.deviceId, connection)) return
        resetReconnectBackoff(device.deviceId)

        val updatedDevice = device.copy(
            deviceName = authMessage.deviceName,
            lastConnected = System.currentTimeMillis(),
            addresses = if (device.addresses.none { it.address == address }) {
                device.addresses + AddressEntry(address)
            } else {
                device.addresses
            },
            address = address,
            connectionState = ConnectionState.Connected,
        )

        sendAuthMessage(writeChannel)

        deviceManager.addOrUpdatePairedDevice(updatedDevice)
        sendMessage(device.deviceId, ConnectionAck)
        finalizeConnection(updatedDevice, false)
    }

    private suspend fun authenticateNewDevice(
        sslSocket: SSLSocket,
        readChannel: ByteReadChannel,
        writeChannel: ByteWriteChannel,
        authMessage: Authentication,
        address: String,
        certificate: X509Certificate,
        device: BaseRemoteDevice?
    ) {
        if (device != null) {
            deviceManager.removeDiscoveredDevice(device.deviceId)
        }

        sendAuthMessage(writeChannel)

        val verificationCode = SslHelper.getVerificationCode(certificate, SslHelper.certificate)

        val newDevice = DiscoveredDevice(
            authMessage.deviceId,
            authMessage.deviceName,
            address,
            listOfNotNull(address),
            certificate,
            verificationCode
        )

        val connection = DeviceConnection(newDevice.deviceId, ConnectionDirection.Incoming, sslSocket, readChannel, writeChannel)
        if (!setConnection(newDevice.deviceId, connection)) return
        deviceManager.addOrUpdateDiscoveredDevice(newDevice)
    }

    suspend fun connectPaired(device: PairedDevice) {
        if (connections.containsKey(device.deviceId) || !connectingDeviceIds.add(device.deviceId)) {
            return
        }

        deviceManager.addOrUpdatePairedDevice(device.copy(connectionState = ConnectionState.Connecting(device.deviceId)))

        val port = device.port ?: PORT_RANGE.first

        try {
            val sslSocket = run {
                for (ip in device.getAddressesToTry()) {
                    socketFactory.tcpClientSocket(ip, port, device.certificate)?.let { return@run it }
                }
                null
            } ?: run {
                Log.e(TAG, "All connection attempts failed")
                scheduleReconnectAfterFailure(device.deviceId)
                if (!connections.containsKey(device.deviceId)) {
                    deviceManager.addOrUpdatePairedDevice(device.copy(connectionState = ConnectionState.Disconnected()))
                }
                return
            }

            val readChannel = sslSocket.inputStream.toByteReadChannel()
            val writeChannel = sslSocket.outputStream.asByteWriteChannel()

            sendAuthMessage(writeChannel)

            val connection = DeviceConnection(device.deviceId, ConnectionDirection.Outgoing, sslSocket, readChannel, writeChannel)
            if (!setConnection(device.deviceId, connection)) return
            resetReconnectBackoff(device.deviceId)

            val remoteAddress = (sslSocket.remoteSocketAddress as? java.net.InetSocketAddress)?.address?.hostAddress ?: ""
            val updatedDevice = device.copy(
                lastConnected = System.currentTimeMillis(),
                connectionState = ConnectionState.Connected,
                port = port,
                address = remoteAddress
            )
            deviceManager.addOrUpdatePairedDevice(updatedDevice)

            Log.d(TAG, "Device ${updatedDevice.deviceId} connected")
            sendMessage(device.deviceId, ConnectionAck)
            finalizeConnection(updatedDevice, false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error during connection", e)
            scheduleReconnectAfterFailure(device.deviceId)
            if (!connections.containsKey(device.deviceId)) {
                deviceManager.addOrUpdatePairedDevice(device.copy(connectionState = ConnectionState.Disconnected()))
            }
        } finally {
            connectingDeviceIds.remove(device.deviceId)
        }
    }


    suspend fun connectTo(connectionDetails: ConnectionDetails) {
        removeConnection(connectionDetails.deviceId)
        deviceManager.removeDiscoveredDevice(connectionDetails.deviceId)

        try {
            val sslSocket = run {
                connectionDetails.prefAddress?.let { prefAddress ->
                    socketFactory.tcpClientSocket(prefAddress, connectionDetails.port)?.let { return@run it }
                }
                for (ip in connectionDetails.addresses) {
                    socketFactory.tcpClientSocket(ip, connectionDetails.port)?.let { return@run it }
                }
                null
            } ?: run {
                Log.e(TAG, "All connection attempts failed")
                return
            }

            val readChannel = sslSocket.inputStream.toByteReadChannel()
            val writeChannel = sslSocket.outputStream.asByteWriteChannel()

            sendAuthMessage(writeChannel)

            val authResponse = try {
                withTimeoutOrNull(3000.milliseconds) {
                    readChannel.readUTF8Line(ProtocolLimits.AUTHENTICATION_FRAME_MAX_CHARS)?.let {
                        MessageSerializer.deserialize(it) as? Authentication
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception while reading Authentication message: ${e.message}", e)
                null
            } ?: run {
                Log.e(TAG, "Timeout or null response waiting for Authentication message")
                readChannel.cancel()
                sslSocket.close()
                return
            }

            val certificate = SslHelper.verifySessionCertificate(sslSocket.session, authResponse.publicKey)
            if (certificate == null) {
                Log.e(TAG, "No server certificate or public key mismatch with TLS peer; rejecting")
                readChannel.cancel()
                sslSocket.close()
                return
            }

            val address = (sslSocket.remoteSocketAddress as? java.net.InetSocketAddress)?.address?.hostAddress ?: ""

            val verificationCode = SslHelper.getVerificationCode(certificate, SslHelper.certificate)

            val connectedDevice = DiscoveredDevice(
                authResponse.deviceId,
                authResponse.deviceName,
                address,
                connectionDetails.addresses,
                certificate,
                verificationCode,
                connectionDetails.port
            )

            val connection = DeviceConnection(connectedDevice.deviceId, ConnectionDirection.Outgoing, sslSocket, readChannel, writeChannel)
            if (!setConnection(connectedDevice.deviceId, connection)) return

            deviceManager.addOrUpdateDiscoveredDevice(connectedDevice)
        } catch (e: Exception) {
            Log.e(TAG, "Error in connectTo", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun sendDeviceInfo(device: PairedDevice) {
        try {
            val wallpaper = getCachedWallpaper()

            val localPhoneNumbers = try {
                TelephonyHelper.getAllPhoneNumbers(this).map { it.toDto() }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get local phone numbers", e)
                emptyList()
            }

            val deviceInfo = DeviceInfo(
                deviceName = deviceManager.localDevice.deviceName,
                avatar = wallpaper,
                phoneNumbers = localPhoneNumbers,
                capabilities = ProtocolCapabilities.local,
            )
            sendMessage(device.deviceId, deviceInfo)
            Log.d(TAG, "DeviceInfo sent to ${device.deviceId}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send DeviceInfo to ${device.deviceId}", e)
        }
    }

    suspend fun disconnect(deviceId: String) {
        deviceManager.getPairedDevice(deviceId)?.let {
            if (it.connectionState.isConnected) {
                flushDisconnect(it.deviceId)
            }
            disconnectDevice(it, true)
        }
    }

    suspend fun disconnectDevice(device: PairedDevice, forcedDisconnect: Boolean = false) {
        Log.i(TAG, "Disconnected ${device.deviceName}")

        if (forcedDisconnect) {
            resetReconnectBackoff(device.deviceId)
        } else {
            scheduleReconnect(device.deviceId)
        }
        deviceManager.addOrUpdatePairedDevice(device.copy(connectionState = ConnectionState.Disconnected(forcedDisconnect)))
        fileTransferService.cancelTransfersForDevice(device.deviceId)
        featureManager.onDisconnect(device.deviceId)
        removeConnection(device.deviceId)
    }

    private suspend fun disconnectDevice(device: DiscoveredDevice) {
        Log.i(TAG, "Disconnected ${device.deviceName}")
        fileTransferService.cancelTransfersForDevice(device.deviceId)
        deviceManager.removeDiscoveredDevice(device.deviceId)
        removeConnection(device.deviceId)
    }

    suspend fun handlePairMessage(device: DiscoveredDevice, message: PairMessage) {
        if (message.pair) {
            // Check if this is a response to our pairing request
            if (device.isPairing) {
                val pairedDevice = PairedDevice(
                    deviceId = device.deviceId,
                    deviceName = device.deviceName,
                    avatar = null,
                    lastConnected = System.currentTimeMillis(),
                    addresses = device.addresses.map { AddressEntry(it) },
                    address = device.address,
                    connectionState = ConnectionState.Connected,
                    certificate = device.certificate.encoded,
                    port = device.port,
                )

                deviceManager.removeDiscoveredDevice(device.deviceId)
                deviceManager.addOrUpdatePairedDevice(pairedDevice)
                sendMessage(device.deviceId, ConnectionAck)
                Log.d(TAG, "Created PairedDevice ${device.deviceId} after pairing approval")

                finalizeConnection(pairedDevice, true)
            } else {
                // Remote device is requesting to pair with us
                val pendingApproval = PendingDeviceApproval(
                    device.deviceId,
                    device.deviceName,
                    device.verificationCode
                )
                emitPendingApproval(pendingApproval)
                showPairingVerificationNotification(pendingApproval)
            }
        } else {
            // Pairing rejected
            if (device.isPairing) {
                // They rejected our pairing request - update isPairing to false
                val updatedDevice = device.copy(isPairing = false)
                deviceManager.addOrUpdateDiscoveredDevice(updatedDevice)
                Log.d(TAG, "Pairing rejected by ${device.deviceId}")
            }
        }
    }

    suspend fun handleDeviceInfo(deviceInfo: DeviceInfo, device: PairedDevice) {
        val updatedDevice = device.copy(
            deviceName = deviceInfo.deviceName,
            avatar = deviceInfo.avatar,
            capabilities = deviceInfo.capabilities.toSet(),
        )
        deviceManager.addOrUpdatePairedDevice(updatedDevice)
        Log.d(TAG, "DeviceInfo updated for ${device.deviceId}; capabilities=${updatedDevice.capabilities}")
    }

    fun broadcastMessage(message: SocketMessage) {
        deviceManager.pairedDevices.value.forEach { device ->
            if (device.connectionState.isConnected) {
                sendMessage(device.deviceId, message)
            }
        }
    }

    fun sendClipboardMessage(message: ClipboardInfo) {
        scope.launch {
            deviceManager.pairedDevices.value.forEach { device ->
                if (device.connectionState.isConnected) {
                    if (preferencesRepository.readClipboardSyncSettingsForDevice(device.deviceId).first()) {
                        sendMessage(device.deviceId, message)
                    }
                }
            }
        }
    }

    private suspend fun hasConnectedClipboardTarget(): Boolean {
        for (device in deviceManager.pairedDevices.value) {
            if (!device.connectionState.isConnected) continue
            if (preferencesRepository.readClipboardSyncSettingsForDevice(device.deviceId).first()) {
                return true
            }
        }
        return false
    }

    /**
     * Starts listening for messages from a device connection.
     */
    private fun startListeningForDevice(connection: DeviceConnection) {
        connection.startListening(
            getDevice = { deviceManager.getDevice(it) },
            onMessage = { device, message -> handleMessage(device, message) },
            onClose = { closed ->
                scope.launch {
                    val id = closed.deviceId
                    if (connections[id] === closed) {
                        when (val device = deviceManager.getDevice(id)) {
                            is PairedDevice -> {
                                disconnectDevice(device)
                            }
                            is DiscoveredDevice -> disconnectDevice(device)
                        }
                    }
                }
            }
        )
    }

    suspend fun finalizeConnection(
        device: PairedDevice,
        isNewDevice: Boolean,
    ) {
        sendDeviceInfo(device)
        deviceControlHandler.sendDeviceStatus(device.deviceId)

        if (isNewDevice) {
            appListHandler.sendInstalledApps(device.deviceId)
            sendContacts(device)
        }

        networkDiscovery.saveCurrentNetworkAsTrusted()

        featureManager.onConnect(device.deviceId)
    }

    private suspend fun sendAuthMessage(writeChannel: ByteWriteChannel) {
        val localDevice = deviceManager.localDevice
        val authenticationMessage = Authentication(
            localDevice.deviceId,
            localDevice.deviceName,
            SslHelper.publicKeyString,
            localDevice.model
        )
        val jsonMessage = MessageSerializer.serialize(authenticationMessage)
            ?: throw java.io.IOException("Failed to serialize Authentication message")
        if (jsonMessage.length > ProtocolLimits.AUTHENTICATION_FRAME_MAX_CHARS) {
            throw java.io.IOException("Authentication frame is too large")
        }
        writeChannel.writeStringUtf8("$jsonMessage\n")
        writeChannel.flush()
    }

    private suspend fun sendContacts(device: PairedDevice) {
        try {
            if (!isContactsPermissionGranted(this)) return

            ContactsHelper().getAllContacts(this).forEach { contact ->
                if (!sendMessageAwait(device.deviceId, contact)) return
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending contacts", e)
        }
    }

    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            networkDiscovery.broadcastDevice()
        }
    }

    private val wifiStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            networkDiscovery.broadcastDevice()
        }
    }

    suspend fun approveDeviceConnection(deviceId: String) {
        clearPendingApproval(deviceId)
        cancelPairingVerificationNotification(deviceId)

        val discoveredDevice = deviceManager.getDiscoveredDevice(deviceId) ?: return

        val pairedDevice = PairedDevice(
            deviceId = discoveredDevice.deviceId,
            deviceName = discoveredDevice.deviceName,
            avatar = null,
            lastConnected = System.currentTimeMillis(),
            addresses = discoveredDevice.addresses.map { AddressEntry(it) },
            connectionState = ConnectionState.Connected,
            port = discoveredDevice.port,
            address = discoveredDevice.address,
            certificate = discoveredDevice.certificate.encoded
        )

        // Send pairing message before removing DiscoveredDevice
        sendMessage(deviceId, PairMessage(true))

        deviceManager.removeDiscoveredDevice(deviceId)
        deviceManager.addOrUpdatePairedDevice(pairedDevice)
        Log.d(TAG, "Approved $deviceId")

        sendMessage(deviceId, ConnectionAck)
        finalizeConnection(pairedDevice, true)
    }

    suspend fun rejectDeviceConnection(deviceId: String) {
        val remoteDevice = deviceManager.getDiscoveredDevice(deviceId)

        if (remoteDevice != null) {
            // Send rejection message
            sendMessage(deviceId, PairMessage(pair = false))
        }

        // Clear pending approval and cancel notification
        clearPendingApproval(deviceId)
        cancelPairingVerificationNotification(deviceId)

        Log.d(TAG, "Rejected pairing request from device $deviceId")
    }

    // Connection management methods
    private fun setConnection(deviceId: String, connection: DeviceConnection): Boolean {
        if (shuttingDown) {
            connection.close()
            return false
        }
        val accepted = synchronized(connections) {
            if (shuttingDown) return@synchronized false
            val existing = connections[deviceId]
            val shouldAccept = ConnectionCollisionPolicy.shouldAcceptCandidate(
                localDeviceId = deviceManager.localDevice.deviceId,
                remoteDeviceId = deviceId,
                existingDirection = existing?.direction,
                candidateDirection = connection.direction,
            )
            if (!shouldAccept) {
                false
            } else {
                if (existing != null) {
                    connections.remove(deviceId, existing)
                    existing.close()
                }
                connections[deviceId] = connection
                true
            }
        }
        if (!accepted) {
            Log.d(TAG, "Rejected duplicate ${connection.direction} connection for $deviceId")
            connection.close()
            return false
        }
        startListeningForDevice(connection)
        return true
    }

    private fun removeConnection(deviceId: String) {
        connections.remove(deviceId)?.close()
    }

    private fun scheduleReconnect(deviceId: String, delayMs: Long = RECONNECT_INTERVAL_MS) {
        reconnectNotBefore[deviceId] = System.currentTimeMillis() + delayMs
    }

    private fun scheduleReconnectAfterFailure(deviceId: String) {
        val failures = reconnectFailureCounts.merge(deviceId, 1, Int::plus) ?: 1
        val delayMs = ReconnectBackoffPolicy.delayAfterFailure(failures)
        scheduleReconnect(deviceId, delayMs)
        Log.i(TAG, "Reconnect to $deviceId delayed ${delayMs}ms after $failures failures")
    }

    private fun resetReconnectBackoff(deviceId: String) {
        reconnectFailureCounts.remove(deviceId)
        reconnectNotBefore.remove(deviceId)
    }

    fun sendMessage(deviceId: String, message: SocketMessage) {
        val connection = connections[deviceId]
        if (connection == null) {
            Log.w(TAG, "Cannot send message to $deviceId: no connection found")
            return
        }
        if (!connection.sendMessage(message)) {
            if (connection.closeIfOpen()) {
                Log.w(
                    TAG,
                    "Closing overloaded connection to $deviceId while queuing ${message::class.simpleName}",
                )
            }
        }
    }

    /** Promote once; later notification refreshes are handled by NotificationCenter.notify(). */
    @Synchronized
    fun ensureForeground(notificationId: Int, notification: Notification) {
        if (foregroundStarted) return
        startForeground(notificationId, notification)
        foregroundStarted = true
    }

    private val wallpaperChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            synchronized(wallpaperCacheLock) {
                cachedWallpaper = null
                cachedWallpaperLoaded = false
            }
        }
    }

    private fun getCachedWallpaper(): String? = synchronized(wallpaperCacheLock) {
        if (!cachedWallpaperLoaded) {
            cachedWallpaper = try {
                WallpaperManager.getInstance(applicationContext).drawable?.let { drawable ->
                    drawableToBase64Compressed(drawable)
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "Unable to access wallpaper", e)
                null
            }
            cachedWallpaperLoaded = true
        }
        cachedWallpaper
    }

    suspend fun sendMessageAwait(deviceId: String, message: SocketMessage): Boolean {
        val connection = connections[deviceId] ?: return false
        val delivered = withTimeoutOrNull(MESSAGE_SEND_TIMEOUT_MS) {
            connection.sendMessageAndAwait(message)
        } == true
        if (!delivered) {
            if (connection.closeIfOpen()) {
                Log.w(TAG, "Timed out writing to $deviceId; closing connection")
            }
        }
        return delivered
    }

    private suspend fun flushDisconnect(deviceId: String) {
        val connection = connections[deviceId] ?: return
        val delivered = withTimeoutOrNull(DISCONNECT_FLUSH_TIMEOUT_MS) {
            connection.sendControlMessageAndAwait(Disconnect)
        } == true
        if (!delivered) Log.w(TAG, "Disconnect frame to $deviceId did not flush before close")
    }

    override fun onDestroy() {
        shuttingDown = true
        serverAcceptJob?.cancel()
        try {
            tcpServerSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing TCP server", e)
        }
        pendingHandshakeSockets.toList().forEach { socket ->
            runCatching { socket.close() }
        }
        pendingHandshakeSockets.clear()

        val connectionSnapshot = connections.entries.map { it.key to it.value }

        // Flush paired peers concurrently under one global deadline, then tear down every
        // accepted connection, including devices still waiting for pairing approval.
        runBlocking {
            val pairedIds = deviceManager.pairedDevices.value.mapTo(mutableSetOf()) { it.deviceId }
            withTimeoutOrNull(DISCONNECT_FLUSH_TIMEOUT_MS) {
                coroutineScope {
                    connectionSnapshot
                        .filter { (deviceId, _) -> deviceId in pairedIds }
                        .map { (_, connection) ->
                            async { runCatching { connection.sendControlMessageAndAwait(Disconnect) } }
                        }
                        .awaitAll()
                }
            }

            connectionSnapshot.forEach { (deviceId, _) ->
                when (val device = deviceManager.getDevice(deviceId)) {
                    is PairedDevice -> disconnectDevice(device, true)
                    is DiscoveredDevice -> disconnectDevice(device)
                    else -> Unit
                }
            }
        }

        connections.values.toList().forEach(DeviceConnection::close)
        connections.clear()

        scope.cancel()

        unregisterReceiver(screenOnReceiver)
        unregisterReceiver(wifiStateReceiver)
        unregisterReceiver(wallpaperChangedReceiver)
        wifiLock?.let { lock -> if (lock.isHeld) lock.release() }
        wifiLock = null
        wakeLock?.let { lock -> if (lock.isHeld) lock.release() }
        wakeLock = null
        deviceControlHandler.stop()
        networkDiscovery.shutdown()
        privilegedBridgeManager.stop()
        fileTransferService.shutdown()

        remotePlaybackFeature.release()
        smsFeature.stop()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        foregroundStarted = false
        super.onDestroy()
    }

    companion object {
        enum class Actions {
            CONNECT,
            APPROVE_DEVICE,
            REJECT_DEVICE,
            DISCONNECT,
            CANCEL_TRANSFER,
            SEND_CLIPBOARD,
            SEND_FILES
        }

        val PORT_RANGE = 5150..5169
        const val TAG = "NetworkService"
        const val DEVICE_ID_EXTRA = "device_id"
        const val EXTRA_CONNECTION_DETAILS = "extra_connection_details"
        private const val PRIVILEGED_CLIPBOARD_POLL_INTERVAL_MS = 400L
        private const val RECONNECT_INTERVAL_MS = 5_000L
        private const val HEARTBEAT_INTERVAL_MS = 10_000L
        private const val CONNECTION_STALE_TIMEOUT_MS = 30_000L
        private const val TLS_HANDSHAKE_TIMEOUT_MS = 5_000
        private const val MESSAGE_SEND_TIMEOUT_MS = 5_000L
        private const val DISCONNECT_FLUSH_TIMEOUT_MS = 750L
        private const val MAX_PENDING_HANDSHAKES = 8
    }
}
