package com.xrdavies.mob.vnc

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
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
class VncSession {
    private val _state = MutableStateFlow(VncState())
    val state: StateFlow<VncState> = _state

    /** Bumped after every framebuffer update so views redraw. */
    private val _frame = MutableStateFlow(0L)
    val frame: StateFlow<Long> = _frame

    @Volatile
    var bitmap: Bitmap? = null
        private set

    private val main = Handler(Looper.getMainLooper())
    private var conn: Connection? = null

    fun connect(http: OkHttpClient, url: String, username: String, password: String) {
        disconnect()
        _state.value = VncState(VncStatus.Connecting, "Connecting…")
        conn = Connection(http, url, username, password)
    }

    fun disconnect() {
        val c = conn ?: return
        conn = null
        c.close()
        bitmap = null
        _state.value = VncState(VncStatus.Idle, "Disconnected")
    }

    fun setControl(on: Boolean) {
        val connected = _state.value.status == VncStatus.Connected
        _state.value = _state.value.copy(control = on && connected)
    }

    fun click(x: Int, y: Int, button: Int = BUTTON_LEFT) {
        pointer(x, y, 0)
        pointer(x, y, button)
        pointer(x, y, 0)
    }

    fun pointer(x: Int, y: Int, buttons: Int) {
        if (_state.value.control) conn?.rfb?.pointer(x, y, buttons)
    }

    fun tapKey(keysym: Int) {
        val rfb = controlled() ?: return
        rfb.key(keysym, true)
        rfb.key(keysym, false)
    }

    /** Types one character from the phone's keyboard. */
    fun type(c: Char) {
        val rfb = controlled() ?: return
        if (c == '\n') return tapKey(XK_RETURN)
        val keysym = if (c.code < 0x100) c.code else 0x01000000 or c.code
        val shift = c.isUpperCase() || c in SHIFTED
        if (shift) rfb.key(XK_SHIFT_L, true)
        rfb.key(keysym, true)
        rfb.key(keysym, false)
        if (shift) rfb.key(XK_SHIFT_L, false)
    }

    private fun controlled(): Rfb? = if (_state.value.control) conn?.rfb else null

    private inner class Connection(
        http: OkHttpClient,
        url: String,
        private val username: String,
        private val password: String,
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
                }
            }
        }

        private fun resize(fb: Framebuffer) {
            val bmp = Bitmap.createBitmap(fb.width, fb.height, Bitmap.Config.ARGB_8888)
            post {
                bitmap = bmp
                val s = _state.value
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
            input.fail(IOException(response?.let { httpError(it) } ?: t.message ?: "Connection failed"))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            input.fail(EOFException("The office Mac closed the live view"))
        }
    }

    /** mobd fails /vnc with JSON {"error": …} (for example when Screen Sharing is off). */
    private fun httpError(r: Response): String {
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
        const val XK_BACKSPACE = 0xff08
        const val XK_RETURN = 0xff0d
        const val XK_SHIFT_L = 0xffe1
        private const val SHIFTED = "~!@#$%^&*()_+{}|:\"<>?"
    }
}
