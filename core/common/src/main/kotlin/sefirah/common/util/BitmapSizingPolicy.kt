package sefirah.common.util

internal data class BitmapSize(val width: Int, val height: Int)

internal fun calculateDownscaledBitmapSize(
    width: Int,
    height: Int,
    maxDimension: Int,
): BitmapSize {
    require(width > 0 && height > 0) { "Bitmap dimensions must be positive" }
    require(maxDimension > 0) { "maxDimension must be positive" }

    val largestDimension = maxOf(width, height)
    if (largestDimension <= maxDimension) return BitmapSize(width, height)

    val scale = maxDimension.toDouble() / largestDimension
    return BitmapSize(
        width = (width * scale).toInt().coerceAtLeast(1),
        height = (height * scale).toInt().coerceAtLeast(1),
    )
}

internal fun calculateBitmapDecodeSampleSize(
    width: Int,
    height: Int,
    maxDimension: Int,
    maxPixelCount: Long,
): Int {
    require(width > 0 && height > 0) { "Bitmap dimensions must be positive" }
    require(maxDimension > 0) { "maxDimension must be positive" }
    require(maxPixelCount > 0) { "maxPixelCount must be positive" }

    var sampleSize = 1
    while (true) {
        val sampledWidth = ceilDiv(width, sampleSize)
        val sampledHeight = ceilDiv(height, sampleSize)
        val withinDimensionLimit = sampledWidth <= maxDimension && sampledHeight <= maxDimension
        val withinPixelLimit = sampledWidth.toLong() * sampledHeight <= maxPixelCount
        if (withinDimensionLimit && withinPixelLimit) return sampleSize

        if (sampleSize > Int.MAX_VALUE / 2) return Int.MAX_VALUE
        sampleSize *= 2
    }
}

private fun ceilDiv(value: Int, divisor: Int): Int =
    ((value.toLong() + divisor - 1) / divisor).toInt()
