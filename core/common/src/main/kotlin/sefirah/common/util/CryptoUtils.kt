package sefirah.common.util

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.io.File
import java.security.SecureRandom
import java.security.Security
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Calendar
import javax.security.auth.x500.X500Principal
import kotlin.random.asKotlinRandom

class CryptoUtils {
    init {
        Security.addProvider(BouncyCastleProvider())
    }

    @Throws(Exception::class)
    private fun createECDSACertificate(): X509Certificate {
        val keyPairGenerator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            "AndroidKeyStore"
        )

        val startDate = Calendar.getInstance()
        val endDate = Calendar.getInstance()
        endDate.add(Calendar.YEAR, CERT_VALIDITY_YEARS)

        val parameterSpec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        ).apply {
            setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            setCertificateSubject(X500Principal("CN=SefirahAndroid"))
            setCertificateSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
            setCertificateNotBefore(startDate.time)
            setCertificateNotAfter(endDate.time)
            setDigests(
                KeyProperties.DIGEST_NONE,
                KeyProperties.DIGEST_SHA256,
                KeyProperties.DIGEST_SHA512
            )
            setUserAuthenticationRequired(false)
        }.build()

        keyPairGenerator.initialize(parameterSpec)
        keyPairGenerator.generateKeyPair()

        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        return keyStore.getCertificate(KEY_ALIAS) as X509Certificate
    }

    suspend fun getOrCreateCertificate(): X509Certificate {
        return withContext(Dispatchers.IO) {
            synchronized(identityLock) {
                val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                val marker = AtomicFile(File(checkNotNull(identityDirectory) {
                    "Device identity storage is not initialized"
                }, "device-identity-spki"))
                val saved = if (marker.baseFile.exists() || File(marker.baseFile.path + ".bak").exists())
                    marker.readFully().decodeToString() else null
                val certificate = if (keyStore.containsAlias(KEY_ALIAS)) {
                    keyStore.getCertificate(KEY_ALIAS) as? X509Certificate
                        ?: error("Existing device key has no readable certificate; identity retained")
                } else {
                    check(saved == null) { "Device key is missing after initialization; explicit identity recovery required" }
                    createECDSACertificate()
                }
                // Never overwrite an unreadable key, or a key restored under an existing marker.
                val loaded = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                val privateKey = loaded.getKey(KEY_ALIAS, null) as? PrivateKey
                    ?: error("Device private key is unreadable; identity retained")
                val challenge = ByteArray(32).also { SecureRandom().nextBytes(it) }
                val proof = Signature.getInstance("SHA256withECDSA").run {
                    initSign(privateKey); update(challenge); sign()
                }
                check(Signature.getInstance("SHA256withECDSA").run {
                    initVerify(certificate.publicKey); update(challenge); verify(proof)
                }) { "Device key and certificate do not match; identity retained" }
                val fingerprint = CertificateIdentity.fingerprint(certificate)
                check(saved == null || saved == fingerprint) { "Device public key changed; explicit identity recovery required" }
                if (saved == null) {
                    val stream = marker.startWrite()
                    try {
                        stream.write(fingerprint.toByteArray())
                        marker.finishWrite(stream)
                    } catch (error: Exception) {
                        marker.failWrite(stream)
                        throw error
                    }
                    Log.i("SefirahIdentity", "Initialized persistent device identity $fingerprint")
                }
                certificate
            }
        }
    }

    companion object {
        private const val CERT_VALIDITY_YEARS = 10
        const val KEY_ALIAS = "Sefirah"
        private val identityLock = Any()
        @Volatile private var identityDirectory: File? = null

        fun initialize(context: Context) {
            identityDirectory = context.applicationContext.noBackupFilesDir
        }
    }
}

fun getDeviceKeyStore(): KeyStore {
    runBlocking { CryptoUtils().getOrCreateCertificate() }
    return KeyStore.getInstance("AndroidKeyStore").apply {
        load(null)
        if (!containsAlias(CryptoUtils.KEY_ALIAS)) {
            throw IllegalStateException("Failed to initialize device certificate")
        }
    }
}

fun generateRandomPassword(): String {
    val allowedChars = ('A'..'Z') + ('a'..'z') + ('0'..'9') + "!@#$%^&*"
    val random = SecureRandom().asKotlinRandom()
    return (1..28).map { allowedChars.random(random) }.joinToString("")
}
