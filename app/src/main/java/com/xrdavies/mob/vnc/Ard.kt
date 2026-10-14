package com.xrdavies.mob.vnc

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Apple Remote Desktop authentication (RFB security type 30), used by macOS Screen Sharing
 * to sign in as a macOS user. Unlike VNC Auth, the server then attaches the client to that
 * user's session instead of the login window.
 *
 * The server sends a Diffie-Hellman generator, key length, prime and its public key. The client
 * derives the shared secret, uses MD5(secret) as an AES-128 key, and answers with
 * AES-ECB(username[64] ‖ password[64]) followed by its own public key. Ported from svnc.
 */
object Ard {
    fun response(
        generator: Int,
        prime: ByteArray,
        serverKey: ByteArray,
        username: String,
        password: String,
        random: SecureRandom = SecureRandom(),
    ): ByteArray {
        val keyLength = prime.size
        val p = BigInteger(1, prime)
        val privateKey = BigInteger(1, ByteArray(keyLength).also(random::nextBytes)).mod(p)
        val publicKey = toBytes(BigInteger.valueOf(generator.toLong()).modPow(privateKey, p), keyLength)
        val secret = toBytes(BigInteger(1, serverKey).modPow(privateKey, p), keyLength)
        val plain = field(username, random) + field(password, random)
        return aesEcb(md5(secret), plain) + publicKey
    }

    fun md5(data: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(data)

    fun aesEcb(key: ByteArray, data: ByteArray, mode: Int = Cipher.ENCRYPT_MODE): ByteArray =
        Cipher.getInstance("AES/ECB/NoPadding").run {
            init(mode, SecretKeySpec(key, "AES"))
            doFinal(data)
        }

    /** Big-endian, left-padded to [length] bytes. */
    fun toBytes(n: BigInteger, length: Int): ByteArray {
        val raw = n.toByteArray()
        val out = ByteArray(length)
        val count = minOf(raw.size, length)
        System.arraycopy(raw, raw.size - count, out, length - count, count)
        return out
    }

    /** A NUL-terminated UTF-8 string (max 63 bytes) in a 64-byte field of random filler. */
    private fun field(value: String, random: SecureRandom): ByteArray {
        val out = ByteArray(64).also(random::nextBytes)
        val bytes = value.toByteArray(Charsets.UTF_8)
        val n = minOf(63, bytes.size)
        System.arraycopy(bytes, 0, out, 0, n)
        out[n] = 0
        return out
    }
}
