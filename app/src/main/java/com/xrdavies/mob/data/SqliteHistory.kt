package com.xrdavies.mob.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import com.xrdavies.mob.viewmodel.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The local copy of conversations in SQLite, in `noBackupFilesDir`: private to the app and not
 * backed up. mobd stays the source of truth, so a corrupt file or another schema version is deleted
 * and rebuilt. All work runs on one thread, so writes keep their order. Failures are logged; the
 * app then works as without a copy.
 */
class SqliteHistory(
    context: Context,
    file: File = File(context.noBackupFilesDir, "history.sqlite"),
    /** The storage limit and the size eviction brings it back under. */
    private val limit: Long = 500L * 1024 * 1024,
    private val target: Long = 450L * 1024 * 1024,
) : HistoryStore {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val io = Dispatchers.IO.limitedParallelism(1)

    private val helper = object : SQLiteOpenHelper(context, file.path, null, SCHEMA) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE conversations (
                     machine TEXT NOT NULL, id TEXT NOT NULL, session TEXT NOT NULL, sync_rev INTEGER NOT NULL,
                     opened_at INTEGER NOT NULL, size INTEGER NOT NULL, PRIMARY KEY (machine, id))"""
            )
            db.execSQL(
                """CREATE TABLE turns (
                     machine TEXT NOT NULL, conversation TEXT NOT NULL, id TEXT NOT NULL, seq INTEGER NOT NULL,
                     rev INTEGER NOT NULL, size INTEGER NOT NULL, cursor TEXT, data TEXT NOT NULL,
                     PRIMARY KEY (machine, conversation, id))"""
            )
            db.execSQL("CREATE INDEX turns_seq ON turns (machine, conversation, seq)")
        }

        // Another schema version: start over, mobd has everything.
        override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) = rebuild(db)
        override fun onDowngrade(db: SQLiteDatabase, old: Int, new: Int) = rebuild(db)

        private fun rebuild(db: SQLiteDatabase) {
            db.execSQL("DROP TABLE IF EXISTS turns")
            db.execSQL("DROP TABLE IF EXISTS conversations")
            onCreate(db)
        }
    }

    private val path = file

    /** Runs [block] on the database thread; a corrupt file is deleted and the next call starts over. */
    private suspend fun <T> db(fallback: T, block: (SQLiteDatabase) -> T): T = withContext(io) {
        try {
            block(helper.writableDatabase)
        } catch (e: SQLiteException) {
            Log.error("local copy of conversations: ${e.message}")
            if (e is android.database.sqlite.SQLiteDatabaseCorruptException) {
                helper.close()
                SQLiteDatabase.deleteDatabase(path)
            }
            fallback
        }
    }

    override suspend fun load(machine: String, id: String, before: Long?, limit: Int): SavedConversation? = db(null) { db ->
        val row = db.rawQuery("SELECT session, sync_rev FROM conversations WHERE machine = ? AND id = ?", arrayOf(machine, id)).use {
            if (it.moveToFirst()) it.getString(0) to it.getLong(1) else null
        } ?: return@db null
        val session = runCatching { ApiClient.gson.fromJson(row.first, Session::class.java) }.getOrNull() ?: return@db null
        if (before == null) {
            db.execSQL(
                "UPDATE conversations SET opened_at = (SELECT COALESCE(MAX(opened_at), 0) + 1 FROM conversations) WHERE machine = ? AND id = ?",
                arrayOf(machine, id),
            )
        }
        val oldest = db.rawQuery("SELECT seq, cursor FROM turns WHERE machine = ? AND conversation = ? ORDER BY seq LIMIT 1", arrayOf(machine, id)).use {
            if (it.moveToFirst()) it.getLong(0) to it.getString(1) else null
        }
        val turns = db.rawQuery(
            "SELECT data FROM turns WHERE machine = ? AND conversation = ? AND seq < ? ORDER BY seq DESC LIMIT ?",
            arrayOf(machine, id, (before ?: Long.MAX_VALUE).toString(), limit.toString()),
        ).use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList() }
            .mapNotNull { runCatching { ApiClient.gson.fromJson(it, StoredTurn::class.java) }.getOrNull() }
            .reversed()
        SavedConversation(session, row.second, oldest?.first, oldest?.second, turns)
    }

    override suspend fun list(machine: String): List<Session> = db(emptyList()) { db ->
        db.rawQuery("SELECT session FROM conversations WHERE machine = ?", arrayOf(machine)).use { c ->
            generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList()
        }.mapNotNull { runCatching { ApiClient.gson.fromJson(it, Session::class.java) }.getOrNull() }
    }

    override suspend fun save(machine: String, session: Session, syncRev: Long, turns: List<StoredTurn>,
                              cursors: Map<String, String>, replace: Boolean, keep: Set<String>) = db(Unit) { db ->
        db.beginTransaction()
        try {
            // Update, else insert: UPSERT needs SQLite 3.24 (Android 11).
            val row = ContentValues().apply {
                put("session", ApiClient.gson.toJson(session.copy(messages = null)))
                put("sync_rev", syncRev)
            }
            if (db.update("conversations", row, "machine = ? AND id = ?", arrayOf(machine, session.id)) == 0) {
                db.execSQL(
                    """INSERT INTO conversations (machine, id, session, sync_rev, opened_at, size)
                       VALUES (?, ?, ?, ?, (SELECT COALESCE(MAX(opened_at), 0) + 1 FROM conversations), 0)""",
                    arrayOf(machine, session.id, row.getAsString("session"), syncRev),
                )
            }
            if (replace) db.execSQL("DELETE FROM turns WHERE machine = ? AND conversation = ?", arrayOf(machine, session.id))
            for (t in turns) {
                val data = ApiClient.gson.toJson(t)
                val key = arrayOf(machine, session.id, t.task.id)
                val held = db.rawQuery("SELECT rev, cursor FROM turns WHERE machine = ? AND conversation = ? AND id = ?", key).use {
                    if (it.moveToFirst()) it.getLong(0) to it.getString(1) else null
                }
                if (held != null && held.first > (t.task.rev ?: 0)) continue
                val values = ContentValues().apply {
                    put("seq", t.task.seq ?: 0)
                    put("rev", t.task.rev ?: 0)
                    put("size", data.toByteArray().size)
                    put("cursor", cursors[t.task.id] ?: held?.second)
                    put("data", data)
                }
                if (held != null) db.update("turns", values, "machine = ? AND conversation = ? AND id = ?", key)
                else db.insertOrThrow("turns", null, values.apply { put("machine", machine); put("conversation", session.id); put("id", t.task.id) })
            }
            resize(db, machine, session.id)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        evict(db, machine, keep)
    }

    private fun evict(db: SQLiteDatabase, machine: String, keep: Set<String>) {
        var total = total(db)
        if (total <= limit) return
        val candidates = db.rawQuery(
            """SELECT c.machine, c.id, SUM(t.size) FROM conversations c
               JOIN turns t ON t.machine = c.machine AND t.conversation = c.id
               GROUP BY c.machine, c.id ORDER BY c.opened_at""", null,
        ).use { c -> generateSequence { if (c.moveToNext()) Triple(c.getString(0), c.getString(1), c.getLong(2)) else null }.toList() }
            .filterNot { it.first == machine && it.second in keep }
        db.beginTransaction()
        try {
            for ((i, candidate) in candidates.withIndex()) {
                if (total <= target) break
                val (m, id, size) = candidate
                if (i + 1 < candidates.size) {
                    db.execSQL("DELETE FROM turns WHERE machine = ? AND conversation = ?", arrayOf(m, id))
                    total -= size
                } else {
                    // The last one keeps its newest turns. The oldest kept turn needs a cursor, or the
                    // turns before it could never be fetched again.
                    val rows = db.rawQuery(
                        "SELECT id, size, cursor IS NOT NULL FROM turns WHERE machine = ? AND conversation = ? ORDER BY seq", arrayOf(m, id),
                    ).use { c -> generateSequence { if (c.moveToNext()) Triple(c.getString(0), c.getLong(1), c.getInt(2) != 0) else null }.toList() }
                    var drop = 0
                    while (drop < rows.size && (total > target || !rows[drop].third)) {
                        total -= rows[drop].second
                        drop++
                    }
                    for (r in rows.take(drop)) {
                        db.execSQL("DELETE FROM turns WHERE machine = ? AND conversation = ? AND id = ?", arrayOf(m, id, r.first))
                    }
                }
                // Without turns, the next open loads the newest page instead of changes since a revision.
                db.execSQL(
                    """UPDATE conversations SET sync_rev = 0 WHERE machine = ?1 AND id = ?2
                       AND NOT EXISTS (SELECT 1 FROM turns WHERE machine = ?1 AND conversation = ?2)""",
                    arrayOf(m, id),
                )
                resize(db, m, id)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override suspend fun remove(machine: String, id: String?) = db(Unit) { db ->
        if (id != null) {
            db.execSQL("DELETE FROM turns WHERE machine = ? AND conversation = ?", arrayOf(machine, id))
            db.execSQL("DELETE FROM conversations WHERE machine = ? AND id = ?", arrayOf(machine, id))
        } else {
            db.execSQL("DELETE FROM turns WHERE machine = ?", arrayOf(machine))
            db.execSQL("DELETE FROM conversations WHERE machine = ?", arrayOf(machine))
        }
    }

    override suspend fun prune(machine: String, ids: Set<String>) {
        val saved = db(emptyList()) { db ->
            db.rawQuery("SELECT id FROM conversations WHERE machine = ?", arrayOf(machine)).use { c ->
                generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList()
            }
        }
        for (id in saved) if (id !in ids) remove(machine, id)
    }

    override suspend fun clear() = db(Unit) { db ->
        db.execSQL("DELETE FROM turns")
        db.execSQL("DELETE FROM conversations")
        db.execSQL("VACUUM")
    }

    override suspend fun size(): Long = db(0L) { total(it) }

    private fun total(db: SQLiteDatabase): Long =
        db.rawQuery("SELECT COALESCE(SUM(size), 0) FROM conversations", null).use { if (it.moveToFirst()) it.getLong(0) else 0 }

    private fun resize(db: SQLiteDatabase, machine: String, id: String) = db.execSQL(
        """UPDATE conversations SET size = length(CAST(session AS BLOB))
             + (SELECT COALESCE(SUM(size), 0) FROM turns WHERE machine = ?1 AND conversation = ?2)
           WHERE machine = ?1 AND id = ?2""",
        arrayOf(machine, id),
    )

    private companion object {
        const val SCHEMA = 1
    }
}
