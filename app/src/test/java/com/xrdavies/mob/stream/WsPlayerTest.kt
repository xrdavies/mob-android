package com.xrdavies.mob.stream

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WsPlayerTest {
    private val sps = byteArrayOf(0x67, 0x42, 0xe0.toByte(), 0x1f)
    private val pps = byteArrayOf(0x68, 0xce.toByte(), 0x3c, 0x80.toByte())
    private val keyframe = byteArrayOf(0, 0, 0, 1, 0x09, 0xf0.toByte(), 0, 0, 0, 1) + sps + byteArrayOf(0, 0, 0, 1) + pps + byteArrayOf(0, 0, 1, 0x65, 0x88.toByte(), 0x84.toByte())

    @Test
    fun parsesVideoMessages() {
        val pts = 1_792_294_424_594_123L
        val msg = byteArrayOf(1) + ByteArray(8) { i -> (pts shr (56 - 8 * i)).toByte() } + byteArrayOf(1) + keyframe
        val m = parseWsVideo(msg)!!
        assertEquals(pts, m.pts)
        assertEquals(true, m.key)
        assertArrayEquals(keyframe, m.data)
        assertNull(parseWsVideo(byteArrayOf(2) + msg.copyOfRange(1, msg.size))) // a cursor image, not video
        assertNull(parseWsVideo(ByteArray(10)))
    }

    @Test
    fun splitsAnnexBAndFindsParameterSets() {
        val units = annexBUnits(keyframe)
        assertEquals(listOf(listOf<Byte>(0x09, 0xf0.toByte()), sps.toList(), pps.toList(), listOf<Byte>(0x65, 0x88.toByte(), 0x84.toByte())), units.map { it.toList() })
        val (csd0, csd1) = parameterSets(units)!!
        assertArrayEquals(byteArrayOf(0, 0, 0, 1) + sps, csd0)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1) + pps, csd1)
        assertNull(parameterSets(annexBUnits(byteArrayOf(0, 0, 0, 1, 0x41, 0x9a.toByte())))) // a delta frame has no SPS
    }
}
