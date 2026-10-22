package com.xrdavies.mob.stream

import org.junit.Assert.*
import org.junit.Test

class StreamProtocolTest {
    @Test
    fun normalizesToMobdCoordinates() {
        // (v + 0.5) / size centers the pixel
        val (x1, y1) = normalize(640, 360, 1280, 720)
        assertEquals(0.5004, x1, 0.001)
        assertEquals(0.5007, y1, 0.001)

        val (x2, y2) = normalize(0, 0, 1280, 720)
        assertEquals(0.0004, x2, 0.001)
        assertEquals(0.0007, y2, 0.001)

        val (x3, y3) = normalize(1279, 719, 1280, 720)
        assertEquals(0.9996, x3, 0.001)
        assertEquals(0.9993, y3, 0.001)
    }

    @Test
    fun roundsDevicePixelsUp() {
        assertEquals(64, devicePixels(1))
        assertEquals(64, devicePixels(64))
        assertEquals(128, devicePixels(65))
        assertEquals(1280, devicePixels(1280))
    }

    @Test
    fun encodesMessages() {
        assertTrue(moveMessage(0.5, 0.5).contains("\"t\":\"move\""))
        assertTrue(buttonMessage(0.1, 0.2, "left", true).contains("\"down\":true"))
        assertTrue(keyMessage("Enter", false).contains("\"code\":\"Enter\""))
        assertTrue(textMessage("abc").contains("\"text\":\"abc\""))
        assertTrue(scrollMessage(0.5, 0.5, 10.0, -20.0).contains("\"dx\":10"))
        assertTrue(resizeMessage(1280, 720).contains("\"maxWidth\":1280"))
    }

    @Test
    fun parsesControlMessages() {
        val stats = parseControl("""{"t":"stats","kbps":3000,"width":1280,"height":720,"fps":30,"loss":0.01,"rttMs":50}""")
        assertTrue(stats is Control.Stats)
        assertEquals(3000, (stats as Control.Stats).kbps)
        assertEquals(30, stats.fps)

        val ended = parseControl("""{"t":"ended","message":"done"}""")
        assertTrue(ended is Control.Ended)
        assertEquals("done", (ended as Control.Ended).message)

        assertNull(parseControl("""{"t":"unknown"}"""))
        assertNull(parseControl("invalid json"))
    }

    @Test
    fun parsesAnswers() {
        val answer = parseAnswer("""{"session":"abc","answer":"sdp","width":1280,"height":720}""")
        assertNotNull(answer)
        assertEquals("abc", answer?.session)
        assertEquals("sdp", answer?.answer)
        assertEquals(1280, answer?.width)

        assertNull(parseAnswer("""{"session":""}"""))
        assertNull(parseAnswer("invalid"))
    }

    @Test
    fun extractsErrorText() {
        assertEquals("denied", errorText("""{"error":"denied"}""", 403))
        assertEquals("HTTP 500", errorText("plain text", 500))
    }

    @Test
    fun mapsKeyCodes() {
        assertEquals("Backspace", keyCode(0xff08))
        assertEquals("Enter", keyCode(0xff0d))
        assertEquals("ArrowLeft", keyCode(0xff51))
        assertNull(keyCode(999))
    }

    @Test
    fun mapsShortcutCodes() {
        assertEquals("KeyC", shortcutCode('c'))
        assertEquals("KeyC", shortcutCode('C'))
        assertEquals("Digit5", shortcutCode('5'))
        assertEquals("Space", shortcutCode(' '))
        assertNull(shortcutCode('\u0000'))
    }

    @Test
    fun identifiesNetworks() {
        assertEquals(Network.Tailscale, networkOf("100.64.1.2"))
        assertEquals(Network.Tailscale, networkOf("fd7a:115c:a1e0::1"))
        assertEquals(Network.LAN, networkOf("192.168.1.1"))
        assertEquals(Network.LAN, networkOf("10.0.0.1"))
        assertEquals(Network.Internet, networkOf("8.8.8.8"))
        assertEquals(Network.Relay, networkOf("1.2.3.4", relayed = true))
    }

    @Test
    fun extractsCandidateAddresses() {
        val sdp = """
            a=candidate:1 1 udp 100 192.168.1.5 54321 typ host
            a=candidate:2 1 tcp 50 10.0.0.1 9 typ host
        """.trimIndent()
        val addrs = candidateAddresses(sdp)
        assertEquals("192.168.1.5", addrs["udp:54321"])
        assertEquals("10.0.0.1", addrs["tcp:9"])
    }

    @Test
    fun readsStats() {
        val report = listOf(
            StatEntry("inbound-rtp", "v0", mapOf("kind" to "video", "bytesReceived" to 1000000.0, "framesDecoded" to 30.0)),
            StatEntry("candidate-pair", "pair", mapOf("selected" to true, "state" to "succeeded", "remoteCandidateId" to "remote")),
            StatEntry("remote-candidate", "remote", mapOf("address" to "192.168.1.5", "protocol" to "udp", "port" to 12345, "candidateType" to "host")),
        )
        val (sample, readout) = readStats(report, StatsSample(0, 500000.0, 0.0), 1000, emptyMap())
        assertEquals(1000, sample.atMs)
        assertEquals(500, readout.kBps)
        assertEquals(30, readout.fps)
        assertEquals(Network.LAN, readout.network)
    }

    @Test
    fun formatsReadoutText() {
        val r = Readout(3500, 30, Network.Tailscale, 25)
        assertEquals("1280×720 · 30 fps · 3500 kB/s · WebRTC · Tailscale", readoutText(1280, 720, r))
        assertEquals("1280×720", readoutText(1280, 720, null))
        assertEquals("1280×720 · Idle · WebRTC · LAN", readoutText(1280, 720, Readout(0, 0, Network.LAN)))
        assertEquals("1280×720 · Idle · WebRTC · LAN", readoutText(1280, 720, Readout(0, 4, Network.LAN)))
    }

    @Test
    fun calculatesSignalBars() {
        assertEquals(3, signalBars(0.0, 10))
        assertEquals(2, signalBars(0.03, 200))
        assertEquals(1, signalBars(0.15, 500))
    }
}
