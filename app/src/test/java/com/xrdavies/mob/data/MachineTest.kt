package com.xrdavies.mob.data

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class MachineTest {
    @Test
    fun decodesOldListWithoutDesktopSignIn() {
        val list = decodeMachines("""[{"id":"m1","name":"Office","mobdUrl":"http://h:8080","vncUsername":"me","vncPassword":"pw"}]""")!!
        val m = list.single()
        assertEquals("Office", m.name)
        // VNC is gone: saving the list again drops the sign-in.
        val saved = ApiClient.gson.toJson(list)
        assertFalse(saved.contains("vnc"))
        assertFalse(saved.contains("pw"))
        assertNull(m.serverId)
        assertNull(m.clientId)
        assertNull(m.needsPairing)
    }

    @Test
    fun pairedMachineRoundTrip() {
        val json = ApiClient.gson.toJson(listOf(Machine("m1", "Office", "http://h", "S", "C", true, "low", true)))
        val m = decodeMachines(json)!!.single()
        assertEquals(true, m.needsPairing)
        assertEquals("low", m.liveQuality)
        assertEquals(true, m.liveWebSocket)
    }

    @Test
    fun unreadableList() {
        assertNull(decodeMachines("not json"))
    }

    /** Reversible stand-in for the Keystore. */
    private class FakeCipher : SecretCipher {
        override fun encrypt(plain: ByteArray) = byteArrayOf(7) to plain.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
        override fun decrypt(iv: ByteArray, data: ByteArray) = data.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
    }

    @Test
    fun secretStoreRoundTrip() {
        val prefs = FakePrefs()
        val store = SecretStore(prefs, FakeCipher())
        store.set(SecretStore.token("m1"), "secret")
        assertFalse(prefs.map.values.any { "secret" in it.toString() })
        assertEquals("secret", SecretStore(prefs, FakeCipher()).get("m1.token"))
        store.delete("m1.token")
        assertNull(store.get("m1.token"))
        assertNull(SecretStore(prefs, FakeCipher()).get("m1.token"))
    }
}

class FakePrefs : SharedPreferences {
    val map = mutableMapOf<String, Any?>()

    override fun getAll(): Map<String, *> = map
    override fun getString(key: String, defValue: String?) = map[key] as String? ?: defValue
    override fun getStringSet(key: String, defValues: Set<String>?) = defValues
    override fun getInt(key: String, defValue: Int) = defValue
    override fun getLong(key: String, defValue: Long) = defValue
    override fun getFloat(key: String, defValue: Float) = defValue
    override fun getBoolean(key: String, defValue: Boolean) = defValue
    override fun contains(key: String) = key in map
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        override fun putString(key: String, value: String?) = apply { map[key] = value }
        override fun putStringSet(key: String, values: Set<String>?) = this
        override fun putInt(key: String, value: Int) = this
        override fun putLong(key: String, value: Long) = this
        override fun putFloat(key: String, value: Float) = this
        override fun putBoolean(key: String, value: Boolean) = this
        override fun remove(key: String) = apply { map.remove(key) }
        override fun clear() = apply { map.clear() }
        override fun commit() = true
        override fun apply() {}
    }
}
