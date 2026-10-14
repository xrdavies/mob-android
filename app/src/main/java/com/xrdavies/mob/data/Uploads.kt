package com.xrdavies.mob.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException

// mobd accepts PNG, JPEG, GIF and WebP up to 20 MiB.
private val ACCEPTED = setOf("image/png", "image/jpeg", "image/gif", "image/webp")
private const val MAX_BYTES = 20 shl 20
private const val MAX_SIDE = 2560

/**
 * Uploads a picked image to POST /uploads. Accepted formats under 8 MB go as they are;
 * anything else (HEIC from the camera, huge photos) is re-encoded as JPEG, at most 2560 px.
 */
suspend fun ApiClient.uploadImage(context: Context, uri: Uri): Upload = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val mime = resolver.getType(uri).orEmpty()
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: "image"
    val original = resolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IOException("Cannot read $name")

    val (bytes, type, fileName) = if (mime in ACCEPTED && original.size <= 8 shl 20) {
        Triple(original, mime, name)
    } else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(original, 0, original.size, bounds)
        if (bounds.outWidth <= 0) throw IOException("$name is not an image this phone can read")
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        var bitmap = BitmapFactory.decodeByteArray(original, 0, original.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException("Cannot decode $name")
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest > MAX_SIDE) {
            val s = MAX_SIDE.toFloat() / longest
            bitmap = Bitmap.createScaledBitmap(bitmap, (bitmap.width * s).toInt(), (bitmap.height * s).toInt(), true)
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        Triple(out.toByteArray(), "image/jpeg", name.substringBeforeLast('.') + ".jpg")
    }
    if (bytes.size > MAX_BYTES) throw IOException("$name is larger than 20 MB")
    api.upload(MultipartBody.Part.createFormData("file", fileName, bytes.toRequestBody(type.toMediaType())))
}
