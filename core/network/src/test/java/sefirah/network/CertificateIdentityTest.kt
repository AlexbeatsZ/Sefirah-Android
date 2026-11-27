package sefirah.network

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.*
import org.junit.Test
import sefirah.common.util.CertificateIdentity
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.util.Date

class CertificateIdentityTest {
    private fun key() = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    private fun certificate(key: KeyPair, serial: Long): ByteArray {
        val name = X500Name("CN=Sefirah-$serial")
        return JcaX509CertificateConverter().getCertificate(
            JcaX509v3CertificateBuilder(name, BigInteger.valueOf(serial), Date(0),
                Date(4_000_000_000_000L), name, key.public)
                .build(JcaContentSignerBuilder("SHA256withECDSA").build(key.private))).encoded
    }

    @Test fun `renewal preserves identity while changed keys are rejected`() {
        val key = key()
        val first = certificate(key, 1)
        val renewed = certificate(key, 2)
        val different = certificate(key(), 3)
        assertFalse(first.contentEquals(renewed))
        assertTrue(CertificateIdentity.matches(renewed, first))
        assertFalse(CertificateIdentity.matches(different, first))
        assertFalse(CertificateIdentity.matches(byteArrayOf(), first))
        assertFalse(CertificateIdentity.matches(first, byteArrayOf()))
        assertEquals(MessageDigest.getInstance("SHA-256").digest(key.public.encoded)
            .joinToString("") { "%02X".format(it.toInt() and 0xff) }, CertificateIdentity.fingerprint(first))
    }
}
