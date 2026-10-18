package com.xrdavies.mob.stream

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.xrdavies.mob.stream.LiveKeys
import kotlin.math.ceil
import kotlin.math.roundToInt

// The live desktop protocol (mobd docs/desktop-streaming.md, "Data channels"): small JSON messages
// with a `t` field; coordinates normalized to the display (0,0 top left, 1,1 bottom right).
// Kept free of Android classes so it runs in JVM unit tests.

private val gson = Gson()

const val UPDATE_MOBD = "Update mobd on the office Mac to use the live desktop"

/** mobd's quality profiles (internal/stream/stream.go), with the hints the desktop app shows. */
enum class LiveQuality(val wire: String, val label: String, val hint: String) {
    Auto("auto", "Auto", "Starts at 720p and follows the connection, up to 1080p at 30 fps and 6 Mbps"),
    High("high", "High", "Starts sharp at 3 Mbps, then follows the connection, up to 1080p at 30 fps and 6 Mbps"),
    Low("low", "Low", "For slow or metered links: up to 720p at 15 fps and 1 Mbps");

    companion object {
        fun of(wire: String?): LiveQuality = entries.firstOrNull { it.wire == wire } ?: Auto
    }
}

/** Device pixels asked of mobd, rounded up to 64 so small layout changes reuse the size. */
fun devicePixels(px: Int): Int = (ceil(px / 64.0).toInt() * 64).coerceIn(64, 8192)

/** A point in desktop pixels of the encoded picture ([w] × [h]) as mobd's 0..1 coordinates. */
fun normalize(x: Int, y: Int, w: Int, h: Int): Pair<Double, Double> {
    fun n(v: Int, size: Int) = if (size <= 1) 0.0 else ((v + 0.5) / size).coerceIn(0.0, 1.0)
    return n(x, w) to n(y, h)
}

private fun round(v: Double) = (v * 10000).roundToInt() / 10000.0

fun moveMessage(x: Double, y: Double): String = gson.toJson(mapOf("t" to "move", "x" to round(x), "y" to round(y)))

fun buttonMessage(x: Double, y: Double, button: String, down: Boolean): String =
    gson.toJson(mapOf("t" to "button", "x" to round(x), "y" to round(y), "button" to button, "down" to down))

fun keyMessage(code: String, down: Boolean): String = gson.toJson(mapOf("t" to "key", "code" to code, "down" to down))

fun textMessage(text: String): String = gson.toJson(mapOf("t" to "text", "text" to text))

fun scrollMessage(x: Double, y: Double, dx: Double, dy: Double): String =
    gson.toJson(mapOf("t" to "scroll", "x" to round(x), "y" to round(y), "dx" to dx.roundToInt(), "dy" to dy.roundToInt()))

fun keyframeMessage(): String = gson.toJson(mapOf("t" to "keyframe"))

fun resizeMessage(maxWidth: Int, maxHeight: Int): String =
    gson.toJson(mapOf("t" to "resize", "maxWidth" to maxWidth, "maxHeight" to maxHeight))

/** mobd's text limit per message; longer pastes are split. */
const val MAX_TEXT = 4096

/** The keys bar's keysyms ([LiveKeys].XK_*) as web `KeyboardEvent.code` values. */
fun keyCode(keysym: Int): String? = when (keysym) {
    LiveKeys.XK_BACKSPACE -> "Backspace"
    LiveKeys.XK_TAB -> "Tab"
    LiveKeys.XK_RETURN -> "Enter"
    LiveKeys.XK_ESCAPE -> "Escape"
    LiveKeys.XK_DELETE -> "Delete"
    LiveKeys.XK_LEFT -> "ArrowLeft"
    LiveKeys.XK_UP -> "ArrowUp"
    LiveKeys.XK_RIGHT -> "ArrowRight"
    LiveKeys.XK_DOWN -> "ArrowDown"
    LiveKeys.XK_SHIFT_L -> "ShiftLeft"
    LiveKeys.XK_CONTROL_L -> "ControlLeft"
    LiveKeys.XK_META_L -> "MetaLeft"
    LiveKeys.XK_ALT_L -> "AltLeft"
    else -> null
}

/** The physical key for a character typed with a latched modifier (⌘C → KeyC), or null. */
fun shortcutCode(c: Char): String? = when (val l = c.lowercaseChar()) {
    in 'a'..'z' -> "Key" + l.uppercaseChar()
    in '0'..'9' -> "Digit$l"
    ' ' -> "Space"
    '\n' -> "Enter"
    '-' -> "Minus"
    '=' -> "Equal"
    '[' -> "BracketLeft"
    ']' -> "BracketRight"
    ';' -> "Semicolon"
    '\'' -> "Quote"
    ',' -> "Comma"
    '.' -> "Period"
    '/' -> "Slash"
    '\\' -> "Backslash"
    '`' -> "Backquote"
    else -> null
}

/** Button bits ([LiveKeys].BUTTON_*) to mobd's button names. */
fun buttonName(bit: Int): String? = when (bit) {
    LiveKeys.BUTTON_LEFT -> "left"
    2 -> "middle"
    LiveKeys.BUTTON_RIGHT -> "right"
    else -> null
}

sealed class Control {
    data class Stats(val kbps: Int, val width: Int, val height: Int, val fps: Int, val loss: Double, val rttMs: Int) : Control()
    data class Ended(val message: String) : Control()
    data class Error(val about: String, val message: String) : Control()
    data class Cursor(val x: Double, val y: Double) : Control()
}

/** A message from mobd's control channel; null for anything unknown or malformed. */
fun parseControl(text: String): Control? {
    val o = runCatching { JsonParser().parse(text).asJsonObject }.getOrNull() ?: return null
    fun num(k: String) = runCatching { o.get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble }.getOrNull() ?: 0.0
    fun str(k: String) = runCatching { o.get(k)?.takeIf { it.isJsonPrimitive }?.asString }.getOrNull().orEmpty()
    return when (str("t")) {
        "stats" -> Control.Stats(num("kbps").toInt(), num("width").toInt(), num("height").toInt(), num("fps").toInt(), num("loss"), num("rttMs").toInt())
        "ended" -> Control.Ended(str("message").ifBlank { "The office Mac ended the live view" })
        "error" -> Control.Error(str("about"), str("message"))
        "cursor" -> Control.Cursor(num("x"), num("y"))
        else -> null
    }
}

/** POST /stream's answer. */
data class StreamAnswer(val session: String, val answer: String?, val width: Int, val height: Int)

fun parseAnswer(body: String): StreamAnswer? {
    val o: JsonObject = runCatching { JsonParser().parse(body).asJsonObject }.getOrNull() ?: return null
    val session = runCatching { o.get("session")?.asString }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
    return StreamAnswer(
        session,
        runCatching { o.get("answer")?.asString }.getOrNull()?.takeIf { it.isNotBlank() },
        runCatching { o.get("width")?.asInt }.getOrNull() ?: 0,
        runCatching { o.get("height")?.asInt }.getOrNull() ?: 0,
    )
}

/** mobd's `{"error": "..."}`, else the status. */
fun errorText(body: String, code: Int): String =
    runCatching { JsonParser().parse(body).asJsonObject.get("error").asString }.getOrNull()?.takeIf { it.isNotBlank() } ?: "HTTP $code"

fun openRequest(offer: String, maxWidth: Int, maxHeight: Int, quality: LiveQuality): String =
    gson.toJson(mapOf("offer" to offer, "maxWidth" to maxWidth, "maxHeight" to maxHeight, "quality" to quality.wire))

enum class Network(val label: String) { LAN("LAN"), Tailscale("Tailscale"), Internet("Internet"), Relay("relay") }

/** Where the peer's address lives: Tailscale (100.64.0.0/10, fd7a:115c:a1e0::/48), a private LAN, or beyond. */
fun networkOf(address: String, relayed: Boolean = false): Network {
    if (relayed) return Network.Relay
    val a = address.lowercase().trim('[', ']')
    val v4 = Regex("""^(\d+)\.(\d+)\.\d+\.\d+$""").find(a)
    if (v4 != null) {
        val x = v4.groupValues[1].toInt()
        val y = v4.groupValues[2].toInt()
        if (x == 100 && y in 64..127) return Network.Tailscale
        if (x == 10 || x == 127 || (x == 172 && y in 16..31) || (x == 192 && y == 168) || (x == 169 && y == 254)) return Network.LAN
        return Network.Internet
    }
    if (a.startsWith("fd7a:115c:a1e0:")) return Network.Tailscale
    if (Regex("^(fe8|fe9|fea|feb|fc|fd)").containsMatchIn(a) || a == "::1") return Network.LAN
    return Network.Internet
}

/** Candidate addresses in an SDP, keyed "protocol:port", for stats that leave the address out. */
fun candidateAddresses(sdp: String): Map<String, String> =
    Regex("""(?m)^a=candidate:\S+ \d+ (\S+) \d+ (\S+) (\d+) typ""").findAll(sdp)
        .associate { "${it.groupValues[1].lowercase()}:${it.groupValues[3]}" to it.groupValues[2] }

/** One getStats() entry, as RTCStats exposes it. */
data class StatEntry(val type: String, val id: String, val members: Map<String, Any?>)

data class StatsSample(val atMs: Long, val bytes: Double, val frames: Double)

/** What the viewer actually receives, once a second. */
data class Readout(val kBps: Int, val fps: Int, val network: Network? = null, val rttMs: Int? = null, val transport: String = "WebRTC")

private fun Any?.num(): Double? = (this as? Number)?.toDouble()

/** One stats report against the previous sample. */
fun readStats(report: List<StatEntry>, prev: StatsSample?, nowMs: Long, remoteAddresses: Map<String, String> = emptyMap()): Pair<StatsSample, Readout> {
    val byId = report.associateBy { it.id }
    val video = report.firstOrNull { it.type == "inbound-rtp" && (it.members["kind"] ?: it.members["mediaType"]) == "video" }
    val sample = StatsSample(nowMs, video?.members?.get("bytesReceived").num() ?: 0.0, video?.members?.get("framesDecoded").num() ?: 0.0)
    val seconds = if (prev != null) (nowMs - prev.atMs) / 1000.0 else 0.0
    var readout = Readout(
        kBps = if (seconds > 0) ((sample.bytes - prev!!.bytes) / 1000 / seconds).roundToInt().coerceAtLeast(0) else 0,
        fps = if (seconds > 0) ((sample.frames - prev!!.frames) / seconds).roundToInt().coerceAtLeast(0) else 0,
    )
    val transport = report.firstOrNull { it.type == "transport" && it.members["selectedCandidatePairId"] != null }
    val pair = transport?.let { byId[it.members["selectedCandidatePairId"].toString()] }
        ?: report.firstOrNull { it.type == "candidate-pair" && (it.members["selected"] == true || (it.members["nominated"] == true && it.members["state"] == "succeeded")) }
    if (pair != null) {
        val remote = byId[pair.members["remoteCandidateId"].toString()]
        val local = byId[pair.members["localCandidateId"].toString()]
        val address = (remote?.members?.get("address") ?: remote?.members?.get("ip"))?.toString()
            ?: remoteAddresses["${(remote?.members?.get("protocol") ?: "udp").toString().lowercase()}:${remote?.members?.get("port").num()?.toInt()}"]
            ?: ""
        val relayed = remote?.members?.get("candidateType") == "relay" || local?.members?.get("candidateType") == "relay"
        val rtt = pair.members["currentRoundTripTime"].num()
        readout = readout.copy(
            network = if (address.isNotBlank() || relayed) networkOf(address, relayed) else null,
            rttMs = rtt?.let { (it * 1000).roundToInt() },
        )
    }
    return sample to readout
}

/** "1408×792 · 30 fps · 380 kB/s · WebRTC · LAN". */
fun readoutText(width: Int, height: Int, r: Readout?): String {
    val parts = mutableListOf("$width×$height")
    if (r != null) parts += listOf("${r.fps} fps", "${r.kBps} kB/s", r.transport)
    r?.network?.let { parts += it.label }
    return parts.joinToString(" · ")
}

/** Signal bars (1-3), as on the desktop: mobd's reported loss and the measured round-trip time. */
fun signalBars(loss: Double, rttMs: Int?): Int {
    val rtt = rttMs ?: 0
    return when {
        loss > 0.1 || rtt > 400 -> 1
        loss > 0.02 || rtt > 150 -> 2
        else -> 3
    }
}
