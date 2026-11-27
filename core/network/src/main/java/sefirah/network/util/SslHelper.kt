package sefirah.network.util

import android.annotation.SuppressLint
import android.util.Base64
import kotlinx.coroutines.runBlocking
import org.bouncycastle.util.Arrays
import sefirah.common.util.CryptoUtils
import sefirah.common.util.CertificateIdentity
import sefirah.common.util.getDeviceKeyStore
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Formatter
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLException
import javax.net.ssl.X509TrustManager

object SslHelper {

    /** Our device's certificate, loaded once and reused for TLS identity and public-key helpers. */
    val certificate: X509Certificate by lazy {
        runBlocking { CryptoUtils().getOrCreateCertificate() }
    }

    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private val trustAllCerts = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    private val secureRandom = SecureRandom()
    private val keyManagerFactory by lazy { createKeyManagerFactory() }

    /**
     * Returns a TLS SSLContext for both client and server use.
     *
     * @param certificate Null is for discovery before application authentication. Otherwise pin SPKI.
     */
    fun sslContext(certificate: ByteArray? = null): SSLContext {
        val trustManager = if (certificate != null) {
            getTrustManager(certificate)
        } else {
            trustAllCerts
        }
        return SSLContext.getInstance("TLSv1.2").apply {
            init(keyManagerFactory.keyManagers, arrayOf(trustManager), secureRandom)
        }
    }

    @SuppressLint("CustomX509TrustManager")
    private fun getTrustManager(certificateEncoded: ByteArray): X509TrustManager {
        val certFactory = CertificateFactory.getInstance("X.509")
        val x509Certificate = certFactory.generateCertificate(ByteArrayInputStream(certificateEncoded)) as X509Certificate
        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                checkPinned(chain)
            }
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                checkPinned(chain)
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf(x509Certificate)
            private fun checkPinned(chain: Array<X509Certificate>) {
                if (chain.isEmpty()) throw SSLException("No certificate in chain")
                val leaf = chain[0]
                if (!CertificateIdentity.matches(leaf.encoded, certificateEncoded)) {
                    throw SSLException("Public key does not match pinned device identity")
                }
            }
        }
    }

    private fun createKeyManagerFactory(): KeyManagerFactory {
        return KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(getDeviceKeyStore(), null)
        }
    }

    fun verifySessionCertificate(session: SSLSession, publicKeyString: String): X509Certificate? {
        val certs = try { session.peerCertificates } catch (_: Exception) { return null }
        if (certs.isEmpty()) return null
        val leaf = certs[0] as? X509Certificate ?: return null
        if (publicKeyString(leaf) != publicKeyString) return null
        return leaf
    }

    /** Our cert's public key as Base64 (for auth message and UDP broadcast). */
    val publicKeyString: String
        get() = publicKeyString(certificate)

    private fun publicKeyString(cert: X509Certificate): String = Base64.encodeToString(cert.publicKey.encoded, Base64.NO_WRAP)

    /**
     * verification code from two certificates.
     */
    fun getVerificationCode(certificateA: Certificate, certificateB: Certificate): String {
        val certsConcat = sortedConcat(certificateA.publicKey.encoded, certificateB.publicKey.encoded)
        return humanReadableHash(certsConcat)
    }

    private fun sortedConcat(a: ByteArray, b: ByteArray): ByteArray {
        return if (Arrays.compareUnsigned(a, b) < 0) {
            b + a
        } else {
            a + b
        }
    }

    private fun humanReadableHash(bytes: ByteArray): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
        val formatter = Formatter()
        for (value in hash) {
            formatter.format("%02x", value)
        }
        return formatter.toString().substring(0, 8).uppercase()
    }
}
