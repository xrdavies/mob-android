package com.xrdavies.mob.stream

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xrdavies.mob.data.ApiClient
import com.xrdavies.mob.vnc.VncStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Opens a real live view against a running mobd, so WebRTC, H.264 decoding and the data channels are
 * exercised on the device. Skipped unless run with
 * `-Pandroid.testInstrumentationRunnerArguments.mobdUrl=… -P….mobdToken=…`.
 * It only moves the office pointer: no clicks or keys. "Blocked" drops ICE candidates both ways, like
 * a network without UDP.
 */
@RunWith(AndroidJUnit4::class)
class LiveStreamTest {
    private val inst = InstrumentationRegistry.getInstrumentation()

    private fun <T> main(block: () -> T): T {
        var out: T? = null
        inst.runOnMainSync { out = block() }
        @Suppress("UNCHECKED_CAST")
        return out as T
    }

    private fun waitFor(what: String, seconds: Int, done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + seconds * 1000
        while (!main(done)) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what")
            Thread.sleep(50)
        }
    }

    private fun near(c: Pair<Double, Double>?, x: Double, y: Double) = c != null && abs(c.first - x) < 0.01 && abs(c.second - y) < 0.01

    @Test fun webRTC() = check(blocked = false, remembered = false, expect = StreamSession.WEBRTC)
    @Test fun webSocketWhenWebRTCIsBlocked() = check(blocked = true, remembered = false, expect = StreamSession.WEBSOCKET)
    @Test fun rememberedWebSocketStartsSooner() = check(blocked = true, remembered = true, expect = StreamSession.WEBSOCKET)
    @Test fun rememberedMachineReturnsToWebRTC() = check(blocked = false, remembered = true, expect = StreamSession.WEBRTC)

    private fun check(blocked: Boolean, remembered: Boolean, expect: String) {
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString("mobdUrl")
        val token = args.getString("mobdToken")
        assumeTrue("pass mobdUrl and mobdToken to run against a mobd", url != null && token != null)
        val s = main { StreamSession(inst.targetContext) }
        var won: String? = null
        main {
            s.blockWebRTC = blocked
            s.onTransport = { won = it }
        }
        val started = System.currentTimeMillis()
        main { s.connect(ApiClient(url!!, token), LiveQuality.Auto, canResize = true, screen = 1280 to 832, preferWebSocket = remembered) }
        waitFor("first picture", 15) { s.state.value.status != VncStatus.Connecting }
        assertEquals(s.state.value.detail, VncStatus.Connected, s.state.value.status)
        assertEquals(expect, main { won })
        val first = System.currentTimeMillis() - started
        val opened = main { s.state.value.width to s.state.value.height }

        // Without control, moves are not sent.
        val w = opened.first
        val h = opened.second
        main { s.pointer((0.1 * w).toInt(), (0.1 * h).toInt(), 0) }
        Thread.sleep(1000)
        val before = main { s.cursor.value }

        main { s.setControl(true) }
        assertTrue(main { s.state.value.control })
        main { s.pointer((0.25 * w).toInt(), (0.25 * h).toInt(), 0) }
        waitFor("cursor at (0.25, 0.25)", 5) { near(s.cursor.value, 0.25, 0.25) }
        main { s.pointer((0.75 * w).toInt(), (0.6 * h).toInt(), 0) }
        waitFor("cursor at (0.75, 0.6)", 5) { near(s.cursor.value, 0.75, 0.6) }
        main { s.setControl(false) }

        // A view reporting 640 px wide: the session is refitted without reconnecting.
        main { s.reportView("test", 640 to 640) }
        waitFor("640×360 after resize", 5) { s.state.value.width == 640 }
        waitFor("readout", 4) { s.stats.value.readout != null }
        val text = main { readoutText(s.state.value.width, s.state.value.height, s.stats.value.readout) }
        println("live: blocked=$blocked remembered=$remembered: first picture $first ms over ${main { won }} at ${opened.first}×${opened.second}; cursor before control $before; $text")
        main { s.disconnect() }
    }
}
