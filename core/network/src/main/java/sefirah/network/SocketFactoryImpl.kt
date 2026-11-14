package sefirah.network

import android.util.Log
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.BoundDatagramSocket
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import sefirah.domain.interfaces.SocketFactory
import sefirah.network.util.SslHelper
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.time.Duration.Companion.milliseconds

@Singleton
class SocketFactoryImpl @Inject constructor() : SocketFactory {
    val selectorManager = SelectorManager(Dispatchers.IO)

    override suspend fun tcpClientSocket(address: String, port: Int, certificate: ByteArray?): SSLSocket? {
        var acquired: SSLSocket? = null
        return try { withContext(Dispatchers.IO) {
            val socket = SslHelper.sslContext(certificate).socketFactory.createSocket() as SSLSocket
            acquired = socket
            try {
                socket.keepAlive = true
                socket.connect(java.net.InetSocketAddress(address, port), 3000)
                socket.soTimeout = 10_000
                socket.startHandshake()
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                socket.soTimeout = 0
                socket
            } catch (e: Exception) {
                socket.close()
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Connection failed to $address:$port", e)
                null
            }
        } } catch (e: kotlinx.coroutines.CancellationException) {
            acquired?.close()
            throw e
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
        private const val TAG = "SocketFactory"
    }
}