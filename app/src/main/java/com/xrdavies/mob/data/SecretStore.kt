package com.xrdavies.mob.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts small secrets; the Android Keystore in the app, an in-memory fake in tests. */
interface SecretCipher {
    /** Returns IV and ciphertext. */
    fun encrypt(plain: ByteArray): Pair<ByteArray, ByteArray>
    fun decrypt(iv: ByteArray, data: ByteArray): ByteArray
}

/** AES/GCM with a key that never leaves the Keystore. */
class KeystoreCipher(private val alias: String) : SecretCipher {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    override fun encrypt(plain: ByteArray): Pair<ByteArray, ByteArray> {
        val c = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return c.iv to c.doFinal(plain)
    }

    override fun decrypt(iv: ByteArray, data: ByteArray): ByteArray =
        Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }.doFinal(data)

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

/**
 * Device tokens ("<machineId>.token"),
 * encrypted into their own preferences file. Not backed up: the Keystore key would not restore.
 */
class SecretStore(private val prefs: SharedPreferences, private val cipher: SecretCipher) {
    constructor(context: Context) : this(
        context.getSharedPreferences("mob.secrets", Context.MODE_PRIVATE), KeystoreCipher("mob.secrets"),
    )

    private val cache = mutableMapOf<String, String?>()

    @Synchronized
    fun get(key: String): String? = cache.getOrPut(key) {
        val stored = prefs.getString(key, null) ?: return@getOrPut null
        runCatching {
            val (iv, data) = stored.split(':', limit = 2).map { Base64.getDecoder().decode(it) }
            String(cipher.decrypt(iv, data), Charsets.UTF_8)
        }.getOrNull()
    }

    /** Returns false when the secret could not be encrypted (nothing is stored then). */
    @Synchronized
    fun set(key: String, value: String): Boolean {
        val encoded = runCatching {
            val (iv, data) = cipher.encrypt(value.toByteArray(Charsets.UTF_8))
            Base64.getEncoder().encodeToString(iv) + ":" + Base64.getEncoder().encodeToString(data)
        }.getOrNull() ?: return false
        prefs.edit().putString(key, encoded).apply()
        cache[key] = value
        return true
    }

    @Synchronized
    fun delete(key: String) {
        prefs.edit().remove(key).apply()
        cache.remove(key)
    }

    companion object {
        fun token(machineId: String) = "$machineId.token"
    }
}
