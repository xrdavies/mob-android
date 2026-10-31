package com.xrdavies.mob.viewmodel

import android.content.SharedPreferences
import com.xrdavies.mob.data.ApiClient
import com.xrdavies.mob.data.MemoryHistory
import com.xrdavies.mob.data.Session
import com.xrdavies.mob.data.SessionDetail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.Executors

class ChatHistoryTest {
    private fun detail(turns: List<Pair<Long, Long>>, rev: Long): ChatDetail {
        val tasks = turns.joinToString(",") { (seq, r) -> """{"id":"t$seq","sessionId":"s","status":"succeeded","seq":$seq,"rev":$r}""" }
        val messages = turns.joinToString(",") { (seq, r) -> """{"id":"t$seq:a","taskId":"t$seq","role":"assistant","content":"t$seq r$r"}""" }
        val json = """{"session":{"id":"s","messages":[$messages]},"tasks":[$tasks],"activities":[],"questions":[],"rev":$rev}"""
        return ChatDetail.merged(ApiClient.gson.fromJson(json, SessionDetail::class.java), null)
    }

    @Test
    fun mergingKeepsTheHigherRevAndNeverAppendsAnOlderTurn() {
        val base = detail(listOf(3L to 3L, 4L to 4L), 4)
        val d = ChatDetail.mergedTurns(detail(listOf(4L to 9L, 2L to 8L, 5L to 9L), 9), base, 3)
        assertEquals(listOf("t3", "t4", "t5"), d.tasks.map { it.id })
        assertEquals("t5", d.current?.id)
        assertEquals(listOf("t3 r3", "t4 r9", "t5 r9"), d.session.messages?.map { it.content })
        assertEquals(4L, d.rev)
        assertEquals("t4 r9", ChatDetail.mergedTurns(detail(listOf(4L to 6L), 6), d, 3).session.messages?.get(1)?.content)
        assertEquals(listOf("t1", "t2", "t3", "t4", "t5"), ChatDetail.mergedTurns(detail(listOf(1L to 1L, 2L to 8L), 9), d, 1).tasks.map { it.id })
    }

    /** A mobd with sessionHistory and one conversation "s"; cursors are "c<seq>". */
    private class Office(count: Long) : Dispatcher() {
        val revs = (1..count).map { it }.toMutableList()
        var rev = count
        @Volatile var offline = false
        @Volatile var gone = false
        @Volatile var reset = false
        val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())

        fun change(seq: Int) = synchronized(this) { revs[seq - 1] = ++rev }
        fun add() = synchronized(this) { revs += ++rev }

        override fun dispatch(request: RecordedRequest): MockResponse = synchronized(this) {
            val url = request.requestUrl!!
            requests += url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
            if (offline) return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
            val session = """"id":"s","title":"s","status":"succeeded","updatedAt":"2026-10-01T00:00:00Z""""
            fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
            when {
                url.encodedPath == "/health" -> return json("""{"ok":true,"features":["sessionHistory"]}""")
                url.encodedPath == "/sessions" -> return json(if (gone) """{"sessions":[]}""" else """{"sessions":[{$session}]}""")
                gone -> return json("""{"error":"session not found"}""").setResponseCode(404)
            }
            fun answer(seqs: List<Int>, extra: String): String {
                val tasks = seqs.joinToString(",") { """{"id":"t$it","sessionId":"s","status":"succeeded","eventId":1,"seq":$it,"rev":${revs[it - 1]}}""" }
                val messages = seqs.joinToString(",") { """{"id":"t$it:a","taskId":"t$it","role":"assistant","content":"t$it r${revs[it - 1]}"}""" }
                return """{"session":{$session,"messages":[$messages]},"tasks":[$tasks],"activities":[],"questions":[],"rev":$rev$extra}"""
            }
            val since = url.queryParameter("since")?.toLong()
            if (since != null) {
                if (reset || since == 0L || since > rev) return json(answer(emptyList(), ""","reset":true"""))
                return json(answer(revs.indices.filter { revs[it] > since }.map { it + 1 }, ""","reset":false"""))
            }
            val limit = url.queryParameter("limit")?.toInt() ?: 20
            val before = url.queryParameter("before")?.drop(1)?.toInt() ?: Int.MAX_VALUE
            val older = (1..revs.size).filter { it < before }
            val list = older.takeLast(limit)
            val more = older.size > list.size
            return json(answer(list, if (more) ""","hasMore":true,"before":"c${list.first()}"""" else ""","hasMore":false"""))
        }
    }

    private class Prefs : SharedPreferences, SharedPreferences.Editor {
        private val values = mutableMapOf<String, Any?>()
        override fun getAll() = values
        override fun getString(key: String?, defValue: String?) = values[key] as String? ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?) = defValues
        override fun getInt(key: String?, defValue: Int) = defValue
        override fun getLong(key: String?, defValue: Long) = defValue
        override fun getFloat(key: String?, defValue: Float) = defValue
        override fun getBoolean(key: String?, defValue: Boolean) = defValue
        override fun contains(key: String?) = key in values
        override fun edit() = this
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun putString(key: String?, value: String?) = apply { values[key!!] = value }
        override fun putStringSet(key: String?, values: MutableSet<String>?) = this
        override fun putInt(key: String?, value: Int) = this
        override fun putLong(key: String?, value: Long) = this
        override fun putFloat(key: String?, value: Float) = this
        override fun putBoolean(key: String?, value: Boolean) = this
        override fun remove(key: String?) = apply { values.remove(key) }
        override fun clear() = apply { values.clear() }
        override fun commit() = true
        override fun apply() {}
    }

    private lateinit var server: MockWebServer
    private val executor = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
    private val stores = mutableListOf<ChatStore>()

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() {
        scope.cancel()
        executor.shutdownNow()
        server.shutdown()
    }

    private fun chat(office: Office, history: MemoryHistory): ChatStore {
        server.dispatcher = office
        val prefs = Prefs().apply { putString("session.office", "s") }
        val store = ChatStore(scope, prefs, { _, _ -> }, history)
        // Only the newest store polls: a previous "launch" detaches.
        stores.forEach { it.attach(ApiClient("http://localhost/", null), "", enabled = false) }
        stores += store
        store.attach(ApiClient(server.url("/").toString(), "T"), "office", enabled = true, historyKey = "server")
        return store
    }

    private fun until(what: String, condition: () -> Boolean) = runBlocking {
        repeat(200) { if (condition()) return@runBlocking; delay(20) }
        assertTrue(what, condition())
    }

    @Test
    fun pagesSavesAndSyncsOnlyChangesAfterRelaunch() {
        val office = Office(45)
        val history = MemoryHistory()
        var c = chat(office, history)
        until("newest page") { c.detail.value?.tasks?.size == 20 }
        assertEquals(26L, c.detail.value?.tasks?.first()?.seq)
        assertTrue(c.hasOlder())
        assertEquals("/sessions/s/history?limit=20", office.requests.last())
        c.loadOlder()
        until("second page") { c.detail.value?.tasks?.size == 40 }
        assertEquals(45L, c.detail.value?.current?.seq)
        assertTrue(office.requests.last().contains("before=c26"))
        Thread.sleep(200)

        // Relaunch: the saved copy, then only what changed; an old changed turn is not appended.
        office.change(2)
        office.add()
        c = chat(office, history)
        until("changes merged") { c.detail.value?.current?.seq == 46L }
        assertTrue(office.requests.any { it.contains("since=45") })
        assertEquals("the newest 20 saved turns, then the new one", 26L, c.detail.value?.tasks?.first()?.seq)
        assertFalse(c.detail.value!!.tasks.any { it.seq == 2L })
        val pages = office.requests.count { it.contains("before=") }
        c.loadOlder()
        until("older turns from the local copy") { c.detail.value?.tasks?.first()?.seq == 6L }
        assertEquals("no request for saved turns", pages, office.requests.count { it.contains("before=") })
    }

    @Test
    fun resetLoadsTheNewestPageAndA404RemovesTheCopy() {
        val office = Office(25)
        val history = MemoryHistory()
        var c = chat(office, history)
        until("newest page") { c.detail.value?.tasks?.size == 20 }
        Thread.sleep(200)
        office.reset = true
        office.requests.clear()
        c = chat(office, history)
        until("reset to the newest page") { office.requests.lastOrNull() == "/sessions/s/history?limit=20" && c.sync.value == SyncState.None }
        assertTrue(office.requests.any { it.contains("since=25") })
        assertEquals(6L, c.detail.value?.tasks?.first()?.seq)
        office.gone = true
        c.resync()
        until("removed") { c.currentId.value == null }
        Thread.sleep(200)
        assertNull(runBlocking { history.load("server", "s", null, 1) })
    }

    @Test
    fun offlineShowsTheSavedCopy() {
        val office = Office(25)
        val history = MemoryHistory()
        var c = chat(office, history)
        until("newest page") { c.detail.value?.tasks?.size == 20 }
        Thread.sleep(200)
        office.offline = true
        c = chat(office, history)
        until("saved copy, offline") { c.sync.value == SyncState.Offline && c.sessions.value.isNotEmpty() }
        assertEquals(6L, c.detail.value?.tasks?.first()?.seq)
        assertEquals(listOf("s"), c.sessions.value.map(Session::id))
        assertNull(c.error.value)
        c.loadOlder()
        until("older not available") { c.olderError.value != null }
        assertEquals("Older messages aren't available offline", c.olderError.value)
    }
}
