package sefirah.common.util

import org.junit.Assert.assertEquals
import org.junit.Test

class BitmapSizingPolicyTest {
    @Test
    fun `small bitmap is never upscaled`() {
        assertEquals(
            BitmapSize(width = 64, height = 32),
            calculateDownscaledBitmapSize(width = 64, height = 32, maxDimension = 128),
        )
    }

    @Test
    fun `large bitmap keeps aspect ratio within dimension`() {
        assertEquals(
            BitmapSize(width = 128, height = 64),
            calculateDownscaledBitmapSize(width = 400, height = 200, maxDimension = 128),
        )
        assertEquals(
            BitmapSize(width = 32, height = 128),
            calculateDownscaledBitmapSize(width = 100, height = 400, maxDimension = 128),
        )
    }

    @Test
    fun `decode sampling leaves an already bounded bitmap untouched`() {
        assertEquals(
            1,
            calculateBitmapDecodeSampleSize(
                width = 512,
                height = 256,
                maxDimension = 1024,
                maxPixelCount = 1024L * 1024,
            ),
        )
    }

    @Test
    fun `decode sampling enforces dimensions and pixel count`() {
        assertEquals(
            4,
            calculateBitmapDecodeSampleSize(
                width = 4096,
                height = 2048,
                maxDimension = 1024,
                maxPixelCount = 1024L * 1024,
            ),
        )
        assertEquals(
            4,
            calculateBitmapDecodeSampleSize(
                width = 4000,
                height = 4000,
                maxDimension = 2048,
                maxPixelCount = 1_000_000,
            ),
        )
    }
}
