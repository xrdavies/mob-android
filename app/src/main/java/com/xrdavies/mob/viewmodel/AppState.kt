package com.xrdavies.mob.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.xrdavies.mob.data.ApiClient
import com.xrdavies.mob.data.Health
import com.xrdavies.mob.data.Machine
import com.xrdavies.mob.data.Session
import com.xrdavies.mob.data.Status
import com.xrdavies.mob.data.readable
import com.xrdavies.mob.notify.Notifier
import com.xrdavies.mob.notify.WatchService
import com.xrdavies.mob.vnc.VncSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

class AppState(context: Context) : ViewModel(), MachineEvents {
    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences("mob", Context.MODE_PRIVATE)
    private val gson = Gson()

    private val _machines = MutableStateFlow(loadMachines())
    val machines: StateFlow<List<Machine>> = _machines

    private val _activeMachineId = MutableStateFlow(
        prefs.getString("activeMachine", null)?.takeIf { id -> _machines.value.any { it.id == id } }
            ?: _machines.value.firstOrNull()?.id
    )
    val activeMachineId: StateFlow<String?> = _activeMachineId

    val activeMachine: Machine? get() = _machines.value.firstOrNull { it.id == _activeMachineId.value }

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    private val _health = MutableStateFlow<Health?>(null)
    val health: StateFlow<Health?> = _health

    /** Why the last health check failed; null when connected or not checked yet. */
    private val _healthError = MutableStateFlow<String?>(null)
    val healthError: StateFlow<String?> = _healthError

    private val _showDesktopDrawer = MutableStateFlow(false)
    val showDesktopDrawer: StateFlow<Boolean> = _showDesktopDrawer

    /** One live view shared by the thumbnail and the full-screen view. */
    val vnc = VncSession()
    private var desktopUsers = 0

    private var _apiClient: ApiClient = ApiClient(activeMachine?.mobdUrl.orEmpty())
    val apiClient: ApiClient
        get() = _apiClient

    val chat = ChatStore(viewModelScope, prefs)
    private var healthJob: Job? = null

    val notifier = Notifier(appContext)

    /** Global event streams, one per machine, so every Mac can notify (not only the active one). */
    private val watchers = mutableMapOf<String, MachineWatcher>()
    private val liveMachines = mutableSetOf<String>()

    /** Chats waiting for an answer, per machine; the badge counts them all. */
    private val _waiting = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val waitingCount: StateFlow<Int> get() = _waitingCount
    private val _waitingCount = MutableStateFlow(0)

    private fun setWaiting(machine: String, update: (Set<String>) -> Set<String>) {
        _waiting.value = _waiting.value + (machine to update(_waiting.value[machine].orEmpty()))
        _waitingCount.value = _waiting.value.values.sumOf { it.size }
    }

    /** Turns waiting for the desktop, per machine (all chats on it). */
    private val _queued = MutableStateFlow<Map<String, Int>>(emptyMap())
    val queued: StateFlow<Map<String, Int>> = _queued

    /** An activity is on screen (set by MobApplication). */
    var foreground = false

    /** Keep watching in the background with a foreground service (Settings). */
    private val _backgroundWatch = MutableStateFlow(prefs.getBoolean("backgroundWatch", true))
    val backgroundWatch: StateFlow<Boolean> = _backgroundWatch

    /** Bumped to bring the Chat tab to the front (notification taps). */
    private val _showChat = MutableStateFlow(0)
    val showChat: StateFlow<Int> = _showChat

    init {
        activate()
        syncWatchers()
    }

    private fun loadMachines(): List<Machine> {
        prefs.getString("machines", null)?.let { json ->
            runCatching { gson.fromJson<List<Machine>>(json, object : TypeToken<List<Machine>>() {}.type) }
                .getOrNull()?.let { return it }
        }
        // Before machines existed the app stored one URL and one sign-in.
        val url = prefs.getString("mobdUrl", "").orEmpty()
        if (url.isBlank()) return emptyList()
        val m = Machine(
            UUID.randomUUID().toString(), "Office Mac", url,
            prefs.getString("vncUsername", "").orEmpty(), prefs.getString("vncPassword", "").orEmpty(),
        )
        saveMachines(listOf(m))
        return listOf(m)
    }

    private fun saveMachines(list: List<Machine>) {
        prefs.edit().putString("machines", gson.toJson(list)).apply()
    }

    /** Points the API client, chats, health and live view at the active machine. */
    private fun activate() {
        val m = activeMachine
        prefs.edit().putString("activeMachine", m?.id).apply()
        _apiClient = ApiClient(m?.mobdUrl.orEmpty())
        chat.live = m?.id in liveMachines
        _health.value = null
        _isConnected.value = false
        _healthError.value = null
        chat.attach(_apiClient, m?.id.orEmpty(), enabled = m != null)
        healthJob?.cancel()
        healthJob = viewModelScope.launch {
            while (isActive) {
                checkHealthNow()
                delay(30_000)
            }
        }
        if (desktopUsers > 0) connectDesktop() else vnc.disconnect()
    }

    fun selectMachine(id: String) {
        if (id == _activeMachineId.value) return
        _activeMachineId.value = id
        activate()
    }

    /** Adds or updates a machine; returns its ID. */
    fun saveMachine(m: Machine): String {
        val list = _machines.value
        val updated = if (list.any { it.id == m.id }) list.map { if (it.id == m.id) m else it } else list + m
        _machines.value = updated
        saveMachines(updated)
        if (_activeMachineId.value == null || m.id == _activeMachineId.value) {
            _activeMachineId.value = m.id
            activate()
        }
        syncWatchers()
        return m.id
    }

    fun deleteMachine(id: String) {
        val updated = _machines.value.filterNot { it.id == id }
        _machines.value = updated
        setWaiting(id) { emptySet() }
        saveMachines(updated)
        prefs.edit().remove("session.$id").apply()
        if (id == _activeMachineId.value) {
            _activeMachineId.value = updated.firstOrNull()?.id
            activate()
        }
        syncWatchers()
    }

    /** Starts a watcher for each machine, and restarts one whose URL changed. */
    private fun syncWatchers() {
        val machines = _machines.value.associateBy { it.id }
        watchers.entries.removeAll { (id, w) ->
            (machines[id]?.mobdUrl != w.machine.mobdUrl).also { gone ->
                if (gone) {
                    w.job.cancel()
                    liveMachines -= id
                }
            }
        }
        for (m in machines.values) if (m.id !in watchers) watchers[m.id] = MachineWatcher(m, viewModelScope, this)
        updateBackgroundService()
    }

    fun setBackgroundWatch(on: Boolean) {
        _backgroundWatch.value = on
        prefs.edit().putBoolean("backgroundWatch", on).apply()
        updateBackgroundService()
    }

    /** Runs the background service while it is wanted and there is something to watch. */
    fun updateBackgroundService() {
        if (_backgroundWatch.value && _machines.value.isNotEmpty()) {
            if (foreground) WatchService.start(appContext)
        } else WatchService.stop(appContext)
    }

    /** Opens a chat on a machine (from a notification). */
    fun openChat(machineId: String, sessionId: String?) {
        if (_machines.value.none { it.id == machineId }) return
        selectMachine(machineId)
        if (sessionId != null) chat.open(sessionId)
        _showChat.value++
    }

    private fun isOpen(m: Machine, s: Session) = foreground && m.id == _activeMachineId.value && chat.currentId.value == s.id

    override fun onSeed(m: Machine, sessions: List<Session>) {
        setWaiting(m.id) { sessions.filter { it.waiting == true }.map { it.id }.toSet() }
    }

    override fun onSession(m: Machine, s: Session, before: Session?) {
        setWaiting(m.id) { if (s.waiting == true) it + s.id else it - s.id }
        if (m.id == _activeMachineId.value) chat.applySession(s)
        val nowWaiting = s.waiting == true
        when {
            nowWaiting && before?.waiting != true -> if (!isOpen(m, s)) notifyQuestion(m, s)
            !nowWaiting && before?.waiting == true -> notifier.answered(m, s)
        }
        val finished = (s.status == Status.SUCCEEDED || s.status == Status.FAILED) && Status.busy(before?.status)
        if (finished && !isOpen(m, s)) notifier.finished(m, s, failed = s.status == Status.FAILED)
    }

    /** Fetches the pending question so the notification can show it. */
    private fun notifyQuestion(m: Machine, s: Session) {
        Log.add("${m.name}: “${s.displayTitle}” is waiting for you")
        viewModelScope.launch {
            val q = runCatching { ApiClient(m.mobdUrl).api.getSession(s.id) }.getOrNull()
                ?.questions?.lastOrNull { it.isPending }
            notifier.question(m, s, q?.isApproval == true, q?.text, _waitingCount.value)
        }
    }

    override fun onDeleted(m: Machine, id: String) {
        setWaiting(m.id) { it - id }
        if (m.id == _activeMachineId.value) chat.removeSession(id)
    }

    override fun onReset(m: Machine) {
        if (m.id == _activeMachineId.value) chat.refreshAll()
    }

    override fun onQueue(m: Machine, queued: Int) {
        _queued.value = _queued.value + (m.id to queued)
    }

    override fun onLive(m: Machine, live: Boolean) {
        if (live) liveMachines += m.id else liveMachines -= m.id
        if (m.id != _activeMachineId.value) return
        val was = chat.live
        chat.live = live
        // Reconnected: catch up on whatever happened while the stream was down.
        if (live && !was) chat.refreshAll()
    }

    /**
     * Composer choices, remembered per machine ("turn.<machine>.runner") and per machine and
     * runner ("turn.<machine>/<runner>.model" / ".effort"), like the desktop app.
     */
    private val _turnPrefs = MutableStateFlow(
        prefs.all.filterKeys { it.startsWith("turn.") }.mapValues { it.value.toString() }
    )
    val turnPrefs: StateFlow<Map<String, String>> = _turnPrefs

    fun setTurnPref(key: String, value: String?) {
        val k = "turn.$key"
        _turnPrefs.value = if (value.isNullOrEmpty()) _turnPrefs.value - k else _turnPrefs.value + (k to value)
        prefs.edit().apply { if (value.isNullOrEmpty()) remove(k) else putString(k, value) }.apply()
    }

    fun newMachineId(): String = UUID.randomUUID().toString()

    /** A view that shows the desktop is on screen; the live view stays open while any is. */
    fun retainDesktop() {
        if (desktopUsers++ == 0) connectDesktop()
    }

    fun releaseDesktop() {
        if (--desktopUsers == 0) vnc.disconnect()
    }

    fun connectDesktop() {
        val m = activeMachine ?: return
        vnc.connect(_apiClient.okHttpClient, _apiClient.vncUrl, m.vncUsername, m.vncPassword)
    }

    fun checkHealth() {
        viewModelScope.launch { checkHealthNow() }
    }

    private suspend fun checkHealthNow() {
        val client = _apiClient
        if (activeMachine == null) {
            _isConnected.value = false
            _health.value = null
            return
        }
        try {
            val h = client.api.getHealth()
            if (client !== _apiClient) return
            _health.value = h
            if (!_isConnected.value) Log.add("${activeMachine?.name}: connected, runners ${h.runners.orEmpty().joinToString { it.name }}")
            _isConnected.value = h.ok
            _healthError.value = null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (client !== _apiClient) return
            if (_healthError.value != e.readable()) Log.error("${activeMachine?.name}: health check failed: ${e.readable()}")
            _isConnected.value = false
            _health.value = null
            _healthError.value = e.readable()
        }
    }

    fun toggleDesktopDrawer() {
        _showDesktopDrawer.value = !_showDesktopDrawer.value
    }

    private val _showLiveView = MutableStateFlow(false)
    val showLiveView: StateFlow<Boolean> = _showLiveView

    fun openLiveView() {
        _showLiveView.value = true
    }

    fun closeLiveView() {
        vnc.setControl(false)
        _showLiveView.value = false
    }
}
