package com.xrdavies.mob.viewmodel

import android.content.SharedPreferences
import com.xrdavies.mob.data.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The open conversation: summary, turns, steps and questions. */
data class ChatDetail(
    val session: Session,
    val tasks: List<Task>,
    val activities: List<Activity>,
    val questions: List<Question>,
    /** mobd's revision this state was fetched at; null from mobd versions without it. */
    val rev: Long? = null,
) {
    val current: Task? get() = tasks.lastOrNull()
    val busy: Boolean get() = Status.busy(current?.status)

    companion object {
        /**
         * [d] applied to [base]: a full answer replaces it; a partial one (`full: false`) replaces
         * only the turns it carries, with their messages, steps and questions, and keeps the rest.
         */
        fun merged(d: SessionDetail, base: ChatDetail?): ChatDetail {
            val tasks = d.tasks.orEmpty()
            if (d.full != false || base == null || base.session.id != d.session.id) {
                return ChatDetail(d.session, tasks, d.activities.orEmpty(), d.questions.orEmpty(), d.rev)
            }
            val all = base.tasks.toMutableList()
            for (t in tasks) {
                val i = all.indexOfFirst { it.id == t.id }
                if (i >= 0) all[i] = t else all += t
            }
            val changed = tasks.map { it.id }.toSet()
            val known = all.map { it.id }.toSet()
            // Kept in turn order, so a changed older turn stays in its place.
            fun <T> pick(old: List<T>, new: List<T>, turn: (T) -> String): List<T> {
                val byTurn = (old.filterNot { turn(it) in changed } + new).groupBy(turn)
                return all.flatMap { byTurn[it.id].orEmpty() } + old.filterNot { turn(it) in known }
            }
            return ChatDetail(
                d.session.copy(messages = pick(base.session.messages.orEmpty(), d.session.messages.orEmpty()) { it.taskId }),
                all, pick(base.activities, d.activities.orEmpty()) { it.taskId },
                pick(base.questions, d.questions.orEmpty()) { it.taskId }, d.rev ?: base.rev,
            )
        }

        /** Saved turns as a conversation, with [session]'s metadata. */
        fun of(session: Session, turns: List<StoredTurn>, rev: Long?) = ChatDetail(
            session.copy(messages = turns.flatMap { it.messages }), turns.map { it.task },
            turns.flatMap { it.activities }, turns.flatMap { it.questions }, rev,
        )

        /**
         * [d]'s turns merged into [base] by task ID, keeping the copy with the higher `rev`. A turn
         * not held yet joins only when its `seq` is at least [from], so a changed turn older than the
         * held range never lands at the end as [current]. Ordered by `seq`; metadata from [d],
         * revision from [base].
         */
        fun mergedTurns(d: ChatDetail, base: ChatDetail?, from: Long): ChatDetail {
            val held = base?.tasks.orEmpty().associateBy { it.id }
            val fresh = d.tasks.filter { t ->
                val old = held[t.id]
                if (old != null) (old.rev ?: 0) <= (t.rev ?: 0) else (t.seq ?: from) >= from
            }.map { it.id }.toSet()
            val mine = base?.turns()?.filterNot { it.task.id in fresh }.orEmpty()
            return of(d.session, (mine + d.turns(fresh)).sortedBy { it.task.seq ?: 0 }, base?.rev)
        }
    }

    /** The turns (all, or those named), in order. */
    fun turns(ids: Set<String>? = null): List<StoredTurn> {
        val messages = session.messages.orEmpty().groupBy { it.taskId }
        val steps = activities.groupBy { it.taskId }
        val asked = questions.groupBy { it.taskId }
        return tasks.filter { ids == null || it.id in ids }.map {
            StoredTurn(it, messages[it.id].orEmpty(), steps[it.id].orEmpty(), asked[it.id].orEmpty())
        }
    }

    val oldestSeq: Long? get() = tasks.mapNotNull { it.seq }.minOrNull()
}

/** Whether the open conversation is in step with mobd (see docs/conversation-history.md). */
enum class SyncState { None, Syncing, Offline, Failed }

/** A sync result before it is applied: the conversation to show and the turns to save. */
private class Synced(
    val detail: ChatDetail,
    val turns: List<StoredTurn>,
    val cursors: Map<String, String>,
    val replace: Boolean,
    val storedFrom: Long?,
    val before: String?,
)

/**
 * Chats on the active machine: the list, the open conversation, and every action on them.
 * One instance lives in [AppState]; [attach] points it at another machine.
 * [onUnauthorized] gets the machine and token mobd refused; AppState then detaches the store.
 */
class ChatStore(
    private val scope: CoroutineScope,
    private val prefs: SharedPreferences,
    private val onUnauthorized: (machine: String, token: String?) -> Unit,
    /** The local copy; keyed by [historyKey], the machine's serverId, so every address shares one. */
    private val history: HistoryStore = MemoryHistory(),
) {
    private var api: ApiClient? = null
    private var machineId = ""
    private var generation = 0

    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    val sessions: StateFlow<List<Session>> = _sessions

    /** Archived chats; null until the archive is opened. */
    private val _archived = MutableStateFlow<List<Session>?>(null)
    val archived: StateFlow<List<Session>?> = _archived

    private val _currentId = MutableStateFlow<String?>(null)
    val currentId: StateFlow<String?> = _currentId

    private val _detail = MutableStateFlow<ChatDetail?>(null)
    val detail: StateFlow<ChatDetail?> = _detail

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending

    /** A stop was requested and the turn has not ended yet. */
    private val _cancelling = MutableStateFlow(false)
    val cancelling: StateFlow<Boolean> = _cancelling

    /** Last failure, shown once by the UI and then cleared. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _sync = MutableStateFlow(SyncState.None)
    val sync: StateFlow<SyncState> = _sync

    private val _olderLoading = MutableStateFlow(false)
    val olderLoading: StateFlow<Boolean> = _olderLoading

    private val _olderError = MutableStateFlow<String?>(null)
    val olderError: StateFlow<String?> = _olderError

    private var historyKey = ""

    /** mobd serves paged history ("sessionHistory"); null until /health answers. Only then is the local copy used. */
    private var paged: Boolean? = null

    /** The open conversation's oldest saved turn, and mobd's cursor for the page before it. */
    private var storedFrom: Long? = null
    private var before: String? = null
    private var saveTimer: Job? = null

    /** Writes to the local copy, run one after another in order. */
    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch { for (write in writes) runCatching { write() }.onFailure { Log.error("local copy: ${it.readable()}") } }
    }

    /** Turns before the oldest shown one exist, saved or on mobd. */
    fun hasOlder(d: ChatDetail? = _detail.value): Boolean = paged == true && (d?.tasks?.firstOrNull()?.seq ?: 1) > 1

    private var poll: Job? = null
    private var listPoll: Job? = null

    /** The running turn followed over GET /tasks/{id}/events, if any. */
    private var stream: Job? = null
    private var streamTask: String? = null

    /** Recently opened conversations on this machine, oldest first: reopening one shows it at once. */
    private val cache = LinkedHashMap<String, ChatDetail>()

    /** True while the machine's global event stream is connected; polling then only backs it up. */
    var live = false

    /** [historyKey] names the machine's local copy: its serverId from pairing, else the machine ID. */
    fun attach(client: ApiClient, machine: String, enabled: Boolean, historyKey: String? = null) {
        if (saveTimer != null) saveCurrent()
        generation++
        poll?.cancel()
        listPoll?.cancel()
        stopStream()
        live = false
        api = client.takeIf { enabled }
        machineId = machine
        _sessions.value = emptyList()
        _archived.value = null
        _detail.value = null
        _currentId.value = null
        cache.clear()
        _sync.value = SyncState.None
        paged = null
        this.historyKey = historyKey ?: machine
        if (api == null) return
        val gen = generation
        listPoll = scope.launch {
            while (isActive && gen == generation) {
                loadSessions()
                delay(if (live) 60_000 else 10_000)
            }
        }
        open(prefs.getString(key(), null))
    }

    fun clearError() {
        _error.value = null
    }

    private fun key() = "session.$machineId"

    private fun fail(e: Throwable) {
        _error.value = e.readable()
        Log.error(e.readable())
        if (e.isUnauthorized()) unauthorized()
    }

    private fun unauthorized() = onUnauthorized(machineId, api?.token)

    /** Runs [block] against the current machine; drops the result if the machine changed meanwhile. */
    private fun launch(block: suspend (ApiClient, Int) -> Unit) {
        val client = api ?: return
        val gen = generation
        scope.launch {
            try {
                block(client, gen)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen == generation) fail(e)
            }
        }
    }

    private suspend fun loadSessions() {
        val client = api ?: return
        val gen = generation
        try {
            val list = client.api.listSessions().sessions.orEmpty()
            if (gen == generation) {
                _sessions.value = list
                if (_sync.value == SyncState.Offline && _currentId.value == null) _sync.value = SyncState.None
                // Conversations deleted while this device was away leave the local copy too.
                val key = historyKey
                if (paged == true) enqueue { history.prune(key, list.map { it.id }.toSet()) }
            }
            if (_archived.value != null) {
                val arch = client.api.listSessions("true").sessions.orEmpty()
                if (gen == generation) _archived.value = arch
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // The health check reports connection problems; the list just keeps its last state.
            if (e.isUnauthorized() && gen == generation) unauthorized()
            // Unreachable from the start: the conversations saved on this device can still be read.
            if (!e.isUnreachable() || gen != generation || _sessions.value.isNotEmpty()) return
            val saved = history.list(historyKey)
            if (gen != generation || _sessions.value.isNotEmpty() || saved.isEmpty()) return
            _sessions.value = saved.sortedByDescending { it.updatedAt.orEmpty() }
            if (_currentId.value == null) _sync.value = SyncState.Offline
        }
    }

    fun refreshSessions() = launch { _, _ -> loadSessions() }

    fun loadArchived() {
        if (_archived.value == null) _archived.value = emptyList()
        refreshSessions()
    }

    /** Opens a conversation; null starts a new chat. */
    fun open(id: String?) {
        _detail.value?.let(::remember)
        if (saveTimer != null) saveCurrent()
        _currentId.value = id
        // With paged history the local copy replaces the memory cache.
        _detail.value = if (paged == true) null else id?.let { cache[it] }
        storedFrom = null
        before = null
        _olderLoading.value = false
        _olderError.value = null
        if (_sync.value != SyncState.Offline) _sync.value = SyncState.None
        _cancelling.value = false
        prefs.edit().putString(key(), id).apply()
        poll?.cancel()
        stopStream()
        if (id == null) return
        _loading.value = _detail.value == null
        follow(id)
    }

    private fun remember(d: ChatDetail) {
        cache.remove(d.session.id)
        cache[d.session.id] = d
        if (cache.size > 8) cache.remove(cache.keys.first())
    }

    /**
     * Fetches the open conversation now and keeps refreshing it (fast while a turn runs).
     * [indicate] shows "Syncing…" (returning to the app, reconnecting); routine polls show nothing.
     */
    private fun follow(id: String, indicate: Boolean = false) {
        poll?.cancel()
        val client = api ?: return
        val gen = generation
        poll = scope.launch {
            var first = true
            while (isActive && gen == generation && _currentId.value == id) {
                try {
                    if (paged == null) paged = runCatching { client.api.getHealth() }.getOrNull()?.features?.contains("sessionHistory")
                    if (first && paged != false && _detail.value == null) {
                        // The saved copy shows at once; then only what changed since it is fetched.
                        val saved = history.load(historyKey, id, null, PAGE)
                        if (gen != generation || _currentId.value != id) break
                        if (saved != null && saved.turns.isNotEmpty() && _detail.value == null) {
                            _detail.value = ChatDetail.of(saved.session, saved.turns, saved.syncRev.takeIf { it > 0 })
                            storedFrom = saved.oldest
                            before = saved.cursor
                            // Saved by a mobd with paged history, so it is one even while /health does not answer.
                            if (paged == null) paged = true
                            _loading.value = false
                        }
                    }
                    if (first && paged == true && (indicate || _detail.value != null)) _sync.value = SyncState.Syncing
                    var synced: Synced? = null
                    val fresh = if (paged == true) {
                        synced = fetchHistory(client, id)
                        synced.detail
                    } else {
                        val d = client.api.getSession(id, _detail.value?.rev)
                        if (gen != generation || _currentId.value != id) break
                        ChatDetail.merged(d, _detail.value)
                    }
                    if (gen != generation || _currentId.value != id) break
                    val shown = _detail.value
                    // A snapshot fetched before newer stream events must not replace them.
                    val stale = shown != null && shown.current?.id == fresh.current?.id && fresh.busy &&
                        (fresh.current?.eventId ?: 0) < (shown.current?.eventId ?: 0)
                    if (!stale) synced?.let(::apply) ?: run { _detail.value = fresh }
                    _sync.value = SyncState.None
                    if (_detail.value?.busy != true) _cancelling.value = false
                    followTurn(client, gen)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (e.isUnauthorized()) {
                        if (gen == generation) fail(e)
                        break
                    }
                    if ((first || paged == true) && e is retrofit2.HttpException && e.code() == 404) {
                        // The saved chat was deleted elsewhere.
                        if (gen == generation) removeSession(id)
                        break
                    }
                    if (paged == true && _detail.value != null && gen == generation) {
                        // The saved copy stays readable; the status says why it may be behind.
                        _sync.value = if (e.isUnreachable()) SyncState.Offline else SyncState.Failed
                        Log.error("sync failed: ${e.readable()}")
                    } else if (first && gen == generation) fail(e)
                } finally {
                    if (first) _loading.value = false
                    first = false
                }
                delay(
                    when {
                        stream != null -> 30_000L
                        _detail.value?.busy == true -> 2_000L
                        live -> 60_000L
                        else -> 8_000L
                    }
                )
            }
        }
    }

    /** Re-reads the open conversation now (after an action) without clearing it. */
    private fun reload(indicate: Boolean = false) = _currentId.value?.let { follow(it, indicate) }

    /** Returning to the app: catches up with what changed meanwhile, showing "Syncing…". */
    fun resync() {
        if (paged != true || _sync.value == SyncState.Syncing) return
        refreshSessions()
        reload(indicate = true)
    }

    /**
     * What changed since the saved revision, merged into the open conversation; without a saved
     * copy, or when mobd answers `reset`, the newest page replaces it.
     */
    private suspend fun fetchHistory(client: ApiClient, id: String): Synced {
        val rev = _detail.value?.rev
        if (rev != null && _detail.value?.tasks?.isNotEmpty() == true) {
            val d = client.api.sessionHistory(id, since = rev, limit = 50)
            val held = _detail.value?.takeIf { _currentId.value == id } ?: throw kotlinx.coroutines.CancellationException()
            if (d.reset != true) {
                val changes = ChatDetail.merged(d, null)
                val from = held.tasks.firstOrNull()?.seq ?: 0
                val merged = ChatDetail.mergedTurns(changes, held, from).copy(rev = d.rev)
                // A changed turn inside the saved range is saved even when it is not shown yet.
                val low = storedFrom ?: from
                val ids = changes.tasks.filter { (it.seq ?: low) >= low }.map { it.id }.toSet()
                return Synced(merged, changes.turns(ids), emptyMap(), false, storedFrom ?: from, before)
            }
            Log.add("history reset by mobd; loading the newest page")
        }
        val page = client.api.sessionHistory(id, limit = PAGE)
        val fresh = ChatDetail.merged(page, null)
        val cursors = listOfNotNull(fresh.tasks.firstOrNull()?.id?.let { first -> page.before?.let { first to it } }).toMap()
        return Synced(fresh, fresh.turns(), cursors, true, fresh.oldestSeq, page.before)
    }

    private fun apply(s: Synced) {
        _detail.value = s.detail
        storedFrom = s.storedFrom
        before = s.before
        // A running turn is saved every few seconds, not on every poll.
        val running = s.turns.filter { Status.busy(it.task.status) }.map { it.task.id }.toSet()
        save(s.turns.filterNot { it.task.id in running }, s.cursors, s.replace)
        if (running.isNotEmpty()) saveSoon()
    }

    /** Loads the turns before the oldest shown one: from the local copy, else from mobd. */
    fun loadOlder() {
        val shown = _detail.value ?: return
        val from = shown.tasks.firstOrNull()?.seq ?: return
        if (!hasOlder(shown) || _olderLoading.value) return
        val id = shown.session.id
        val gen = generation
        _olderLoading.value = true
        _olderError.value = null
        scope.launch {
            try {
                var page: ChatDetail? = null
                val stored = storedFrom
                if (stored != null && stored < from) {
                    history.load(historyKey, id, from, PAGE)?.takeIf { it.turns.isNotEmpty() }?.let { page = ChatDetail.of(shown.session, it.turns, null) }
                }
                if (page == null) {
                    val cursor = before ?: throw IllegalStateException("mobd gave no cursor for older messages")
                    val client = api ?: return@launch
                    val answer = client.api.sessionHistory(id, before = cursor, limit = PAGE)
                    if (gen != generation || _currentId.value != id) return@launch
                    val fresh = ChatDetail.merged(answer, null)
                    storedFrom = fresh.oldestSeq
                    before = answer.before
                    val cursors = listOfNotNull(fresh.tasks.firstOrNull()?.id?.let { first -> answer.before?.let { first to it } }).toMap()
                    page = fresh
                    save(fresh.turns(), cursors)
                }
                val current = _detail.value
                val older = page
                if (gen != generation || _currentId.value != id || current == null || older == null) return@launch
                // Older pages never move the sync baseline or the session metadata.
                val merged = ChatDetail.mergedTurns(older, current, older.oldestSeq ?: from)
                _detail.value = merged.copy(session = current.session.copy(messages = merged.session.messages))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen != generation || _currentId.value != id) return@launch
                Log.error("loading older turns failed: ${e.readable()}")
                _olderError.value = if (e.isUnreachable()) "Older messages aren't available offline" else "Couldn't load older messages"
            } finally {
                if (gen == generation && _currentId.value == id) _olderLoading.value = false
            }
        }
    }

    private fun enqueue(write: suspend () -> Unit) {
        writes.trySend(write)
    }

    /** Saves [turns] and the session row of the open conversation. */
    private fun save(turns: List<StoredTurn>, cursors: Map<String, String> = emptyMap(), replace: Boolean = false) {
        val d = _detail.value
        if (paged != true || d == null || (turns.isEmpty() && !replace)) return
        val key = historyKey
        val keep = setOf(d.session.id) + _sessions.value.filter { Status.busy(it.status) }.map { it.id }
        enqueue { history.save(key, d.session, d.rev ?: 0, turns, cursors, replace, keep) }
    }

    /** Saves the current turn now (it finished, or the conversation is left). */
    private fun saveCurrent() {
        saveTimer?.cancel()
        saveTimer = null
        val d = _detail.value ?: return
        val t = d.current ?: return
        save(d.turns(setOf(t.id)))
    }

    /** While a turn runs, saves it at most every 5 seconds rather than per event. */
    private fun saveSoon() {
        if (paged != true || saveTimer != null) return
        val id = _currentId.value ?: return
        saveTimer = scope.launch {
            delay(5_000)
            if (_currentId.value != id) return@launch
            saveTimer = null
            saveCurrent()
        }
    }

    /** Deletes a machine's local copy (the machine was removed, or mobd no longer accepts this device). */
    fun forget(historyKey: String) = enqueue { history.remove(historyKey) }

    /** The local copy's size, and clearing it (Settings). */
    suspend fun savedSize(): Long = history.size()

    fun clearSaved(done: () -> Unit) = enqueue {
        history.clear()
        done()
    }

    private fun stopStream() {
        stream?.cancel()
        stream = null
        streamTask = null
    }

    /** Streams the open chat's running turn so replies and steps appear as they happen. */
    private fun followTurn(client: ApiClient, gen: Int) {
        val task = _detail.value?.current?.takeIf { Status.busy(it.status) }
        if (task == null) return stopStream()
        if (streamTask == task.id && stream?.isActive == true) return
        stopStream()
        streamTask = task.id
        stream = scope.launch {
            var failures = 0
            while (isActive && gen == generation && streamTask == task.id) {
                val after = _detail.value?.current?.takeIf { it.id == task.id }?.eventId ?: 0
                try {
                    client.streamClient.sse(client.url("tasks/${task.id}/events?after=$after")).collect { e ->
                        failures = 0
                        if (e.type == OPEN) Log.add("turn ${task.id.take(8)}: stream connected")
                        val ev = runCatching { ApiClient.gson.fromJson(e.data, TaskEvent::class.java) }.getOrNull() ?: return@collect
                        Log.add("turn ${task.id.take(8)} ${e.type}: " + (ev.status ?: ev.activity?.title ?: ev.question?.text ?: ev.message?.take(80) ?: ""))
                        if (gen == generation) applyTaskEvent(task.id, e.type, ev)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures++
                    Log.error("turn ${task.id.take(8)}: stream failed: ${e.readable()}")
                    if (e.isUnauthorized()) {
                        if (gen == generation) unauthorized()
                        break
                    }
                }
                // The stream ended or broke: a snapshot catches up, then it reconnects.
                if (!isActive || streamTask != task.id) break
                delay(minOf(30_000L, 1_000L shl minOf(failures, 5)))
                if (_detail.value?.current?.takeIf { it.id == task.id }?.let { Status.busy(it.status) } != true) break
            }
        }
    }

    private fun <T> List<T>.upsert(item: T, key: (T) -> String): List<T> {
        val i = indexOfFirst { key(it) == key(item) }
        return if (i < 0) this + item else toMutableList().also { it[i] = item }
    }

    private fun applyTaskEvent(taskId: String, type: String, ev: TaskEvent) {
        val d = _detail.value ?: return
        val task = d.current?.takeIf { it.id == taskId } ?: return
        if (ev.id <= (task.eventId ?: 0)) return
        var t = task.copy(eventId = ev.id)
        var session = d.session
        var activities = d.activities
        var questions = d.questions
        when (type) {
            "activity" -> ev.activity?.let { activities = activities.upsert(it) { a -> a.id } }
            "question" -> ev.question?.let { questions = questions.upsert(it) { q -> q.id } }
            "usage" -> t = t.copy(usage = ev.usage ?: t.usage)
            "message" -> if (ev.messageId != null && ev.message != null) {
                // Messages the user added mid-turn already carry their full ID.
                val user = ev.role == "user"
                val m = Message(if (user) ev.messageId else "$taskId:${ev.messageId}", taskId, if (user) "user" else "assistant", ev.message, ev.timestamp)
                session = session.copy(messages = session.messages.orEmpty().upsert(m) { it.id })
            }
            "error" -> ev.message?.let { _error.value = it }
        }
        if (ev.status != null) {
            t = t.copy(
                status = ev.status,
                error = if (ev.status == Status.FAILED) ev.message else null,
                startedAt = t.startedAt ?: ev.timestamp.takeIf { ev.status == Status.RUNNING },
                finishedAt = t.finishedAt ?: ev.timestamp.takeIf { Status.terminal(ev.status) },
                queuePosition = if (ev.status == Status.RUNNING) null else t.queuePosition,
            )
            session = session.copy(status = ev.status)
        }
        _detail.value = d.copy(session = session, tasks = d.tasks.map { if (it.id == taskId) t else it }, activities = activities, questions = questions)
        if (Status.terminal(t.status)) saveCurrent() else saveSoon()
        if (Status.terminal(ev.status)) {
            _cancelling.value = false
            stopStream()
            reload()
            refreshSessions()
        }
    }

    /** A session summary from the machine's global stream. */
    fun applySession(s: Session) {
        val byRecent = compareByDescending<Session> { it.pinned == true }.thenByDescending { it.updatedAt.orEmpty() }
        if (s.archived == true) {
            _sessions.value = _sessions.value.filterNot { it.id == s.id }
            _archived.value = _archived.value?.upsert(s) { it.id }?.sortedWith(byRecent)
        } else {
            _sessions.value = _sessions.value.upsert(s) { it.id }.sortedWith(byRecent)
            _archived.value = _archived.value?.filterNot { it.id == s.id }
        }
        val d = _detail.value ?: return
        if (d.session.id != s.id) return
        _detail.value = d.copy(
            session = d.session.copy(
                title = s.title, status = s.status, waiting = s.waiting, pinned = s.pinned,
                archived = s.archived, activeTaskId = s.activeTaskId, updatedAt = s.updatedAt,
                model = s.model, effort = s.effort,
            )
        )
        // A new turn (from another device) or a status change the turn stream did not deliver.
        val newTurn = s.activeTaskId != null && s.activeTaskId != d.current?.id
        if (newTurn || (stream == null && s.status != d.current?.status)) reload()
    }

    fun removeSession(id: String) {
        val key = historyKey
        enqueue { history.remove(key, id) }
        _sessions.value = _sessions.value.filterNot { it.id == id }
        _archived.value = _archived.value?.filterNot { it.id == id }
        if (id == _currentId.value) open(null)
        cache.remove(id)
    }

    /** After the global stream reconnected past its buffer: refetch everything. */
    fun refreshAll() {
        refreshSessions()
        reload(indicate = true)
    }

    /** Starts a turn; [done] gets true once mobd accepted it (the composer then clears). */
    fun send(
        prompt: String,
        runner: String? = null,
        model: String? = null,
        effort: String? = null,
        attachments: List<String>? = null,
        done: (Boolean) -> Unit = {},
    ) {
        _sending.value = true
        val sessionId = _currentId.value
        var ok = false
        launch { client, gen ->
            try {
                val task = client.api.createTask(CreateTaskRequest(prompt, sessionId, runner, model, effort, attachments?.takeIf { it.isNotEmpty() }))
                ok = true
                if (gen != generation) return@launch
                if (task.sessionId == _currentId.value) reload() else open(task.sessionId)
                loadSessions()
            } finally {
                _sending.value = false
                done(ok)
            }
        }
    }

    fun cancel() {
        val task = _detail.value?.current?.takeIf { Status.busy(it.status) } ?: return
        _cancelling.value = true
        launch { client, _ ->
            try {
                client.api.cancelTask(task.id).orThrow()
            } catch (e: Exception) {
                _cancelling.value = false
                throw e
            }
            reload()
        }
    }

    /** Adds a message to the running turn (runners with steer). */
    fun steer(prompt: String, done: (Boolean) -> Unit = {}) {
        val task = _detail.value?.current ?: return
        _sending.value = true
        var ok = false
        launch { client, _ ->
            try {
                client.api.steerTask(task.id, SteerRequest(prompt)).orThrow()
                ok = true
                reload()
            } finally {
                _sending.value = false
                done(ok)
            }
        }
    }

    fun answer(q: Question, answer: String?, approved: Boolean?) = launch { client, _ ->
        client.api.answerQuestion(q.taskId, q.id, AnswerQuestionRequest(answer?.takeIf { it.isNotBlank() }, approved)).orThrow()
        reload()
    }

    /** Sets the open conversation's model or effort ("" = machine default), for every device. */
    fun setTurnSettings(model: String? = null, effort: String? = null) {
        val id = _currentId.value ?: return
        launch { client, gen ->
            val s = client.api.patchSession(id, PatchSessionRequest(model = model, effort = effort))
            if (gen == generation) applySession(s)
        }
    }

    fun rename(s: Session, title: String) = patch(s, PatchSessionRequest(title = title))
    fun setPinned(s: Session, pinned: Boolean) = patch(s, PatchSessionRequest(pinned = pinned))

    fun setArchived(s: Session, archived: Boolean) {
        patch(s, PatchSessionRequest(archived = archived))
        if (archived && s.id == _currentId.value) open(null)
    }

    private fun patch(s: Session, body: PatchSessionRequest) = launch { client, _ ->
        client.api.patchSession(s.id, body)
        loadSessions()
        if (s.id == _currentId.value) reload()
    }

    fun delete(s: Session) = launch { client, _ ->
        client.api.deleteSession(s.id).orThrow()
        removeSession(s.id)
        loadSessions()
    }

    companion object {
        /** Turns per history page, from mobd or the local copy. */
        const val PAGE = 20
    }
}
