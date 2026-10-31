package com.xrdavies.mob.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The office computer's sound (mobd docs/desktop-audio.md): what is asked for and understood. */
class SoundTest {
    @Test
    fun requestsAnswersAndControlMessages() {
        assertTrue(openRequest("sdp", 1280, 720, LiveQuality.Auto, audio = true).contains("\"audio\":true"))
        assertFalse("older mobd never sees the field unless sound is offered", openRequest("sdp", 1280, 720, LiveQuality.Auto).contains("audio"))
        val ready = parseAnswer("""{"session":"s","answer":"v=0","width":1,"height":1,"audio":{"status":"ready","codec":"opus"}}""")!!
        assertEquals("ready", ready.audio)
        val no = parseAnswer("""{"session":"s","width":1,"height":1,"audio":{"status":"unavailable","reason":"Not on Linux yet."}}""")!!
        assertEquals("unavailable" to "Not on Linux yet.", no.audio to no.audioReason)
        assertNull(parseAnswer("""{"session":"s","width":1,"height":1}""")!!.audio)
        assertEquals(Control.Audio("on", ""), parseControl("""{"t":"audio","state":"on"}"""))
        assertEquals(Control.Audio("error", "sound ended"), parseControl("""{"t":"audio","state":"error","message":"sound ended"}"""))
        assertEquals("""{"t":"audio","on":true}""", audioMessage(true))
        assertTrue(LiveSound.Starting.isOn)
        assertFalse(LiveSound.Unavailable("x").isOn)
    }

    @Test
    fun theMeterReportsDurationAndPeak() {
        val m = PlaybackMeter()
        m.add(ByteArray(4), 2) // before start: ignored
        assertNull(m.stop())
        m.start()
        // One second of stereo 16-bit PCM, one sample at half scale.
        val pcm = ByteArray(48000 * 2 * 2)
        pcm[10] = 0x00; pcm[11] = 0x40 // 16384
        m.add(pcm, 2)
        assertEquals("live view sound: played 1.0 s, peak 50%", m.stop())
    }
}
