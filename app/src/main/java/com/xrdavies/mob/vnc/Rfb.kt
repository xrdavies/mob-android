package com.xrdavies.mob.vnc

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream

/** Area of the framebuffer changed by one update. */
data class Dirty(val x: Int, val y: Int, val w: Int, val h: Int) {
    fun union(o: Dirty): Dirty {
        val l = minOf(x, o.x)
        val t = minOf(y, o.y)
        return Dirty(l, t, maxOf(x + w, o.x + o.w) - l, maxOf(y + h, o.y + o.h) - t)
    }
}

/** Thrown for problems the user can fix (sign-in, Screen Sharing settings). */
class RfbException(message: String) : IOException(message)

/**
 * RFB 3.8 client: handshake (None or Apple Remote Desktop sign-in), then framebuffer
 * updates and input. Blocking; runs on its own thread. Ported from svnc's session.
 */
class Rfb(
    input: InputStream,
    private val send: (ByteArray) -> Unit,
    private val username: String,
    private val password: String,
) {
    private val d = DataInputStream(input)
    var fb: Framebuffer? = null
        private set

    fun handshake(): Framebuffer {
        val version = ByteArray(12).also(d::readFully).toString(Charsets.US_ASCII)
        if (!version.startsWith("RFB ")) throw IOException("Not a VNC server (got \"${version.trim()}\")")
        send("RFB 003.008\n".toByteArray(Charsets.US_ASCII))

        val count = d.readUnsignedByte()
        if (count == 0) throw RfbException("Screen Sharing refused the connection: ${reason()}")
        val types = ByteArray(count).also(d::readFully).map { it.toInt() and 0xff }
        val signIn = username.isNotEmpty() && password.isNotEmpty()
        val chosen = when {
            signIn && ARD in types -> ARD
            NONE in types -> NONE
            ARD in types -> throw RfbException("Enter the office Mac's user name and password in Settings → Machines → Edit (Desktop sign-in)")
            else -> throw RfbException("No supported sign-in method (server offers $types)")
        }
        send(byteArrayOf(chosen.toByte()))
        if (chosen == ARD) {
            val generator = d.readUnsignedShort()
            val keyLength = d.readUnsignedShort()
            val prime = ByteArray(keyLength).also(d::readFully)
            val serverKey = ByteArray(keyLength).also(d::readFully)
            send(Ard.response(generator, prime, serverKey, username, password))
        }
        if (d.readInt() != 0) {
            val why = runCatching { reason() }.getOrDefault("")
            throw RfbException(if (chosen == ARD) "Sign-in failed: check the Mac user name and password. $why".trim() else "Sign-in failed. $why".trim())
        }

        send(byteArrayOf(1)) // ClientInit: shared, so the agent's own session is not kicked off
        val width = d.readUnsignedShort()
        val height = d.readUnsignedShort()
        d.skipBytes(16) // server pixel format; we set our own
        d.skipBytes(d.readInt()) // desktop name
        val fb = Framebuffer(width, height)
        this.fb = fb
        send(byteArrayOf(0, 0, 0, 0) + PIXEL_FORMAT)
        send(setEncodings(ZRLE, COPY_RECT, RAW, DESKTOP_SIZE, LAST_RECT))
        requestUpdate(incremental = false)
        return fb
    }

    /** Reads server messages until the connection ends. */
    fun run(onResize: (Framebuffer) -> Unit, onUpdate: (Framebuffer, Dirty) -> Unit) {
        while (true) {
            when (val type = d.readUnsignedByte()) {
                0 -> update(onResize, onUpdate)
                1 -> { // SetColourMapEntries (unused with true colour)
                    d.skipBytes(3)
                    d.skipBytes(d.readUnsignedShort() * 6)
                }
                2 -> Unit // Bell
                3 -> { // ServerCutText
                    d.skipBytes(3)
                    d.skipBytes(d.readInt())
                }
                else -> throw IOException("Unknown VNC message type $type")
            }
        }
    }

    private fun update(onResize: (Framebuffer) -> Unit, onUpdate: (Framebuffer, Dirty) -> Unit) {
        d.skipBytes(1)
        val rects = d.readUnsignedShort()
        var dirty: Dirty? = null
        for (i in 0 until rects) {
            val x = d.readUnsignedShort()
            val y = d.readUnsignedShort()
            val w = d.readUnsignedShort()
            val h = d.readUnsignedShort()
            val fb = this.fb!!
            when (val enc = d.readInt()) {
                RAW -> fb.raw(x, y, w, h, d)
                COPY_RECT -> fb.copyRect(x, y, w, h, d.readUnsignedShort(), d.readUnsignedShort())
                ZRLE -> fb.zrle(x, y, w, h, d)
                DESKTOP_SIZE -> {
                    val resized = Framebuffer(w, h)
                    this.fb = resized
                    onResize(resized)
                    dirty = null
                    continue
                }
                LAST_RECT -> break
                else -> throw IOException("Unexpected VNC encoding $enc")
            }
            if (w > 0 && h > 0) dirty = Dirty(x, y, w, h).let { r -> dirty?.union(r) ?: r }
        }
        dirty?.let { onUpdate(fb!!, it) }
        requestUpdate(incremental = true)
    }

    fun requestUpdate(incremental: Boolean) {
        val fb = fb ?: return
        send(byteArrayOf(3, if (incremental) 1 else 0) + u16(0) + u16(0) + u16(fb.width) + u16(fb.height))
    }

    fun pointer(x: Int, y: Int, buttons: Int) {
        send(byteArrayOf(5, buttons.toByte()) + u16(x) + u16(y))
    }

    fun key(keysym: Int, down: Boolean) {
        send(byteArrayOf(4, if (down) 1 else 0, 0, 0) + u32(keysym))
    }

    private fun reason(): String {
        val len = d.readInt()
        return ByteArray(minOf(len, 512)).also(d::readFully).toString(Charsets.UTF_8)
    }

    companion object {
        const val NONE = 1
        const val ARD = 30

        const val RAW = 0
        const val COPY_RECT = 1
        const val ZRLE = 16
        const val DESKTOP_SIZE = -223
        const val LAST_RECT = -224

        /** 32 bpp, depth 24, little endian, true colour, max 255, shifts R16 G8 B0, padding. */
        val PIXEL_FORMAT = byteArrayOf(32, 24, 0, 1, 0, -1, 0, -1, 0, -1, 16, 8, 0, 0, 0, 0)

        fun setEncodings(vararg encodings: Int): ByteArray =
            byteArrayOf(2, 0) + u16(encodings.size) + encodings.fold(ByteArray(0)) { acc, e -> acc + u32(e) }

        private fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
        private fun u32(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    }
}
