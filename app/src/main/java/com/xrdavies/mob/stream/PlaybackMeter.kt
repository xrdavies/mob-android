package com.xrdavies.mob.stream

import java.util.Locale
import kotlin.math.abs

/**
 * How long and how loud the office computer's sound played while it was on: a line for the log, so
 * "I hear nothing" can be told apart from "nothing arrived". Fed 16-bit PCM from WebRTC's playback
 * thread.
 */
class PlaybackMeter(private val rate: Int = 48000) {
    private var running = false
    private var frames = 0L
    private var peak = 0

    @Synchronized
    fun start() {
        running = true
        frames = 0
        peak = 0
    }

    @Synchronized
    fun add(pcm: ByteArray, channels: Int) {
        if (!running || channels <= 0) return
        var i = 0
        while (i + 1 < pcm.size) {
            val v = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xff)).toShort().toInt()
            if (abs(v) > peak) peak = abs(v)
            i += 2
        }
        frames += pcm.size / 2 / channels
    }

    /** Stops measuring; the summary when anything was measured since [start]. */
    @Synchronized
    fun stop(): String? {
        if (!running) return null
        running = false
        val seconds = frames.toDouble() / rate
        val percent = (peak.coerceAtMost(Short.MAX_VALUE.toInt()) * 100.0 / Short.MAX_VALUE).toInt()
        return String.format(Locale.ROOT, "live view sound: played %.1f s, peak %d%%", seconds, percent)
    }
}
