package sefirah.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThumbnailCacheTest {
    @Test
    fun `same thumbnail is loaded once`() {
        val cache = ThumbnailCache<String>(maxEntries = 2, maxEncodedLength = 16)
        var loads = 0

        assertEquals("decoded", cache.getOrLoad("thumbnail") { loads++; "decoded" })
        assertEquals("decoded", cache.getOrLoad("thumbnail") { loads++; "other" })

        assertEquals(1, loads)
    }

    @Test
    fun `empty and oversized thumbnails never reach loader`() {
        val cache = ThumbnailCache<String>(maxEntries = 2, maxEncodedLength = 4)
        var loads = 0

        assertNull(cache.getOrLoad(null) { loads++; it })
        assertNull(cache.getOrLoad("") { loads++; it })
        assertNull(cache.getOrLoad("12345") { loads++; it })

        assertEquals(0, loads)
    }

    @Test
    fun `cache evicts least recently used thumbnail`() {
        val cache = ThumbnailCache<String>(maxEntries = 2, maxEncodedLength = 16)
        var loads = 0
        fun load(encoded: String) = cache.getOrLoad(encoded) { loads++; "decoded-$it" }

        load("first")
        load("second")
        load("first")
        load("third")
        load("second")

        assertEquals(4, loads)
    }

    @Test
    fun `clear releases entries`() {
        val cache = ThumbnailCache<String>(maxEntries = 1, maxEncodedLength = 16)
        var loads = 0

        cache.getOrLoad("thumbnail") { loads++; "decoded" }
        cache.clear()
        cache.getOrLoad("thumbnail") { loads++; "decoded" }

        assertEquals(2, loads)
    }

    @Test
    fun `failed loads are not cached`() {
        val cache = ThumbnailCache<String>(maxEntries = 1, maxEncodedLength = 16)
        var loads = 0

        assertNull(cache.getOrLoad("thumbnail") { loads++; null })
        assertNull(cache.getOrLoad("thumbnail") { loads++; null })

        assertEquals(2, loads)
    }
}
