package com.xrdavies.mob

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xrdavies.mob.data.ApiClient
import com.xrdavies.mob.data.Message
import com.xrdavies.mob.data.Session
import com.xrdavies.mob.data.SqliteHistory
import com.xrdavies.mob.data.StoredTurn
import com.xrdavies.mob.data.Task
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** The SQLite copy on a device or emulator (the JVM tests use MemoryHistory). */
@RunWith(AndroidJUnit4::class)
class SqliteHistoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun file() = File(context.cacheDir, "history-${UUID.randomUUID()}.sqlite")

    private fun session(id: String) = ApiClient.gson.fromJson("""{"id":"$id","title":"$id"}""", Session::class.java)

    private fun turn(seq: Long, rev: Long, size: Int = 10): StoredTurn {
        val task = ApiClient.gson.fromJson("""{"id":"t$seq","sessionId":"s","status":"succeeded","seq":$seq,"rev":$rev}""", Task::class.java)
        return StoredTurn(task, listOf(Message("t$seq:a", "t$seq", "assistant", "x".repeat(size), null)), emptyList(), emptyList())
    }

    @Test
    fun pagesTurnsAndKeepsNewerTurnsAndCursors() = runBlocking {
        val h = SqliteHistory(context, file())
        h.save("m", session("s"), 9, (2L..5L).map { turn(it, it) }, mapOf("t2" to "c2"), replace = true)
        h.save("m", session("s"), 10, listOf(turn(3, 1, size = 99)))
        val saved = h.load("m", "s", null, 2)!!
        assertEquals(10L, saved.syncRev)
        assertEquals(2L, saved.oldest)
        assertEquals("c2", saved.cursor)
        assertEquals(listOf(4L, 5L), saved.turns.map { it.task.seq })
        val older = h.load("m", "s", 4, 9)!!
        assertEquals(listOf(2L, 3L), older.turns.map { it.task.seq })
        assertEquals("a lower rev does not replace a turn", 10, older.turns[1].messages.first().content.length)
        h.save("m", session("s"), 11, listOf(turn(3, 12, size = 5)))
        assertEquals("a higher one does, keeping its cursor", 5, h.load("m", "s", 4, 9)!!.turns[1].messages.first().content.length)
        assertNull(h.load("m", "other", null, 1))
        h.prune("m", emptySet())
        assertTrue(h.list("m").isEmpty())
    }

    @Test
    fun evictsLeastRecentlyOpenedAndTrimsTheLast() = runBlocking {
        val probe = SqliteHistory(context, file())
        probe.save("m", session("s"), 1, listOf(turn(1, 1, size = 400)))
        val u = probe.size()
        val h = SqliteHistory(context, file(), limit = u * 7 / 2, target = u * 32 / 10)
        for (id in listOf("a", "b", "c")) h.save("m", session(id), 1, listOf(turn(1, 1, size = 400)))
        h.load("m", "a", null, 1)
        h.save("m", session("c"), 2, listOf(turn(2, 2, size = 400)), keep = setOf("c"))
        assertEquals(1, h.load("m", "a", Long.MAX_VALUE, 9)!!.turns.size)
        assertEquals("b was opened least recently", 0, h.load("m", "b", Long.MAX_VALUE, 9)!!.turns.size)
        assertEquals(0L, h.load("m", "b", null, 9)!!.syncRev)
        assertEquals("the open conversation is kept", 2, h.load("m", "c", Long.MAX_VALUE, 9)!!.turns.size)
        assertEquals("conversations stay listed", 3, h.list("m").size)

        val one = SqliteHistory(context, file(), limit = u * 5, target = u * 5 / 2)
        one.save("m", session("s"), 7, (1L..6L).map { turn(it, 1, size = 400) }, mapOf("t1" to "c1", "t3" to "c3", "t5" to "c5"))
        val trimmed = one.load("m", "s", null, 9)!!
        assertEquals("keeps its newest turns, from one with a cursor", listOf(5L, 6L), trimmed.turns.map { it.task.seq })
        assertEquals("c5", trimmed.cursor)
    }

    @Test
    fun removesAndClears() = runBlocking {
        val h = SqliteHistory(context, file())
        for ((m, id) in listOf("m" to "a", "m" to "b", "n" to "a")) h.save(m, session(id), 1, listOf(turn(1, 1)))
        h.remove("m", "a")
        assertEquals(listOf("b"), h.list("m").map { it.id })
        h.remove("m")
        assertTrue(h.list("m").isEmpty())
        assertEquals(1, h.list("n").size)
        h.clear()
        assertEquals(0L, h.size())
    }
}
