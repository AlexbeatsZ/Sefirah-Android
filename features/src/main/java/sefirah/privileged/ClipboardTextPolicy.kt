package sefirah.privileged

internal object ClipboardTextPolicy {
    const val MAX_TEXT_LENGTH = 256 * 1024

    fun acceptedText(text: CharSequence?, isSensitive: Boolean): String? {
        if (isSensitive || text == null || text.isEmpty() || text.length > MAX_TEXT_LENGTH) return null
        return text.toString()
    }
}
