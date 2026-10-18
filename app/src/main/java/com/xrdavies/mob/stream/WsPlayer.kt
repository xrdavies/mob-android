package com.xrdavies.mob.stream

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.webrtc.EglBase
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSink
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

// The live desktop over mobd's WebSocket fallback (mobd docs/desktop-streaming.md, "WebSocket
// fallback"), where WebRTC's UDP cannot pass. Binary messages from mobd are video:
//
//	byte 0     1 (H.264 access unit, Annex B)
//	bytes 1-8  capture time in microseconds, big endian
//	byte 9     flags: bit 0 keyframe
//	bytes 10-  access unit
//
// Text messages use the data-channel JSON schema both ways; every video message is acknowledged with
// {"t":"ack","pts":N}, which is how mobd measures the link. The Bearer header goes with the upgrade.

data class WsVideoMessage(val pts: Long, val key: Boolean, val data: ByteArray)

/** A binary message from mobd; null for anything that is not a video access unit. */
fun parseWsVideo(b: ByteArray): WsVideoMessage? {
    if (b.size < 11 || b[0].toInt() != 1) return null
    var pts = 0L
    for (i in 1..8) pts = (pts shl 8) or (b[i].toLong() and 0xff)
    return WsVideoMessage(pts, b[9].toInt() and 1 != 0, b.copyOfRange(10, b.size))
}

/** The NAL units of an Annex B access unit, without start codes. */
fun annexBUnits(b: ByteArray): List<ByteArray> {
    val starts = mutableListOf<Pair<Int, Int>>() // index, start code length
    var i = 0
    while (i + 2 < b.size) {
        if (b[i].toInt() == 0 && b[i + 1].toInt() == 0) {
            if (b[i + 2].toInt() == 1) { starts += i to 3; i += 3; continue }
            if (b[i + 2].toInt() == 0 && i + 3 < b.size && b[i + 3].toInt() == 1) { starts += i to 4; i += 4; continue }
        }
        i++
    }
    return starts.mapIndexed { n, (at, len) -> b.copyOfRange(at + len, if (n + 1 < starts.size) starts[n + 1].first else b.size) }
}

/** The SPS and PPS of a keyframe with start codes, as MediaCodec's csd-0 and csd-1; null without them. */
fun parameterSets(units: List<ByteArray>): Pair<ByteArray, ByteArray>? {
    val code = byteArrayOf(0, 0, 0, 1)
    val sps = units.firstOrNull { it.isNotEmpty() && it[0].toInt() and 0x1f == 7 } ?: return null
    val pps = units.firstOrNull { it.isNotEmpty() && it[0].toInt() and 0x1f == 8 } ?: return null
    return (code + sps) to (code + pps)
}

/**
 * One WebSocket session. Video is decoded by MediaCodec into a SurfaceTextureHelper, so frames arrive as
 * the same [org.webrtc.VideoFrame]s WebRTC produces and go to the same renderers ([sinks]).
 */
class WsPlayer(
    http: OkHttpClient,
    url: String,
    egl: EglBase.Context,
    private val sinks: () -> Collection<VideoSink>,
    private val events: Events,
) {
    interface Events {
        /** A control message (stats, ended, error, cursor); on the WebSocket's thread. */
        fun control(text: String)
        /** A decoded picture of this size reached the renderers; on the decoder thread. */
        fun frame(width: Int, height: Int)
        /** The socket closed; `opened` tells a refused connection from a lost one. */
        fun close(code: Int, opened: Boolean)
    }

    private val thread = HandlerThread("mob-ws-decoder").apply { start() }
    private val handler = Handler(thread.looper)
    private val helper = SurfaceTextureHelper.create("mob-ws-frames", egl)
    private val surface = Surface(helper.surfaceTexture)
    private var codec: MediaCodec? = null
    private var codecName = ""
    private var csd: Pair<ByteArray, ByteArray>? = null
    private val pending = ArrayDeque<WsVideoMessage>()
    private val inputs = ArrayDeque<Int>()
    /** The last access unit fed to the decoder, and whether a picture has come out since. */
    private var lastInput: WsVideoMessage? = null
    private var outputSinceInput = false
    private var repeats = 0
    /**
     * Software decoders (the emulator's, some phones') hold a picture until more input arrives, and an
     * idle desktop sends nothing after a keyframe. Feeding the same keyframe again pushes the held
     * picture out unchanged; never a delta frame, which would decode against the wrong reference.
     */
    private val flushHeld = Runnable {
        val m = lastInput ?: return@Runnable
        if (outputSinceInput || !m.key || pending.isNotEmpty() || repeats >= 3 || codec == null) return@Runnable
        repeats++
        pending.add(m.copy(pts = m.pts + repeats))
        feed()
    }
    @Volatile private var closed = false
    @Volatile private var opened = false
    @Volatile private var lastKeyRequest = 0L
    @Volatile private var size = 0 to 0

    /** Totals for the readout. */
    @Volatile var bytes = 0L
        private set
    @Volatile var frames = 0L
        private set

    private val ws: WebSocket

    init {
        helper.startListening { frame ->
            frames++
            sinks().forEach { it.onFrame(frame) }
            events.frame(frame.rotatedWidth, frame.rotatedHeight)
        }
        // No read timeout: an idle desktop sends nothing for minutes; pings keep the path open.
        val client = http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(15, TimeUnit.SECONDS).build()
        ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { opened = true }
            override fun onMessage(webSocket: WebSocket, text: String) { if (!closed) events.control(text) }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { if (!closed) video(bytes.toByteArray()) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = ended(code)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                android.util.Log.w("mob-ws", "WebSocket failed (${response?.code ?: "no response"})", t)
                ended(if (opened) 1006 else response?.code ?: 0)
            }
        })
    }

    fun send(json: String) {
        if (!closed) ws.send(json)
    }

    /** Asks mobd for a keyframe, at most once a second (a new view, a decoder restarted). */
    fun askKeyframe() {
        val now = System.currentTimeMillis()
        if (now - lastKeyRequest < 1000) return
        lastKeyRequest = now
        send("""{"t":"keyframe"}""")
    }

    fun close() {
        if (closed) return
        closed = true
        ws.close(1000, null)
        handler.post {
            stopCodec()
            helper.stopListening()
            helper.dispose()
            surface.release()
            thread.quitSafely()
        }
    }

    private fun ended(code: Int) {
        if (closed) return
        closed = true
        handler.post {
            stopCodec()
            helper.stopListening()
            helper.dispose()
            surface.release()
            thread.quitSafely()
        }
        events.close(code, opened)
    }

    private fun video(data: ByteArray) {
        val m = parseWsVideo(data) ?: return
        send("""{"t":"ack","pts":${m.pts}}""") // on receipt: mobd times the link from these
        bytes += data.size
        handler.post { decode(m) }
    }

    // Decoder thread from here on.

    private fun decode(m: WsVideoMessage) {
        if (closed) return
        if (m.key) {
            val sets = parameterSets(annexBUnits(m.data))
            // A new size comes with new parameter sets: a fresh decoder, configured for them.
            if (sets != null && (codec == null || !sets.first.contentEquals(csd?.first) || !sets.second.contentEquals(csd?.second))) {
                startCodec(sets)
            }
        }
        if (codec == null) return askKeyframe()
        if (pending.size > 8 && !m.key) {
            // Falling behind: an ever older picture is worse than a skip to the next keyframe.
            pending.clear()
            askKeyframe()
            return
        }
        pending.add(m)
        feed()
    }

    private fun feed() {
        val c = codec ?: return
        while (pending.isNotEmpty() && inputs.isNotEmpty()) {
            val m = pending.poll()!!
            val i = inputs.poll()!!
            try {
                val buf = c.getInputBuffer(i) ?: continue
                buf.clear()
                buf.put(m.data)
                c.queueInputBuffer(i, 0, m.data.size, m.pts, 0)
                if (lastInput?.data !== m.data) repeats = 0
                lastInput = m
                outputSinceInput = false
                handler.removeCallbacks(flushHeld)
                handler.postDelayed(flushHeld, 120)
            } catch (e: Exception) {
                return fail(e)
            }
        }
    }

    private fun startCodec(sets: Pair<ByteArray, ByteArray>) {
        stopCodec()
        csd = sets
        // The emulator's hardware decoder can refuse; try each H.264 decoder in the platform's order.
        val names = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
            .map { it.name }
            .filter { it !in broken }
        for (name in names) {
            val c = try { MediaCodec.createByCodecName(name) } catch (e: Exception) { continue }
            try {
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080).apply {
                    setByteBuffer("csd-0", ByteBuffer.wrap(sets.first))
                    setByteBuffer("csd-1", ByteBuffer.wrap(sets.second))
                    // A remote screen, not a movie: hand out each picture as soon as it is decoded.
                    if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
                c.setCallback(object : MediaCodec.Callback() {
                    override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                        if (codec !== this@WsPlayer.codec) return
                        inputs.add(index)
                        feed()
                    }
                    override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                        if (codec !== this@WsPlayer.codec) return
                        outputSinceInput = true
                        try { codec.releaseOutputBuffer(index, true) } catch (_: Exception) {}
                    }
                    override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                        val w = if (format.containsKey("crop-right")) format.getInteger("crop-right") - format.getInteger("crop-left") + 1 else format.getInteger(MediaFormat.KEY_WIDTH)
                        val h = if (format.containsKey("crop-bottom")) format.getInteger("crop-bottom") - format.getInteger("crop-top") + 1 else format.getInteger(MediaFormat.KEY_HEIGHT)
                        size = w to h
                        helper.setTextureSize(w, h)
                    }
                    override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                        if (codec === this@WsPlayer.codec) fail(e)
                    }
                }, handler)
                c.configure(format, surface, null, 0)
                c.start()
                codec = c
                codecName = name
                android.util.Log.i("mob-ws", "decoding with $name")
                return
            } catch (e: Exception) {
                try { c.release() } catch (_: Exception) {}
            }
        }
    }

    private fun stopCodec() {
        handler.removeCallbacks(flushHeld)
        lastInput = null
        val c = codec ?: return
        codec = null
        inputs.clear()
        try { c.stop() } catch (_: Exception) {}
        try { c.release() } catch (_: Exception) {}
    }

    companion object {
        /** Decoders that failed while running (the emulator's hardware H.264 refuses parameter sets); for the process. */
        private val broken = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    }

    /** A decoder failed while running: the next one, from the next keyframe. */
    private fun fail(e: Exception) {
        android.util.Log.w("mob-ws", "decoder $codecName failed", e)
        broken += codecName
        restart()
    }

    /** After a decoder error: a fresh decoder from the next keyframe. */
    private fun restart() {
        stopCodec()
        csd = null
        pending.clear()
        askKeyframe()
    }
}
