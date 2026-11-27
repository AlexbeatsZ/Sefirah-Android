package sefirah.common.util

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/** SHA-256 of DER SubjectPublicKeyInfo, independent of certificate serial/validity. */
object CertificateIdentity {
    fun certificate(encoded: ByteArray): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(encoded)) as X509Certificate

    fun fingerprint(encoded: ByteArray): String = fingerprint(certificate(encoded))
    fun fingerprint(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded)
            .joinToString("") { "%02X".format(it.toInt() and 0xff) }

    fun matches(encoded: ByteArray, pinned: ByteArray): Boolean = try {
        MessageDigest.isEqual(
            MessageDigest.getInstance("SHA-256").digest(certificate(encoded).publicKey.encoded),
            MessageDigest.getInstance("SHA-256").digest(certificate(pinned).publicKey.encoded))
    } catch (_: Exception) { false }
}
