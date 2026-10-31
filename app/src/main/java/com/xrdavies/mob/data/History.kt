package com.xrdavies.mob.data

// The local copy of conversations and paging (docs/conversation-history.md).

/** One saved turn: the task with its messages, steps and questions. */
data class StoredTurn(
    val task: Task,
    val messages: List<Message>,
    val activities: List<Activity>,
    val questions: List<Question>,
)

/** A saved conversation: its session row, sync revision and the newest turns asked for (oldest first). */
data class SavedConversation(
    val session: Session,
    val syncRev: Long,
    /** The oldest saved turn and mobd's cursor for the page before it. */
    val oldest: Long?,
    val cursor: String?,
    val turns: List<StoredTurn>,
)

/** The local copy, keyed by machine (its serverId from pairing) and conversation. */
interface HistoryStore {
    /** The newest [limit] saved turns older than [before] (null: the newest); null when not saved. */
    suspend fun load(machine: String, id: String, before: Long? = null, limit: Int): SavedConversation?
    suspend fun list(machine: String): List<Session>
    /**
     * Saves the session row and turns ([replace] drops the conversation's other turns first). A turn
     * is only replaced by one with at least its `rev`; [cursors] name turns that were the oldest of a
     * page. Then evicts down to the limit, never touching the [keep] conversations of [machine].
     */
    suspend fun save(machine: String, session: Session, syncRev: Long, turns: List<StoredTurn>,
                     cursors: Map<String, String> = emptyMap(), replace: Boolean = false, keep: Set<String> = emptySet())
    /** One conversation, or the machine's whole copy. */
    suspend fun remove(machine: String, id: String? = null)
    /** Deletes the machine's conversations missing from [ids] (mobd's current list). */
    suspend fun prune(machine: String, ids: Set<String>)
    suspend fun clear()
    /** The bytes of saved conversations, as counted against the limit. */
    suspend fun size(): Long
}

/** A HistoryStore in memory, without eviction (unit tests). */
class MemoryHistory : HistoryStore {
    private class Saved(var session: Session, var syncRev: Long, val turns: MutableMap<String, Pair<StoredTurn, String?>> = mutableMapOf())

    private val data = mutableMapOf<Pair<String, String>, Saved>()

    override suspend fun load(machine: String, id: String, before: Long?, limit: Int): SavedConversation? = synchronized(data) {
        val c = data[machine to id] ?: return null
        val all = c.turns.values.sortedBy { it.first.task.seq ?: 0 }
        val turns = all.filter { before == null || (it.first.task.seq ?: 0) < before }.takeLast(limit).map { it.first }
        SavedConversation(c.session, c.syncRev, all.firstOrNull()?.first?.task?.seq, all.firstOrNull()?.second, turns)
    }

    override suspend fun list(machine: String) = synchronized(data) { data.filterKeys { it.first == machine }.values.map { it.session } }

    override suspend fun save(machine: String, session: Session, syncRev: Long, turns: List<StoredTurn>,
                              cursors: Map<String, String>, replace: Boolean, keep: Set<String>) = synchronized(data) {
        val c = data.getOrPut(machine to session.id) { Saved(session, syncRev) }
        c.session = session.copy(messages = null)
        c.syncRev = syncRev
        if (replace) c.turns.clear()
        for (t in turns) {
            val old = c.turns[t.task.id]
            if (old != null && (old.first.task.rev ?: 0) > (t.task.rev ?: 0)) continue
            c.turns[t.task.id] = t to (cursors[t.task.id] ?: old?.second)
        }
    }

    override suspend fun remove(machine: String, id: String?) = synchronized(data) {
        data.keys.removeAll { it.first == machine && (id == null || it.second == id) }
        Unit
    }

    override suspend fun prune(machine: String, ids: Set<String>) = synchronized(data) {
        data.keys.removeAll { it.first == machine && it.second !in ids }
        Unit
    }

    override suspend fun clear() = synchronized(data) { data.clear() }

    override suspend fun size(): Long = synchronized(data) { data.values.sumOf { c -> c.turns.values.sumOf { ApiClient.gson.toJson(it.first).length.toLong() } } }
}
