package com.xrdavies.mob.vnc

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import com.xrdavies.mob.data.HttpStatusException
import com.xrdavies.mob.data.NEEDS_PAIRING
import com.xrdavies.mob.data.isUnauthorized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

enum class VncStatus { Idle, Connecting, Connected, Error }

data class VncState(
    val status: VncStatus = VncStatus.Idle,
    val detail: String = "",
    val width: Int = 0,
    val height: Int = 0,
    /** Touches and keys reach the office Mac only while true (full-screen Control mode). */
    val control: Boolean = false,
)

/**
 * The single live-view connection to the office Mac, through mobd's `/vnc` WebSocket relay.
 * The thumbnail and the full-screen view draw the same [bitmap]; only the full-screen view
 * sends input, and only in Control mode. Call from the main thread.
 */
class VncSession : com.xrdavies.mob.stream.LiveSession {
    private val _state = MutableStateFlow(VncState())
    override val state: StateFlow<VncState> = _state

    /** Bumped after every framebuffer update so views redraw. */
    private val _frame = MutableStateFlow(0L)
    val frame: StateFlow<Long> = _frame

    @Volatile
    var bitmap: Bitmap? = null
        private set

    private val main = Handler(Looper.getMainLooper())
    private var conn: Connection? = null

    /** [onUnauthorized] runs on the main thread when mobd refuses the device token. */
    fun connect(http: OkHttpClient, url: String, username: String, password: String, onUnauthorized: () -> Unit = {}) {
        disconnect()
        _state.value = VncState(VncStatus.Connecting, "Connecting…")
        com.xrdavies.mob.viewmodel.Log.add("live view: connecting to $url")
        conn = Connection(http, url, username, password, onUnauthorized)
    }

    override fun disconnect() {
        val c = conn ?: return
        conn = null
        c.close()
        bitmap = null
        _state.value = VncState(VncStatus.Idle, "Disconnected")
    }

    override fun setControl(on: Boolean) {
        val connected = _state.value.status == VncStatus.Connected
        _state.value = _state.value.copy(control = on && connected)
        if (!on) _modifiers.value = emptySet()
    }

    override fun click(x: Int, y: Int, button: Int) {
        pointer(x, y, 0)
        pointer(x, y, button)
        pointer(x, y, 0)
    }

    override fun pointer(x: Int, y: Int, buttons: Int) {
        if (_state.value.control) conn?.rfb?.pointer(x, y, buttons)
    }

    /** Modifiers latched from the keys bar; they apply to the next key, then release. */
    private val _modifiers = MutableStateFlow<Set<Int>>(emptySet())
    override val modifiers: StateFlow<Set<Int>> = _modifiers

    override fun toggleModifier(keysym: Int) {
        _modifiers.value = _modifiers.value.let { if (keysym in it) it - keysym else it + keysym }
    }

    /** Presses [keysym] with the latched modifiers (plus [extra]), then releases everything. */
    private fun press(rfb: Rfb, keysym: Int, extra: Set<Int> = emptySet()) {
        val mods = _modifiers.value + extra
        mods.forEach { rfb.key(it, true) }
        rfb.key(keysym, true)
        rfb.key(keysym, false)
        mods.reversed().forEach { rfb.key(it, false) }
        if (_modifiers.value.isNotEmpty()) _modifiers.value = emptySet()
    }

    override fun tapKey(keysym: Int) {
        val rfb = controlled() ?: return
        press(rfb, keysym)
    }

    /** Types one character from the phone's keyboard. */
    override fun type(c: Char) {
        val rfb = controlled() ?: return
        if (c == '\n') return press(rfb, XK_RETURN)
        val keysym = if (c.code < 0x100) c.code else 0x01000000 or c.code
        // With a latched modifier (⌘C) the key goes as its plain letter.
        val shortcut = _modifiers.value.isNotEmpty()
        val shift = !shortcut && (c.isUpperCase() || c in SHIFTED)
        press(rfb, if (shortcut) c.lowercaseChar().code else keysym, if (shift) setOf(XK_SHIFT_L) else emptySet())
    }

    /** Types text from the phone's clipboard (no shared clipboard with the Mac). */
    override fun typeText(text: String) {
        if (controlled() == null) return
        text.take(MAX_PASTE).forEach { type(it) }
    }

    /** Scroll wheel: one notch per step; negative dy scrolls up, negative dx left. */
    override fun scroll(x: Int, y: Int, dx: Int, dy: Int) {
        val rfb = controlled() ?: return
        fun notch(mask: Int, n: Int) = repeat(n) {
            rfb.pointer(x, y, mask)
            rfb.pointer(x, y, 0)
        }
        if (dy != 0) notch(if (dy < 0) WHEEL_UP else WHEEL_DOWN, kotlin.math.abs(dy))
        if (dx != 0) notch(if (dx < 0) WHEEL_LEFT else WHEEL_RIGHT, kotlin.math.abs(dx))
    }

    private fun controlled(): Rfb? = if (_state.value.control) conn?.rfb else null

    private inner class Connection(
        http: OkHttpClient,
        url: String,
        private val username: String,
        private val password: String,
        private val onUnauthorized: () -> Unit,
    ) : WebSocketListener() {
        private val input = WsInput()
        @Volatile var rfb: Rfb? = null
        @Volatile private var closed = false
        // An unchanged desktop sends nothing, so reads must not time out; pings keep the path alive.
        private val ws: WebSocket = http.newBuilder().readTimeout(0, TimeUnit.SECONDS).pingInterval(20, TimeUnit.SECONDS).build()
            .newWebSocket(Request.Builder().url(url).header("Sec-WebSocket-Protocol", "binary").build(), this)

        init {
            Thread(::run, "vnc").apply { isDaemon = true }.start()
        }

        fun close() {
            closed = true
            ws.cancel()
            input.fail(EOFException("closed"))
        }

        private fun run() {
            try {
                val rfb = Rfb(input, { ws.send(it.toByteString()) }, username, password)
                val fb = rfb.handshake()
                this.rfb = rfb
                resize(fb)
                rfb.run(onResize = ::resize, onUpdate = ::draw)
            } catch (e: Exception) {
                if (!closed) post {
                    conn = null
                    bitmap = null
                    _state.value = VncState(VncStatus.Error, e.message ?: e.javaClass.simpleName)
                    com.xrdavies.mob.viewmodel.Log.error("live view: ${e.message ?: e.javaClass.simpleName}")
                    if (e.isUnauthorized()) onUnauthorized()
                }
            }
        }

        private fun resize(fb: Framebuffer) {
            val bmp = Bitmap.createBitmap(fb.width, fb.height, Bitmap.Config.ARGB_8888)
            post {
                bitmap = bmp
                val s = _state.value
                if (s.status != VncStatus.Connected) com.xrdavies.mob.viewmodel.Log.add("live view: connected ${fb.width}×${fb.height}")
                _state.value = VncState(VncStatus.Connected, "", fb.width, fb.height, s.control && s.status == VncStatus.Connected)
            }
        }

        // Runs on the reader thread; a frame may tear while it is drawn, the next one fixes it.
        private fun draw(fb: Framebuffer, r: Dirty) {
            main.post {
                if (conn !== this) return@post
                val bmp = bitmap ?: return@post
                if (bmp.width != fb.width || bmp.height != fb.height) return@post
                bmp.setPixels(fb.pixels, r.y * fb.width + r.x, fb.width, r.x, r.y, r.w, r.h)
                _frame.value++
            }
        }

        private fun post(block: () -> Unit) = main.post { if (conn === this) block() }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = input.push(bytes.toByteArray())

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            input.fail(
                when {
                    t is HttpStatusException -> t
                    response != null -> HttpStatusException(response.code, httpError(response))
                    else -> IOException(t.message ?: "Connection failed")
                }
            )
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            input.fail(EOFException("The office Mac closed the live view"))
        }
    }

    /** mobd fails /vnc with JSON {"error": …} (for example when Screen Sharing is off). */
    private fun httpError(r: Response): String {
        if (r.code == 401) return NEEDS_PAIRING
        val body = runCatching { r.body?.string().orEmpty() }.getOrDefault("")
        val msg = runCatching { JSONObject(body).getString("error") }.getOrNull()
        return msg ?: "HTTP ${r.code}"
    }

    /** WebSocket messages as a blocking stream for the RFB reader. */
    private class WsInput : InputStream() {
        private val queue = LinkedBlockingQueue<ByteArray>()
        private val end = ByteArray(0)
        private var cur = ByteArray(0)
        private var pos = 0
        @Volatile private var error: IOException? = null

        fun push(b: ByteArray) = queue.put(b)

        fun fail(e: IOException) {
            if (error == null) error = e
            queue.put(end)
        }

        override fun read(): Int {
            val b = ByteArray(1)
            return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (pos >= cur.size) {
                val next = queue.take()
                if (next === end) {
                    queue.put(end)
                    throw error ?: EOFException()
                }
                cur = next
                pos = 0
            }
            val n = minOf(len, cur.size - pos)
            System.arraycopy(cur, pos, b, off, n)
            pos += n
            return n
        }
    }

    companion object {
        const val BUTTON_LEFT = 1
        const val BUTTON_RIGHT = 4
        const val WHEEL_UP = 1 shl 3
        const val WHEEL_DOWN = 1 shl 4
        const val WHEEL_LEFT = 1 shl 5
        const val WHEEL_RIGHT = 1 shl 6
        private const val MAX_PASTE = 5000

        const val XK_BACKSPACE = 0xff08
        const val XK_TAB = 0xff09
        const val XK_RETURN = 0xff0d
        const val XK_ESCAPE = 0xff1b
        const val XK_DELETE = 0xffff
        const val XK_LEFT = 0xff51
        const val XK_UP = 0xff52
        const val XK_RIGHT = 0xff53
        const val XK_DOWN = 0xff54
        const val XK_SHIFT_L = 0xffe1
        const val XK_CONTROL_L = 0xffe3
        /** macOS Screen Sharing maps Meta to Command (as svnc and the desktop app send it). */
        const val XK_META_L = 0xffe7
        const val XK_ALT_L = 0xffe9
        private const val SHIFTED = "~!@#$%^&*()_+{}|:\"<>?"
    }
}
