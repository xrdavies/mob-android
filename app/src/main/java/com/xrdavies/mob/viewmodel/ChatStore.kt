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
) {
    val current: Task? get() = tasks.lastOrNull()
    val busy: Boolean get() = Status.busy(current?.status)
}

/**
 * Chats on the active machine: the list, the open conversation, and every action on them.
 * One instance lives in [AppState]; [attach] points it at another machine.
 */
class ChatStore(private val scope: CoroutineScope, private val prefs: SharedPreferences) {
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

    /** Last failure, shown once by the UI and then cleared. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private var poll: Job? = null
    private var listPoll: Job? = null

    fun attach(client: ApiClient, machine: String, enabled: Boolean) {
        generation++
        poll?.cancel()
        listPoll?.cancel()
        api = client.takeIf { enabled }
        machineId = machine
        _sessions.value = emptyList()
        _archived.value = null
        _detail.value = null
        _currentId.value = null
        if (api == null) return
        val gen = generation
        listPoll = scope.launch {
            while (isActive && gen == generation) {
                loadSessions()
                delay(10_000)
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
    }

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
        } catch (_: Exception) {
            // The health check reports connection problems; the list just keeps its last state.
        }
    }

    fun refreshSessions() = launch { _, _ -> loadSessions() }

    fun loadArchived() {
        if (_archived.value == null) _archived.value = emptyList()
        refreshSessions()
    }

    /** Opens a conversation; null starts a new chat. */
    fun open(id: String?) {
        _currentId.value = id
        _detail.value = null
        prefs.edit().putString(key(), id).apply()
        poll?.cancel()
        if (id == null) return
        _loading.value = true
        follow(id)
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
                    val d = client.api.getSession(id)
                    if (gen != generation || _currentId.value != id) break
                    _detail.value = ChatDetail(d.session, d.tasks.orEmpty(), d.activities.orEmpty(), d.questions.orEmpty())
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (first && e is retrofit2.HttpException && e.code() == 404) {
                        // The saved chat was deleted elsewhere.
                        if (gen == generation) open(null)
                        break
                    }
                    if (first && gen == generation) fail(e)
                } finally {
                    if (first) _loading.value = false
                    first = false
                }
                delay(if (_detail.value?.busy == true) 1500 else 8000)
            }
        }
    }

    /** Re-reads the open conversation now (after an action) without clearing it. */
    private fun reload() = _currentId.value?.let(::follow)

    fun send(prompt: String, runner: String? = null, model: String? = null, effort: String? = null, attachments: List<String>? = null) {
        _sending.value = true
        val sessionId = _currentId.value
        launch { client, gen ->
            try {
                val task = client.api.createTask(CreateTaskRequest(prompt, sessionId, runner, model, effort, attachments))
                if (gen != generation) return@launch
                if (task.sessionId == _currentId.value) reload() else open(task.sessionId)
                loadSessions()
            } finally {
                _sending.value = false
            }
        }
    }

    fun cancel() {
        val task = _detail.value?.current ?: return
        launch { client, _ ->
            client.api.cancelTask(task.id).orThrow()
            reload()
        }
    }

    fun steer(prompt: String) {
        val task = _detail.value?.current ?: return
        _sending.value = true
        launch { client, _ ->
            try {
                client.api.steerTask(task.id, SteerRequest(prompt)).orThrow()
                reload()
            } finally {
                _sending.value = false
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
        if (s.id == _currentId.value) open(null)
        loadSessions()
    }
}
