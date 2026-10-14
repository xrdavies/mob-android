package com.xrdavies.mob.vnc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.security.SecureRandom
import javax.crypto.Cipher

class RfbTest {
    private fun argb(r: Int, g: Int, b: Int) = (0xff shl 24) or (r shl 16) or (g shl 8) or b
    private fun cpixel(r: Int, g: Int, b: Int) = byteArrayOf(b.toByte(), g.toByte(), r.toByte())

    @Test
    fun ardResponseDecryptsOnTheServerSide() {
        val rnd = SecureRandom()
        val p = BigInteger.probablePrime(512, rnd)
        val g = 2
        val keyLength = 64
        val serverPrivate = BigInteger(500, rnd)
        val serverPublic = Ard.toBytes(BigInteger.valueOf(g.toLong()).modPow(serverPrivate, p), keyLength)

        val out = Ard.response(g, Ard.toBytes(p, keyLength), serverPublic, "alice", "s3cret")
        assertEquals(128 + keyLength, out.size)

        val clientPublic = BigInteger(1, out.copyOfRange(128, out.size))
        val secret = Ard.toBytes(clientPublic.modPow(serverPrivate, p), keyLength)
        val plain = Ard.aesEcb(Ard.md5(secret), out.copyOfRange(0, 128), Cipher.DECRYPT_MODE)
        assertEquals("alice", String(plain, 0, plain.indexOf(0)))
        assertEquals("s3cret", String(plain, 64, plain.copyOfRange(64, 128).indexOf(0)))
    }

    @Test
    fun zrleSolidPaletteAndRunTiles() {
        // Three 2x2 rects decoded side by side into a 6x2 framebuffer.
        val fb = Framebuffer(6, 2)
        val red = argb(255, 0, 0)
        val blue = argb(0, 0, 255)

        fb.zrleTiles(0, 0, 2, 2, byteArrayOf(1) + cpixel(255, 0, 0))
        // Packed palette of 2: one bit per pixel, rows padded to bytes: row0 = 1,0 row1 = 0,1
        fb.zrleTiles(2, 0, 2, 2, byteArrayOf(2) + cpixel(255, 0, 0) + cpixel(0, 0, 255) + byteArrayOf(0x80.toByte(), 0x40))
        // Palette RLE: index 1 for 3 pixels (run byte 2), then index 0 once.
        fb.zrleTiles(4, 0, 2, 2, byteArrayOf(130.toByte()) + cpixel(255, 0, 0) + cpixel(0, 0, 255) + byteArrayOf(0x81.toByte(), 2, 0))

        assertArrayEquals(
            intArrayOf(red, red, blue, red, blue, blue, red, red, red, blue, blue, red),
            fb.pixels,
        )
    }

    @Test
    fun zrlePlainRunLengthSpansRows() {
        val fb = Framebuffer(3, 2)
        fb.zrleTiles(0, 0, 3, 2, byteArrayOf(128.toByte()) + cpixel(0, 255, 0) + byteArrayOf(3) + cpixel(1, 2, 3) + byteArrayOf(1))
        val green = argb(0, 255, 0)
        val other = argb(1, 2, 3)
        assertArrayEquals(intArrayOf(green, green, green, green, other, other), fb.pixels)
    }

    private fun server(types: IntArray, width: Int = 1440, height: Int = 900): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply {
            write("RFB 003.889\n".toByteArray())
            writeByte(types.size)
            types.forEach { writeByte(it) }
            writeInt(0) // SecurityResult OK
            writeShort(width)
            writeShort(height)
            write(ByteArray(16))
            writeInt(3)
            write("Mac".toByteArray())
        }
        return bytes.toByteArray()
    }

    @Test
    fun handshakeWithoutSignIn() {
        val sent = mutableListOf<ByteArray>()
        val fb = Rfb(ByteArrayInputStream(server(intArrayOf(1))), { sent += it }, "", "").handshake()
        assertEquals(1440, fb.width)
        assertEquals(900, fb.height)
        assertEquals("RFB 003.008\n", String(sent[0]))
        assertArrayEquals(byteArrayOf(1), sent[1]) // chose None
        assertEquals(0, sent[3][0].toInt()) // SetPixelFormat
        assertEquals(2, sent[4][0].toInt()) // SetEncodings
        assertEquals(3, sent[5][0].toInt()) // first full update request
    }

    @Test
    fun desMatchesTheStandardTestVector() {
        fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertArrayEquals(hex("85e813540f0ab405"), VncAuth.des(hex("133457799bbcdff1"), hex("0123456789abcdef")))
        assertEquals(0x80.toByte(), VncAuth.reverseBits(1))
        assertEquals(0x86.toByte(), VncAuth.reverseBits(0x61)) // 'a'
    }

    @Test
    fun passwordOnlyUsesVncAuth() {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply {
            write("RFB 003.889\n".toByteArray())
            writeByte(2); writeByte(30); writeByte(2)
            write(ByteArray(16) { it.toByte() }) // challenge
            writeInt(0)
            writeShort(800); writeShort(600); write(ByteArray(16)); writeInt(0)
        }
        val sent = mutableListOf<ByteArray>()
        Rfb(ByteArrayInputStream(bytes.toByteArray()), { sent += it }, "", "secret").handshake()
        assertArrayEquals(byteArrayOf(2), sent[1])
        assertArrayEquals(VncAuth.response("secret", ByteArray(16) { it.toByte() }), sent[2])
        assertEquals(16, sent[2].size)
    }

    @Test
    fun ardOnlyServerAsksForCredentials() {
        val e = runCatching { Rfb(ByteArrayInputStream(server(intArrayOf(30))), {}, "", "").handshake() }.exceptionOrNull()
        assertTrue(e is RfbException)
        assertTrue(e!!.message!!.contains("Settings"))
    }
}
