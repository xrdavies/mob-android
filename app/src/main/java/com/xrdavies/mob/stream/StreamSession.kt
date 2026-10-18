package com.xrdavies.mob.stream

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.xrdavies.mob.data.ApiClient
import com.xrdavies.mob.data.isUnauthorized
import com.xrdavies.mob.viewmodel.Log
import com.xrdavies.mob.vnc.VncSession
import com.xrdavies.mob.vnc.VncState
import com.xrdavies.mob.vnc.VncStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import java.io.IOException
import java.nio.ByteBuffer

/** Measured once a second while connected, plus mobd's own view of the link. */
data class StreamStats(val readout: Readout? = null, val kbps: Int = 0, val loss: Double = 0.0)

/**
 * The live desktop over WebRTC (mobd docs/desktop-streaming.md): one non-trickle POST /stream with
 * the offer, a receive-only H.264 track drawn by [StreamVideo]s, and the `control`, `input` and
 * `pointer` (unreliable, latest wins) data channels. One session is shared by the thumbnail and
 * the full-screen view; it is sized to the largest view showing it. Call from the main thread.
 */
class StreamSession(context: Context) : LiveSession {
    private val appContext = context.applicationContext
    private val _state = MutableStateFlow(VncState())
    override val state: StateFlow<VncState> = _state

    private val _stats = MutableStateFlow(StreamStats())
    val stats: StateFlow<StreamStats> = _stats

    /** Where mobd last reported the office pointer, normalized (0..1). */
    private val _cursor = MutableStateFlow<Pair<Double, Double>?>(null)
    val cursor: StateFlow<Pair<Double, Double>?> = _cursor

    private val _modifiers = MutableStateFlow<Set<Int>>(emptySet())
    override val modifiers: StateFlow<Set<Int>> = _modifiers
    override val pixelScroll: Boolean get() = true

    private val main = Handler(Looper.getMainLooper())
    private var conn: Conn? = null
    private var attempt = 0
    private val sinks = mutableSetOf<VideoSink>()

    /** The views showing the stream, in device pixels; the stream is fitted to the largest. */
    private val views = mutableMapOf<String, Pair<Int, Int>>()
    private var fallbackSize = 1280 to 800
    private var opened = 0 to 0
    private var canResize = false
    private val resizeTask = Runnable { refit() }

    val eglContext: EglBase.Context get() = egl.eglBaseContext

    /** [onUnauthorized] runs on the main thread when mobd refuses the device token. */
    fun connect(client: ApiClient, quality: LiveQuality, canResize: Boolean, screen: Pair<Int, Int>, control: Boolean = false, onUnauthorized: () -> Unit = {}) {
        disconnect()
        this.canResize = canResize
        fallbackSize = screen
        val n = ++attempt
        _state.value = VncState(VncStatus.Connecting, "Connecting…")
        _stats.value = StreamStats()
        opened = target()
        Log.add("live view: WebRTC to ${client.baseUrl} at ${opened.first}×${opened.second} (${quality.wire})")
        conn = Conn(n, client, quality, control, onUnauthorized).also { it.start() }
    }

    override fun disconnect() {
        attempt++
        main.removeCallbacks(resizeTask)
        val c = conn ?: return
        conn = null
        c.close()
        _modifiers.value = emptySet()
        _state.value = VncState(VncStatus.Idle, "Disconnected")
        _stats.value = StreamStats()
    }

    fun addSink(sink: VideoSink) {
        sinks += sink
        conn?.track?.addSink(sink)
    }

    fun removeSink(sink: VideoSink) {
        sinks -= sink
        conn?.track?.removeSink(sink)
    }

    /** A view showing the stream changed size (pixels); null when it goes away. */
    fun reportView(key: String, size: Pair<Int, Int>?) {
        if (size == null) views.remove(key) else views[key] = devicePixels(size.first) to devicePixels(size.second)
        main.removeCallbacks(resizeTask)
        main.postDelayed(resizeTask, 300)
    }

    private fun target(): Pair<Int, Int> =
        if (views.isEmpty()) devicePixels(fallbackSize.first) to devicePixels(fallbackSize.second)
        else views.values.maxOf { it.first } to views.values.maxOf { it.second }

    /** Fits the running stream to the views: a resize message, or a reconnect for older mobd. */
    private fun refit() {
        val c = conn ?: return
        if (_state.value.status != VncStatus.Connected) return
        val want = target()
        if (want == opened) return
        if (canResize && c.send(c.control, resizeMessage(want.first, want.second))) {
            opened = want
            c.nudge()
        } else {
            connect(c.client, c.quality, canResize, fallbackSize, _state.value.control, c.onUnauthorized)
        }
    }

    override fun setControl(on: Boolean) {
        val control = on && _state.value.status == VncStatus.Connected
        if (!control) release()
        _state.value = _state.value.copy(control = control)
    }

    // Input arrives in pixels of the picture; mobd wants 0..1 of the display.
    private var buttons = 0
    private var last = 0 to 0

    private fun at(x: Int, y: Int) = _state.value.let { normalize(x, y, it.width, it.height) }

    private fun controlled(): Conn? = if (_state.value.control) conn else null

    override fun click(x: Int, y: Int, button: Int) {
        val c = controlled() ?: return
        val name = buttonName(button) ?: return
        val (nx, ny) = at(x, y)
        c.send(c.input, buttonMessage(nx, ny, name, true))
        c.send(c.input, buttonMessage(nx, ny, name, false))
    }

    /** RFB-style: [mask] is the buttons held now; changes become button messages, the rest moves. */
    override fun pointer(x: Int, y: Int, buttons: Int) {
        val c = controlled() ?: return
        val (nx, ny) = at(x, y)
        last = x to y
        val changed = buttons xor this.buttons
        if (changed == 0) {
            c.send(c.pointer, moveMessage(nx, ny))
            return
        }
        for (bit in listOf(VncSession.BUTTON_LEFT, 2, VncSession.BUTTON_RIGHT)) {
            if (changed and bit == 0) continue
            buttonName(bit)?.let { c.send(c.input, buttonMessage(nx, ny, it, buttons and bit != 0)) }
        }
        this.buttons = buttons
    }

    override fun scroll(x: Int, y: Int, dx: Int, dy: Int) = scrollPixels(x, y, dx * 40f, dy * 40f)

    override fun scrollPixels(x: Int, y: Int, dx: Float, dy: Float) {
        val c = controlled() ?: return
        val (nx, ny) = at(x, y)
        c.send(c.input, scrollMessage(nx, ny, dx.toDouble(), dy.toDouble()))
    }

    override fun toggleModifier(keysym: Int) {
        _modifiers.value = _modifiers.value.let { if (keysym in it) it - keysym else it + keysym }
    }

    /** Presses [code] with the latched modifiers, then releases everything. */
    private fun press(c: Conn, code: String) {
        val mods = _modifiers.value.mapNotNull(::keyCode)
        mods.forEach { c.send(c.input, keyMessage(it, true)) }
        c.send(c.input, keyMessage(code, true))
        c.send(c.input, keyMessage(code, false))
        mods.reversed().forEach { c.send(c.input, keyMessage(it, false)) }
        if (_modifiers.value.isNotEmpty()) _modifiers.value = emptySet()
    }

    override fun tapKey(keysym: Int) {
        val c = controlled() ?: return
        press(c, keyCode(keysym) ?: return)
    }

    override fun type(c: Char) {
        val conn = controlled() ?: return
        if (c == '\n') return press(conn, "Enter")
        // With a latched modifier (⌘C) the key goes as its physical key; otherwise as Unicode text.
        if (_modifiers.value.isNotEmpty()) shortcutCode(c)?.let { return press(conn, it) }
        conn.send(conn.input, textMessage(c.toString()))
    }

    override fun typeText(text: String) {
        val c = controlled() ?: return
        text.chunked(MAX_TEXT).forEach { c.send(c.input, textMessage(it)) }
    }

    /** Lets go of held buttons and latched modifiers (View mode, background, disconnect). */
    private fun release() {
        val c = conn
        if (c != null && buttons != 0) {
            val (nx, ny) = at(last.first, last.second)
            for (bit in listOf(VncSession.BUTTON_LEFT, 2, VncSession.BUTTON_RIGHT)) {
                if (buttons and bit != 0) buttonName(bit)?.let { c.send(c.input, buttonMessage(nx, ny, it, false)) }
            }
        }
        buttons = 0
        _modifiers.value = emptySet()
    }

    private inner class Conn(
        val n: Int,
        val client: ApiClient,
        val quality: LiveQuality,
        val takeControl: Boolean,
        val onUnauthorized: () -> Unit,
    ) {
        val started = System.currentTimeMillis()
        val pc: PeerConnection
        var control: DataChannel? = null
        var input: DataChannel? = null
        var pointer: DataChannel? = null
        var track: VideoTrack? = null
        var session: String? = null
        var offered = false
        var addresses = emptyMap<String, String>()
        var prev: StatsSample? = null
        var closed = false
        /** When a frame last came out of the decoder. */
        var lastFrame = 0L
        private var nudgeSince = 0L
        private var nudges = 0
        /**
         * Some decoders (the emulator's software H.264, some phones) hold a frame until the next one
         * arrives, and an idle desktop sends nothing more after a keyframe: no picture for seconds
         * after connecting or resizing. While none has come out, ask mobd for another keyframe.
         */
        private val nudgeTick = object : Runnable {
            override fun run() {
                if (!current() || lastFrame > nudgeSince || nudges >= 8) return
                nudges++
                send(control, keyframeMessage())
                main.postDelayed(this, 700)
            }
        }

        fun nudge() {
            main.removeCallbacks(nudgeTick)
            nudgeSince = System.currentTimeMillis()
            nudges = 0
            main.postDelayed(nudgeTick, 700)
        }
        private val frames = VideoSink { frame ->
            val w = frame.rotatedWidth
            val h = frame.rotatedHeight
            post { onFrame(w, h) }
        }
        private val timeout = Runnable { fail("The live view did not start. Is the office Mac reachable from here?") }
        private val gatherTimeout = Runnable { offer() }
        private val statsTick = object : Runnable {
            override fun run() {
                if (!current()) return
                pc.getStats { report ->
                    val entries = report.statsMap.values.map { StatEntry(it.type, it.id, it.members) }
                    post {
                        val (sample, readout) = readStats(entries, prev, System.currentTimeMillis(), addresses)
                        if (prev != null) _stats.value = _stats.value.copy(readout = readout)
                        prev = sample
                    }
                }
                main.postDelayed(this, 1000)
            }
        }

        init {
            val config = PeerConnection.RTCConfiguration(emptyList()).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
            }
            pc = factory.createPeerConnection(config, observer())
                ?: throw IllegalStateException("could not create a peer connection")
        }

        fun current() = !closed && conn === this && attempt == n

        fun post(block: () -> Unit) {
            main.post { if (current()) block() }
        }

        fun ms() = System.currentTimeMillis() - started

        fun start() {
            main.postDelayed(timeout, 15_000)
            pc.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY),
            )
            control = pc.createDataChannel("control", DataChannel.Init())
            input = pc.createDataChannel("input", DataChannel.Init())
            pointer = pc.createDataChannel("pointer", DataChannel.Init().apply { ordered = false; maxRetransmits = 0 })
            control?.registerObserver(object : DataChannel.Observer {
                override fun onBufferedAmountChange(previous: Long) {}
                override fun onStateChange() {}
                override fun onMessage(buffer: DataChannel.Buffer) {
                    if (buffer.binary) return
                    val bytes = ByteArray(buffer.data.remaining()).also { buffer.data.get(it) }
                    val m = parseControl(String(bytes, Charsets.UTF_8)) ?: return
                    post { onControl(m) }
                }
            })
            pc.createOffer(object : Sdp() {
                override fun onCreateSuccess(desc: SessionDescription) {
                    pc.setLocalDescription(object : Sdp() {
                        override fun onSetSuccess() = post { main.postDelayed(gatherTimeout, 2000) }
                        override fun onSetFailure(error: String) = post { fail("Could not start WebRTC: $error") }
                    }, desc)
                }
                override fun onCreateFailure(error: String) = post { fail("Could not start WebRTC: $error") }
            }, MediaConstraints())
        }

        /** ICE gathered (or 2 s passed: host candidates take milliseconds): one POST with the offer. */
        fun offer() {
            if (offered || !current()) return
            offered = true
            main.removeCallbacks(gatherTimeout)
            val sdp = pc.localDescription?.description ?: return fail("Could not start WebRTC: no offer")
            val (w, h) = opened
            val req = Request.Builder().url(client.url("stream"))
                .post(openRequest(sdp, w, h, quality).toRequestBody("application/json".toMediaType()))
                .build()
            client.okHttpClient.newCall(req).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = post {
                    if (e.isUnauthorized()) {
                        onUnauthorized()
                        fail(e.message ?: "unauthorized")
                    } else fail("Cannot reach mobd: ${e.message ?: e.javaClass.simpleName}")
                }

                override fun onResponse(call: Call, response: Response) {
                    val code = response.code
                    val body = response.use { it.body?.string().orEmpty() }
                    main.post {
                        val answer = parseAnswer(body)
                        when {
                            !current() -> answer?.let { closeSession(it.session) }
                            code == 404 || code == 405 -> fail(UPDATE_MOBD)
                            code !in 200..299 -> fail(errorText(body, code))
                            answer == null -> fail("Invalid live desktop answer from mobd")
                            answer.answer == null -> {
                                session = answer.session
                                fail("mobd answered without WebRTC; update mobd on the office Mac")
                            }
                            else -> accept(answer)
                        }
                    }
                }
            })
        }

        fun accept(answer: StreamAnswer) {
            session = answer.session
            addresses = candidateAddresses(answer.answer.orEmpty())
            Log.add("live view: answered in ${ms()} ms, ${answer.width}×${answer.height}")
            pc.setRemoteDescription(object : Sdp() {
                override fun onSetSuccess() {}
                override fun onSetFailure(error: String) = post { fail("mobd's answer was refused: $error") }
            }, SessionDescription(SessionDescription.Type.ANSWER, answer.answer))
        }

        fun onTrack(t: VideoTrack) {
            track = t
            t.addSink(frames)
            sinks.forEach(t::addSink)
        }

        fun onFrame(w: Int, h: Int) {
            lastFrame = System.currentTimeMillis()
            val s = _state.value
            if (s.status == VncStatus.Connecting) {
                main.removeCallbacks(timeout)
                Log.add("live view: first picture after ${ms()} ms, ${w}×$h")
                _state.value = s.copy(status = VncStatus.Connected, detail = "", width = w, height = h)
                main.postDelayed(statsTick, 1000)
                if (takeControl) setControl(true)
                main.removeCallbacks(resizeTask)
                main.postDelayed(resizeTask, 300) // views laid out while connecting
            } else if (w != s.width || h != s.height) {
                _state.value = s.copy(width = w, height = h)
            }
        }

        fun onControl(m: Control) {
            when (m) {
                is Control.Stats -> _stats.value = _stats.value.copy(kbps = m.kbps, loss = m.loss)
                is Control.Ended -> fail(m.message)
                is Control.Error -> Log.add("live view: ${m.about}: ${m.message}")
                is Control.Cursor -> _cursor.value = m.x to m.y
            }
        }

        fun send(channel: DataChannel?, text: String): Boolean {
            if (channel == null || channel.state() != DataChannel.State.OPEN) return false
            return channel.send(DataChannel.Buffer(ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8)), false))
        }

        fun fail(detail: String) {
            if (!current()) return
            Log.error("live view: $detail")
            attempt++
            conn = null
            close()
            _modifiers.value = emptySet()
            _state.value = VncState(VncStatus.Error, detail)
            _stats.value = StreamStats()
        }

        fun closeSession(id: String) {
            val req = Request.Builder().url(client.url("stream/$id")).delete().build()
            client.okHttpClient.newCall(req).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {}
                override fun onResponse(call: Call, response: Response) = response.close()
            })
        }

        fun close() {
            if (closed) return
            release()
            closed = true
            main.removeCallbacks(timeout)
            main.removeCallbacks(gatherTimeout)
            main.removeCallbacks(statsTick)
            main.removeCallbacks(nudgeTick)
            session?.let(::closeSession)
            track?.let { t -> sinks.forEach(t::removeSink); t.removeSink(frames) }
            track = null
            listOfNotNull(control, input, pointer).forEach { it.unregisterObserver(); it.close(); it.dispose() }
            control = null
            input = null
            pointer = null
            pc.close()
            pc.dispose()
        }

        private fun observer() = object : PeerConnection.Observer {
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                if (state == PeerConnection.IceGatheringState.COMPLETE) post { offer() }
            }

            override fun onConnectionChange(state: PeerConnection.PeerConnectionState) = post {
                when (state) {
                    PeerConnection.PeerConnectionState.CONNECTED -> {
                        Log.add("live view: WebRTC connected after ${ms()} ms")
                        nudge()
                    }
                    PeerConnection.PeerConnectionState.FAILED ->
                        fail(if (_state.value.status == VncStatus.Connected) "The live view connection was lost" else "The office Mac could not be reached over WebRTC")
                    else -> Unit
                }
            }

            override fun onTrack(transceiver: RtpTransceiver) {
                val t = transceiver.receiver.track() as? VideoTrack ?: return
                post { onTrack(t) }
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceCandidate(candidate: IceCandidate) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(channel: DataChannel) {}
            override fun onRenegotiationNeeded() {}
        }
    }

    private abstract class Sdp : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String) {}
        override fun onSetFailure(error: String) {}
    }

    private val egl: EglBase by lazy { shared(appContext).first }
    private val factory: PeerConnectionFactory by lazy { shared(appContext).second }

    companion object {
        private var created: Pair<EglBase, PeerConnectionFactory>? = null

        /** One EGL context and factory for the process; libwebrtc initializes once. */
        @Synchronized
        private fun shared(context: Context): Pair<EglBase, PeerConnectionFactory> = created ?: run {
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
            val egl = EglBase.create()
            // Receive-only: the encoder factory is unused but the builder wants one. The decoder
            // factory includes the platform software H.264 decoder emulators need.
            val factory = PeerConnectionFactory.builder()
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
                .createPeerConnectionFactory()
            (egl to factory).also { created = it }
        }
    }
}
