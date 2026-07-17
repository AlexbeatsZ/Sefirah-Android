package sefirah.clipboard

import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ClipboardEventTracker @Inject constructor() {
    private var lastObservedFingerprint: String? = null

    @Synchronized
    fun recordLocalText(content: String): String? {
        if (content.isEmpty()) return null
        val fingerprint = fingerprint(content)
        if (fingerprint == lastObservedFingerprint) return null
        lastObservedFingerprint = fingerprint
        return UUID.randomUUID().toString()
    }

    @Synchronized
    fun recordRemoteText(content: String) {
        lastObservedFingerprint = fingerprint(content)
    }

    private fun fingerprint(content: String): String = MessageDigest
        .getInstance("SHA-256")
        .digest(content.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
