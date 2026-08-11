package sefirah.common.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Base64
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.scale
import java.io.ByteArrayOutputStream

const val DEFAULT_MAX_ENCODED_BITMAP_LENGTH = 4 * 1024 * 1024
const val DEFAULT_MAX_DECODED_BITMAP_DIMENSION = 1024

fun bitmapToBase64(bitmap: Bitmap): String {
    val outputStream = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
    val byteArray = outputStream.toByteArray()
    return Base64.encodeToString(byteArray, Base64.NO_WRAP)
}

fun drawableToBitmap(drawable: Drawable): Bitmap {
    if (drawable is BitmapDrawable) {
        return drawable.bitmap
    }
    val bitmap = createBitmap(
        drawable.intrinsicWidth.coerceAtLeast(1),
        drawable.intrinsicHeight.coerceAtLeast(1),
    )
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return bitmap
}

fun drawableToBase64(drawable: Drawable): String? {
    val ownsBitmap = drawable !is BitmapDrawable
    val bitmap = drawableToBitmap(drawable)
    return try {
        bitmapToBase64(bitmap)
    } finally {
        if (ownsBitmap) bitmap.recycle()
    }
}

fun base64ToBitmap(
    base64String: String?,
    maxEncodedLength: Int = DEFAULT_MAX_ENCODED_BITMAP_LENGTH,
    maxDimension: Int = DEFAULT_MAX_DECODED_BITMAP_DIMENSION,
): Bitmap? {
    require(maxEncodedLength > 0) { "maxEncodedLength must be positive" }
    require(maxDimension > 0) { "maxDimension must be positive" }

    return try {
        if (base64String.isNullOrEmpty()) return null
        if (base64String.length > maxEncodedLength) {
            Log.w("BitmapHelper", "Ignoring oversized base64 bitmap (${base64String.length} chars)")
            return null
        }

        val decodedBytes = Base64.decode(base64String, Base64.NO_WRAP)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = calculateBitmapDecodeSampleSize(
                width = bounds.outWidth,
                height = bounds.outHeight,
                maxDimension = maxDimension,
                maxPixelCount = maxDimension.toLong() * maxDimension,
            )
        }
        BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size, decodeOptions)
    } catch (e: Exception) {
        Log.e("BitmapHelper", "Error decoding base64 thumbnail", e)
        null
    }
}

fun base64ToIconCompat(base64String: String?): IconCompat? {
    val bitmap = base64ToBitmap(base64String)
    return if (bitmap != null) {
        IconCompat.createWithBitmap(bitmap)
    }
    else {
        null
    }
}

fun drawableToBase64Compressed(
    drawable: Drawable,
    maxSize: Int = 1024,
): String? {
    require(maxSize > 0) { "maxSize must be positive" }

    val ownsOriginalBitmap = drawable !is BitmapDrawable
    val originalBitmap = drawableToBitmap(drawable)
    val targetSize = calculateDownscaledBitmapSize(
        width = originalBitmap.width,
        height = originalBitmap.height,
        maxDimension = maxSize,
    )
    val scaledBitmap = if (
        targetSize.width == originalBitmap.width && targetSize.height == originalBitmap.height
    ) {
        originalBitmap
    } else {
        originalBitmap.scale(targetSize.width, targetSize.height)
    }

    return try {
        val outputStream = ByteArrayOutputStream()
        if (!scaledBitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)) return null
        Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    } finally {
        if (scaledBitmap !== originalBitmap) scaledBitmap.recycle()
        if (ownsOriginalBitmap) originalBitmap.recycle()
    }
}
