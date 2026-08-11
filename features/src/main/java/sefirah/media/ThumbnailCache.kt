package sefirah.media

/**
 * A small LRU cache that rejects encoded thumbnails before a decoder can allocate for them.
 * The loader runs under the cache lock so concurrent requests for the same thumbnail decode once.
 */
internal class ThumbnailCache<T : Any>(
    private val maxEntries: Int,
    private val maxEncodedLength: Int,
) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
        require(maxEncodedLength > 0) { "maxEncodedLength must be positive" }
    }

    private val entries = object : LinkedHashMap<String, T>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, T>?): Boolean =
            size > maxEntries
    }

    @Synchronized
    fun getOrLoad(encodedThumbnail: String?, loader: (String) -> T?): T? {
        if (encodedThumbnail.isNullOrEmpty() || encodedThumbnail.length > maxEncodedLength) {
            return null
        }

        entries[encodedThumbnail]?.let { return it }
        return loader(encodedThumbnail)?.also { entries[encodedThumbnail] = it }
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }
}
