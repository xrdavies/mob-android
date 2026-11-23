package com.xrdavies.mob.data

import android.content.Context
import android.os.Build
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Whether a newer Mob for Android is out: the release history the release workflow publishes (the
 * same one mymob.dev/download is built from). The APK installs over this one; the app only says so
 * and links to it. Checked when Mob comes to the front, at most every few hours.
 */
object AppUpdate {
    const val MANIFEST = "https://dl.mymob.dev/android/releases.json"
    private const val INTERVAL_MS = 6 * 60 * 60 * 1000L

    data class Release(
        val version: String,
        @SerializedName("version_code") val versionCode: Long,
        @SerializedName("min_sdk") val minSdk: Int? = null,
        val download: Download?,
    ) {
        data class Download(val url: String?)
    }

    internal val state = MutableStateFlow<Release?>(null)
    /** The newer release this phone can install, or null. */
    val available: StateFlow<Release?> = state
    private var checkedAt = 0L

    private val http by lazy {
        OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).followRedirects(false).build()
    }

    /**
     * The newest release in [json] that is newer than [installed] and runs on [sdk]; its download
     * must be the APK under that release on the download host.
     */
    fun newer(json: String, installed: Long, sdk: Int): Release? {
        val list = runCatching { ApiClient.gson.fromJson(json, Array<Release>::class.java)?.toList() }.getOrNull() ?: return null
        return list.filter { r ->
            val url = r.download?.url.orEmpty()
            r.versionCode > installed && (r.minSdk ?: 0) <= sdk &&
                Regex("""\d+\.\d+\.\d+""").matches(r.version) &&
                url == "https://dl.mymob.dev/android/v${r.version}/mob-android_${r.version}.apk"
        }.maxByOrNull { it.versionCode }
    }

    suspend fun check(context: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - checkedAt < INTERVAL_MS) return
        checkedAt = now
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        val installed = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        val json = withContext(Dispatchers.IO) {
            runCatching {
                http.newCall(Request.Builder().url(MANIFEST).build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
            }.getOrNull()
        } ?: run { checkedAt = 0L; return } // offline: try again next time
        state.value = newer(json, installed, Build.VERSION.SDK_INT)
    }
}
