package com.xrdavies.mob.vnc

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Classic VNC Authentication (RFB security type 2): the 16-byte challenge encrypted with DES,
 * keyed by the password (max 8 bytes, zero-padded) with each key byte's bits reversed.
 * Signs in to the login window, not a user's session (that needs Apple sign-in, type 30).
 */
object VncAuth {
    fun response(password: String, challenge: ByteArray): ByteArray {
        val key = ByteArray(8)
        val pw = password.toByteArray(Charsets.ISO_8859_1)
        for (i in 0 until minOf(8, pw.size)) key[i] = reverseBits(pw[i])
        return des(key, challenge)
    }

    fun des(key: ByteArray, data: ByteArray): ByteArray =
        Cipher.getInstance("DES/ECB/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "DES"))
            doFinal(data)
        }

    fun reverseBits(b: Byte): Byte {
        var v = b.toInt() and 0xff
        var r = 0
        repeat(8) {
            r = (r shl 1) or (v and 1)
            v = v shr 1
        }
        return r.toByte()
    }
}
