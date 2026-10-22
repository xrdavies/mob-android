package com.xrdavies.mob.ui.settings

import androidx.annotation.RawRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xrdavies.mob.R

/** A shipped library and its licence; [text] is the licence's full text in res/raw. */
data class Acknowledgement(val name: String, val license: String, val url: String, @RawRes val text: Int? = null)

/** mob-android and the code it ships: the app's dependencies, and the components inside libwebrtc (read from its binary). */
object Acknowledgements {
    val app = Acknowledgement("Mob for Android", "MIT", "https://github.com/xrdavies/mob-android", R.raw.license_mob)

    val libraries = listOf(
        Acknowledgement("WebRTC (libwebrtc, io.github.webrtc-sdk:android)", "BSD 3-Clause", "https://github.com/webrtc-sdk/webrtc", R.raw.license_webrtc),
        Acknowledgement("AndroidX and Jetpack Compose", "Apache 2.0", "https://developer.android.com/jetpack/androidx", R.raw.license_apache2),
        Acknowledgement("Kotlin coroutines", "Apache 2.0", "https://github.com/Kotlin/kotlinx.coroutines", R.raw.license_apache2),
        Acknowledgement("OkHttp", "Apache 2.0", "https://square.github.io/okhttp/", R.raw.license_apache2),
        Acknowledgement("Retrofit", "Apache 2.0", "https://square.github.io/retrofit/", R.raw.license_apache2),
        Acknowledgement("Gson", "Apache 2.0", "https://github.com/google/gson", R.raw.license_apache2),
        Acknowledgement("ZXing and ZXing Android Embedded", "Apache 2.0", "https://github.com/journeyapps/zxing-android-embedded", R.raw.license_apache2),
    )

    /** Third-party code inside the WebRTC library, each under its own licence. */
    val inWebRtc = listOf(
        Acknowledgement("BoringSSL", "Apache 2.0; older parts OpenSSL and ISC", "https://boringssl.googlesource.com/boringssl"),
        Acknowledgement("Abseil", "Apache 2.0", "https://github.com/abseil/abseil-cpp"),
        Acknowledgement("dav1d", "BSD 2-Clause", "https://code.videolan.org/videolan/dav1d"),
        Acknowledgement("libaom", "BSD 2-Clause, AOM Patent License 1.0", "https://aomedia.googlesource.com/aom"),
        Acknowledgement("libsrtp", "BSD 3-Clause", "https://github.com/cisco/libsrtp"),
        Acknowledgement("libvpx", "BSD 3-Clause", "https://chromium.googlesource.com/webm/libvpx"),
        Acknowledgement("libyuv", "BSD 3-Clause", "https://chromium.googlesource.com/libyuv/libyuv"),
        Acknowledgement("Opus", "BSD 3-Clause", "https://opus-codec.org"),
    )
}

/** Settings → About → Open source licences. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AcknowledgementsDialog(onDismiss: () -> Unit) {
    var open by remember { mutableStateOf<Acknowledgement?>(null) }
    val uri = LocalUriHandler.current
    Dialog(onDismissRequest = { if (open != null) open = null else onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                TopAppBar(
                    title = { Text(open?.name ?: "Open source licences", maxLines = 1) },
                    navigationIcon = {
                        IconButton(onClick = { if (open != null) open = null else onDismiss() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    },
                )
                val item = open
                if (item != null) {
                    LicenseText(item)
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        item { Row(Acknowledgements.app) { open = it } }
                        item { Header("Libraries") }
                        items(Acknowledgements.libraries) { Row(it) { a -> open = a } }
                        item { Header("Included in WebRTC") }
                        items(Acknowledgements.inWebRtc) { a -> Row(a) { uri.openUri(a.url) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
}

@Composable
private fun Row(a: Acknowledgement, onClick: (Acknowledgement) -> Unit) {
    ListItem(
        headlineContent = { Text(a.name) },
        supportingContent = { Text(a.license) },
        modifier = Modifier.clickable { onClick(a) },
    )
}

@Composable
private fun LicenseText(a: Acknowledgement) {
    val context = LocalContext.current
    val text = remember(a) { a.text?.let { context.resources.openRawResource(it).bufferedReader().use { r -> r.readText() } }.orEmpty() }
    val uri = LocalUriHandler.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        TextButton(onClick = { uri.openUri(a.url) }, contentPadding = PaddingValues(0.dp)) { Text(a.url) }
        SelectionContainer {
            Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}
