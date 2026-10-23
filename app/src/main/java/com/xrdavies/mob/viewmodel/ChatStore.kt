package com.xrdavies.mob.viewmodel

import android.content.SharedPreferences
import com.xrdavies.mob.data.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
    }
}

/**
 * Chats on the active machine: the list, the open conversation, and every action on them.
 * One instance lives in [AppState]; [attach] points it at another machine.
 * [onUnauthorized] gets the machine and token mobd refused; AppState then detaches the store.
 */
class ChatStore(
    private val scope: CoroutineScope,
    private val prefs: SharedPreferences,
    private val onUnauthorized: (machine: String, token: String?) -> Unit,
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

    private var poll: Job? = null
    private var listPoll: Job? = null

    /** The running turn followed over GET /tasks/{id}/events, if any. */
    private var stream: Job? = null
    private var streamTask: String? = null

    /** Recently opened conversations on this machine, oldest first: reopening one shows it at once. */
    private val cache = LinkedHashMap<String, ChatDetail>()

    /** True while the machine's global event stream is connected; polling then only backs it up. */
    var live = false

    fun attach(client: ApiClient, machine: String, enabled: Boolean) {
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
            if (gen == generation) _sessions.value = list
            if (_archived.value != null) {
                val arch = client.api.listSessions("true").sessions.orEmpty()
                if (gen == generation) _archived.value = arch
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // The health check reports connection problems; the list just keeps its last state.
            if (e.isUnauthorized() && gen == generation) unauthorized()
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
        _currentId.value = id
        _detail.value = id?.let { cache[it] }
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

    /** Fetches the open conversation now and keeps refreshing it (fast while a turn runs). */
    private fun follow(id: String) {
        poll?.cancel()
        val client = api ?: return
        val gen = generation
        poll = scope.launch {
            var first = true
            while (isActive && gen == generation && _currentId.value == id) {
                try {
                    val d = client.api.getSession(id, _detail.value?.rev)
                    if (gen != generation || _currentId.value != id) break
                    val fresh = ChatDetail.merged(d, _detail.value)
                    val shown = _detail.value
                    // A snapshot fetched before newer stream events must not replace them.
                    val stale = shown != null && shown.current?.id == fresh.current?.id && fresh.busy &&
                        (fresh.current?.eventId ?: 0) < (shown.current?.eventId ?: 0)
                    if (!stale) _detail.value = fresh
                    if (_detail.value?.busy != true) _cancelling.value = false
                    followTurn(client, gen)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (e.isUnauthorized()) {
                        if (gen == generation) fail(e)
                        break
                    }
                    if (first && e is retrofit2.HttpException && e.code() == 404) {
                        // The saved chat was deleted elsewhere.
                        if (gen == generation) removeSession(id)
                        break
                    }
                    if (first && gen == generation) fail(e)
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
    private fun reload() = _currentId.value?.let(::follow)

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
            )
        )
        // A new turn (from another device) or a status change the turn stream did not deliver.
        val newTurn = s.activeTaskId != null && s.activeTaskId != d.current?.id
        if (newTurn || (stream == null && s.status != d.current?.status)) reload()
    }

    fun removeSession(id: String) {
        _sessions.value = _sessions.value.filterNot { it.id == id }
        _archived.value = _archived.value?.filterNot { it.id == id }
        if (id == _currentId.value) open(null)
        cache.remove(id)
    }

    /** After the global stream reconnected past its buffer: refetch everything. */
    fun refreshAll() {
        refreshSessions()
        reload()
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
}
