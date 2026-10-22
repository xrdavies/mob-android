package com.xrdavies.mob.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder

/** A pairing step failed; the message is meant for the user. */
class PairException(message: String) : IOException(message)

/**
 * A link printed by `mobd link`: `<origin>/pair#v=1&serverId=S&invite=I`.
 * Only the fragment carries the invite; it is never sent as part of a URL.
 */
data class PairLink(val origin: String, val invite: String, val serverId: String) {
    /** Plain HTTP: the token travels unencrypted. */
    val insecure: Boolean get() = origin.startsWith("http://")

    /** Same scheme, host and port as a saved mobd URL. */
    fun sameOrigin(url: String): Boolean {
        val a = origin.toHttpUrlOrNull() ?: return false
        val b = ApiClient.normalize(url)
        return a.scheme == b.scheme && a.host == b.host && a.port == b.port
    }

    companion object {
        fun parse(text: String): PairLink {
            val s = text.trim()
            if (s.isEmpty()) throw PairException("Paste the link printed by mobd link")
            val uri = try {
                URI(s)
            } catch (_: URISyntaxException) {
                throw PairException("Not a valid link")
            }
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") throw PairException("The link must start with http:// or https://")
            if (uri.rawUserInfo != null) throw PairException("The link must not contain a user name")
            val host = uri.host?.lowercase() ?: throw PairException("The link has no valid host")
            if (uri.rawPath != "/pair") throw PairException("Not a mobd pairing link: the path must be /pair")
            if (uri.rawQuery != null) throw PairException("Not a mobd pairing link: unexpected ?query")
            val fragment = uri.rawFragment?.takeIf { it.isNotEmpty() }
                ?: throw PairException("The link has no invite. Copy the whole link printed by mobd link")
            val params = mutableMapOf<String, String>()
            for (part in fragment.split('&')) {
                if (part.isEmpty()) continue
                val k = decode(part.substringBefore('='))
                if (params.put(k, decode(part.substringAfter('=', ""))) != null) throw PairException("The link repeats “$k”")
            }
            val v = params["v"] ?: throw PairException("The link has no version (v=1). Copy the whole link printed by mobd link")
            if (v != "1") throw PairException("This link needs a newer version of Mob (version $v)")
            val invite = params["invite"]?.takeIf { it.isNotBlank() } ?: throw PairException("The link has no invite")
            val serverId = params["serverId"]?.takeIf { it.isNotBlank() } ?: throw PairException("The link has no server ID")
            val port = if (uri.port == -1) "" else ":${uri.port}"
            return PairLink("$scheme://$host$port", invite, serverId)
        }

        private fun decode(s: String): String = try {
            URLDecoder.decode(s, "UTF-8")
        } catch (_: IllegalArgumentException) {
            throw PairException("The link is damaged")
        }
    }
}

data class PairResult(val serverId: String, val clientId: String, val token: String)

private data class PairRequest(val invite: String, val name: String)

private data class ErrorBody(val error: String?)

private data class PairResponse(val serverId: String?, val clientId: String?, val token: String?)

/** Checks a device name like mobd does: 1–128 UTF-8 bytes after trimming, no control characters. */
fun deviceNameError(name: String): String? {
    val n = name.trim()
    return when {
        n.isEmpty() -> "Enter a name for this device"
        n.toByteArray(Charsets.UTF_8).size > 128 -> "The name is too long"
        n.any { Character.isISOControl(it) } -> "The name must not contain control characters"
        else -> null
    }
}

/**
 * Redeems [link]'s invite with POST /pair on the link's origin. [client] must not add
 * Authorization; redirects are refused so the invite only goes where the link points.
 */
suspend fun pair(link: PairLink, name: String, client: OkHttpClient): PairResult = withContext(Dispatchers.IO) {
    deviceNameError(name)?.let { throw PairException(it) }
    val http = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    val body = ApiClient.gson.toJson(PairRequest(link.invite, name.trim())).toRequestBody("application/json".toMediaType())
    val request = Request.Builder().url("${link.origin}/pair").post(body).build()
    http.newCall(request).execute().use { r ->
        val text = r.body?.string().orEmpty()
        val error = runCatching { ApiClient.gson.fromJson(text, ErrorBody::class.java)?.error }.getOrNull()
        when (r.code) {
            201 -> {}
            401 -> throw PairException("This invite is invalid, expired or already used. Run mobd link on the office Mac again.")
            400 -> throw PairException("The office Mac rejected the device name" + (error?.let { ": $it" } ?: ""))
            503 -> throw PairException("The office Mac could not store the pairing. Check mobd on the office Mac and try again.")
            404 -> throw PairException("This mobd does not support pairing. Update mobd on the office Mac.")
            in 300..399 -> throw PairException("The office Mac answered with a redirect. Use the link exactly as mobd link printed it.")
            else -> throw PairException(error ?: "HTTP ${r.code}")
        }
        val res = runCatching { ApiClient.gson.fromJson(text, PairResponse::class.java) }.getOrNull()
        val serverId = res?.serverId?.takeIf { it.isNotBlank() }
        val clientId = res?.clientId?.takeIf { it.isNotBlank() }
        val token = res?.token?.takeIf { it.isNotBlank() }
        if (serverId == null || clientId == null || token == null) throw PairException("The office Mac sent an unexpected answer")
        if (serverId != link.serverId) {
            throw PairException("The office Mac answered with a different server ID than the link. Run mobd link again on the right Mac.")
        }
        PairResult(serverId, clientId, token)
    }
}
