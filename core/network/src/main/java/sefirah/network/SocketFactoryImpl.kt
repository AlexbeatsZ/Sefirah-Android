package sefirah.network

import android.util.Log
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.BoundDatagramSocket
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sefirah.domain.interfaces.SocketFactory
import sefirah.network.util.SslHelper
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

@Singleton
class SocketFactoryImpl @Inject constructor() : SocketFactory {
    val selectorManager = SelectorManager(Dispatchers.IO)

    override suspend fun tcpClientSocket(address: String, port: Int, certificate: ByteArray?): SSLSocket? {
        return try {
            Log.d(TAG, "Connecting to $address:$port")
            val sslContext = SslHelper.sslContext(certificate)
            withContext(Dispatchers.IO) {
                (sslContext.socketFactory.createSocket() as SSLSocket).apply {
                    try {
                        connect(java.net.InetSocketAddress(address, port), CONNECTION_TIMEOUT_MS)
                        soTimeout = HANDSHAKE_TIMEOUT_MS
                        startHandshake()
                        soTimeout = 0
                    } catch (error: Exception) {
                        runCatching { close() }
                        throw error
                    }
                }
            }.also {
                Log.d(TAG, "Connected to ${it.remoteSocketAddress}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed to $address:$port", e)
            null
        }
    }

    override suspend fun tcpServerSocket(range: IntRange, certificate: ByteArray?): SSLServerSocket? {
        val sslContext = SslHelper.sslContext(certificate)

        range.forEach { port ->
            try {
                val serverSocket = withContext(Dispatchers.IO) {
                    sslContext.serverSocketFactory.createServerSocket(port)
                } as SSLServerSocket

                serverSocket.needClientAuth = true

                Log.d(TAG, "Server socket created on ${serverSocket.inetAddress.address}:${port}")
                return serverSocket
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create server socket on port $port", e)
            }
        }
        Log.e(TAG, "Server socket creation failed")
        return null
    }

    override suspend fun udpSocket(port: Int): BoundDatagramSocket {
        return try {
            aSocket(selectorManager).udp().bind(InetSocketAddress("0.0.0.0", port)) {
                reuseAddress = true
                broadcast = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create UDP server", e)
            throw e
        }
    }

    companion object {
        private const val CONNECTION_TIMEOUT_MS = 3_000
        private const val HANDSHAKE_TIMEOUT_MS = 3_000
        private const val TAG = "SocketFactory"
    }
}
