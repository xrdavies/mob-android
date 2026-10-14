package com.xrdavies.mob.vnc

import java.io.DataInputStream
import java.io.IOException
import java.util.zip.Inflater

/**
 * The remote desktop as 0xAARRGGBB pixels, plus the decoders for the encodings mob asks for
 * (ZRLE, CopyRect, Raw). The pixel format is fixed by [Rfb.PIXEL_FORMAT]: 32 bpp, depth 24,
 * little endian, red at bit 16, so a pixel arrives as the bytes B, G, R, x.
 */
class Framebuffer(val width: Int, val height: Int) {
    val pixels = IntArray(width * height)
    private val inflater = Inflater()
    private var zbuf = ByteArray(1 shl 16)

    fun raw(x: Int, y: Int, w: Int, h: Int, d: DataInputStream) {
        val row = ByteArray(w * 4)
        for (r in 0 until h) {
            d.readFully(row)
            var o = (y + r) * width + x
            for (c in 0 until w) {
                val i = c * 4
                pixels[o++] = argb(row[i], row[i + 1], row[i + 2])
            }
        }
    }

    fun copyRect(x: Int, y: Int, w: Int, h: Int, srcX: Int, srcY: Int) {
        val tmp = IntArray(w * h)
        for (r in 0 until h) System.arraycopy(pixels, (srcY + r) * width + srcX, tmp, r * w, w)
        for (r in 0 until h) System.arraycopy(tmp, r * w, pixels, (y + r) * width + x, w)
    }

    /** ZRLE (RFC 6143 §7.7.6): 64×64 TRLE tiles inside one zlib stream per connection. */
    fun zrle(x: Int, y: Int, w: Int, h: Int, d: DataInputStream) {
        val compressed = ByteArray(d.readInt()).also(d::readFully)
        inflater.setInput(compressed)
        var len = 0
        while (true) {
            if (len == zbuf.size) zbuf = zbuf.copyOf(zbuf.size * 2)
            val n = inflater.inflate(zbuf, len, zbuf.size - len)
            len += n
            if (n == 0 && (inflater.needsInput() || inflater.finished())) break
        }
        zrleTiles(x, y, w, h, zbuf, len)
    }

    fun zrleTiles(x: Int, y: Int, w: Int, h: Int, data: ByteArray, length: Int = data.size) {
        val r = Reader(data, length)
        var ty = 0
        while (ty < h) {
            val th = minOf(64, h - ty)
            var tx = 0
            while (tx < w) {
                val tw = minOf(64, w - tx)
                tile(x + tx, y + ty, tw, th, r)
                tx += 64
            }
            ty += 64
        }
    }

    private fun tile(x: Int, y: Int, w: Int, h: Int, r: Reader) {
        when (val sub = r.u8()) {
            0 -> for (row in 0 until h) {
                var o = (y + row) * width + x
                repeat(w) { pixels[o++] = r.cpixel() }
            }
            1 -> fill(x, y, w, h, r.cpixel())
            in 2..16 -> {
                val palette = IntArray(sub) { r.cpixel() }
                val bits = if (sub == 2) 1 else if (sub <= 4) 2 else 4
                val mask = (1 shl bits) - 1
                for (row in 0 until h) {
                    var o = (y + row) * width + x
                    var bit = 0
                    var byte = 0
                    repeat(w) {
                        if (bit == 0) {
                            byte = r.u8()
                            bit = 8
                        }
                        bit -= bits
                        pixels[o++] = palette.getOrElse((byte shr bit) and mask) { palette[0] }
                    }
                }
            }
            128 -> runs(x, y, w, h) { r.cpixel() to r.runLength() }
            in 130..255 -> {
                val palette = IntArray(sub - 128) { r.cpixel() }
                runs(x, y, w, h) {
                    val b = r.u8()
                    val color = palette.getOrElse(b and 0x7f) { palette[0] }
                    color to (if (b and 0x80 != 0) r.runLength() else 1)
                }
            }
            else -> throw IOException("ZRLE subencoding $sub is not supported")
        }
    }

    /** Fills a tile in row order from (colour, run length) pairs. */
    private inline fun runs(x: Int, y: Int, w: Int, h: Int, next: () -> Pair<Int, Int>) {
        var i = 0
        val total = w * h
        while (i < total) {
            val (color, len) = next()
            repeat(minOf(len, total - i)) {
                pixels[(y + i / w) * width + x + i % w] = color
                i++
            }
        }
    }

    private fun fill(x: Int, y: Int, w: Int, h: Int, color: Int) {
        for (row in 0 until h) {
            val o = (y + row) * width + x
            pixels.fill(color, o, o + w)
        }
    }

    private class Reader(val data: ByteArray, val length: Int) {
        var o = 0
        fun u8(): Int {
            if (o >= length) throw IOException("ZRLE data ended early")
            return data[o++].toInt() and 0xff
        }
        // A compressed pixel is the three low bytes: B, G, R.
        fun cpixel(): Int {
            val b = u8()
            val g = u8()
            return argb(b.toByte(), g.toByte(), u8().toByte())
        }
        fun runLength(): Int {
            var len = 1
            do {
                val more = u8()
                len += more
            } while (more == 255)
            return len
        }
    }

    companion object {
        fun argb(b: Byte, g: Byte, r: Byte): Int =
            (0xff shl 24) or ((r.toInt() and 0xff) shl 16) or ((g.toInt() and 0xff) shl 8) or (b.toInt() and 0xff)
    }
}
