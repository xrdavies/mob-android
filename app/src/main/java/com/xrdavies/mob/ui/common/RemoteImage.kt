package com.xrdavies.mob.ui.common

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Artifacts never change, so decoded images are kept by URL (about 32 MB). */
private val cache = object : LruCache<String, Bitmap>(32 shl 20) {
    override fun sizeOf(key: String, value: Bitmap) = value.byteCount
}

private suspend fun load(http: OkHttpClient, url: String): Bitmap? {
    cache.get(url)?.let { return it }
    return withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) return@use null
                r.body?.byteStream()?.let(BitmapFactory::decodeStream)
            }
        }.getOrNull()?.also { cache.put(url, it) }
    }
}

/** An image from mobd; tap opens it full screen. */
@Composable
fun RemoteImage(http: OkHttpClient, url: String, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    var bitmap by remember(url) { mutableStateOf(cache.get(url)) }
    var zoom by remember { mutableStateOf(false) }
    LaunchedEffect(url) { if (bitmap == null) bitmap = load(http, url) }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant).clickable(enabled = bitmap != null) { zoom = true }, contentAlignment = Alignment.Center) {
        val b = bitmap
        if (b == null) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        else Image(b.asImageBitmap(), "Image", contentScale = contentScale, modifier = Modifier.fillMaxSize())
    }
    val b = bitmap
    if (zoom && b != null) {
        Dialog(onDismissRequest = { zoom = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black).clickable { zoom = false }, contentAlignment = Alignment.Center) {
                Image(b.asImageBitmap(), "Image", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
