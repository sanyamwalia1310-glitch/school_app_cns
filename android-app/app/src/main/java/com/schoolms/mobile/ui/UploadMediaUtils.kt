package com.schoolms.mobile.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.ktx.storage
import java.io.File
import java.io.FileOutputStream

private const val DEFAULT_UPLOAD_MAX_DIMENSION = 1280
private const val DEFAULT_UPLOAD_QUALITY = 82

fun Context.prepareOptimizedUpload(
    uri: Uri,
    cachePrefix: String,
    maxDimension: Int = DEFAULT_UPLOAD_MAX_DIMENSION,
    quality: Int = DEFAULT_UPLOAD_QUALITY
): Uri {
    if (!isImageUri(uri)) return uri
    val bitmap = decodeScaledBitmap(uri, maxDimension) ?: return uri
    return bitmapToTempUri(bitmap, cachePrefix, maxDimension, quality)
}

fun Context.prepareOptimizedUpload(
    bitmap: Bitmap,
    cachePrefix: String,
    maxDimension: Int = DEFAULT_UPLOAD_MAX_DIMENSION,
    quality: Int = DEFAULT_UPLOAD_QUALITY
): Uri {
    val scaled = scaleBitmapIfNeeded(bitmap, maxDimension)
    return bitmapToTempUri(scaled, cachePrefix, maxDimension, quality)
}

fun deleteStorageUrlIfPossible(url: String) {
    val trimmed = url.trim()
    if (trimmed.isBlank()) return
    runCatching {
        Firebase.storage.getReferenceFromUrl(trimmed).delete()
    }
}

private fun Context.isImageUri(uri: Uri): Boolean {
    return contentResolver.getType(uri)?.startsWith("image/") == true
}

private fun Context.decodeScaledBitmap(uri: Uri, maxDimension: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    contentResolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input, null, bounds)
    } ?: return null

    val sampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxDimension, maxDimension)
    val decodeOptions = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return contentResolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input, null, decodeOptions)
    }
}

private fun scaleBitmapIfNeeded(bitmap: Bitmap, maxDimension: Int): Bitmap {
    val longestSide = maxOf(bitmap.width, bitmap.height)
    if (longestSide <= maxDimension) return bitmap
    val scale = maxDimension.toFloat() / longestSide.toFloat()
    val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
    val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(bitmap, width, height, true)
}

private fun Context.bitmapToTempUri(
    bitmap: Bitmap,
    cachePrefix: String,
    maxDimension: Int,
    quality: Int
): Uri {
    val sanitizedPrefix = cachePrefix.replace("[^a-zA-Z0-9_-]".toRegex(), "_").ifBlank { "upload" }
    val finalBitmap = scaleBitmapIfNeeded(bitmap, maxDimension)
    val file = File(cacheDir, "${sanitizedPrefix}_${System.currentTimeMillis()}.jpg")
    FileOutputStream(file).use { out ->
        if (!finalBitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(50, 95), out)) {
            throw IllegalStateException("Unable to prepare image for upload")
        }
    }
    return Uri.fromFile(file)
}

private fun calculateInSampleSize(
    width: Int,
    height: Int,
    reqWidth: Int,
    reqHeight: Int
): Int {
    if (height <= 0 || width <= 0) return 1
    var sampleSize = 1
    var halfHeight = height / 2
    var halfWidth = width / 2
    while (halfHeight / sampleSize >= reqHeight && halfWidth / sampleSize >= reqWidth) {
        sampleSize *= 2
    }
    return sampleSize.coerceAtLeast(1)
}
