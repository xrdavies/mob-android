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
    fun machineSavedBeforeAddressesKeepsItsTokenKey() {
        val m = decodeMachines("""[{"id":"m1","name":"Office","mobdUrl":"http://office.local:8080","serverId":"S","clientId":"C"}]""")!!.single()
        assertEquals(listOf("m1"), m.addresses.map { it.id })
        assertEquals("m1.token", m.tokenKey)
        assertEquals("http://office.local:8080", m.mobdUrl)
        assertEquals("C", m.clientId)
        // Saved again in the new shape, it reads back the same.
        assertEquals(m, decodeMachines(ApiClient.gson.toJson(listOf(m)))!!.single())
        assertFalse(ApiClient.gson.toJson(listOf(m)).contains("mobdUrl"))
    }

    @Test
    fun addressKinds() {
        for (lan in listOf("http://office-mac.local:8080", "http://192.168.1.20:8080", "http://10.0.0.2", "http://172.20.1.1", "http://[fd00::1]:8080")) {
            assertEquals(lan, AddressKind.LAN, AddressKind.of(lan))
        }
        for (remote in listOf("https://office.tail49e461.ts.net", "https://mob.example.com", "http://100.101.1.2:8080", "http://172.32.0.1")) {
            assertEquals(remote, AddressKind.REMOTE, AddressKind.of(remote))
        }
    }

    @Test
    fun pairingAddsASecondAddressToTheSameOffice() {
        var n = 0
        val ids = { "a${++n}" }
        val m = Machine("m", "Office", "http://office.local:8080").copy(serverId = "S")
        val (two, paired, dropped) = m.pairing("https://office.tail.ts.net", "S", "C2", ids)
        assertEquals("a1", paired.id)
        assertEquals(emptyList<String>(), dropped)
        assertEquals(listOf("http://office.local:8080", "https://office.tail.ts.net"), two.addresses.map { it.url })
        assertEquals("the first address keeps its token", "m.token", SecretStore.token(two.addresses[0].id))
        assertEquals("the new address is used", "https://office.tail.ts.net", two.mobdUrl)
        assertEquals(listOf("LAN", "Remote"), two.addresses.map { two.label(it) })

        // The same address again is paired again, not added.
        val (again, a, _) = two.pairing("https://office.tail.ts.net", "S", "C3", ids)
        assertEquals("a1", a.id)
        assertEquals(2, again.addresses.size)
        assertEquals("C3", again.clientId)

        // Another office replaces the addresses (the user confirmed it) and drops their tokens.
        val (other, _, gone) = two.pairing("http://10.0.0.9:8080", "T", "D", ids)
        assertEquals(listOf("http://10.0.0.9:8080"), other.addresses.map { it.url })
        assertEquals("m", other.addresses[0].id)
        assertEquals(setOf("m.token", "a1.token"), gone.toSet())
    }

    @Test
    fun routeLabelsAndChoice() {
        var m = Machine("m", "Office", "https://a.ts.net")
        m = m.copy(addresses = m.addresses + MachineAddress("b", "https://b.example.com"))
        assertEquals(listOf("Remote", "Remote 2"), m.addresses.map { m.label(it) })
        assertEquals("Cloudflare", m.copy(addresses = listOf(m.addresses[0], m.addresses[1].copy(label = "Cloudflare"))).let { it.label(it.addresses[1]) })
        assertEquals("the first address without a choice", "https://a.ts.net", m.mobdUrl)
        m = m.copy(route = "b")
        assertEquals("https://b.example.com", m.mobdUrl)
        m = m.withNeedsPairing(true)
        assertEquals(true, m.addresses[1].needsPairing)
        assertNull("only the refused address needs pairing", m.addresses[0].needsPairing)
        assertEquals("https://a.ts.net", m.copy(route = "gone").mobdUrl)
    }

    @Test
    fun pairedMachineRoundTrip() {
        val json = ApiClient.gson.toJson(listOf(Machine("m1", "Office", listOf(MachineAddress("m1", "http://h", "C", true)), null, "S", "low", true)))
        val m = decodeMachines(json)!!.single()
        assertEquals(true, m.needsPairing)
        assertEquals("C", m.clientId)
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
