package com.xrdavies.mob.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.xrdavies.mob.data.AnswerQuestionRequest
import com.xrdavies.mob.data.ApiClient
import com.xrdavies.mob.data.SqliteHistory
import com.xrdavies.mob.data.DesktopEvent
import com.xrdavies.mob.data.DesktopState
import com.xrdavies.mob.data.Health
import com.xrdavies.mob.data.Machine
import com.xrdavies.mob.data.NEEDS_PAIRING
import com.xrdavies.mob.data.NO_DESKTOP
import com.xrdavies.mob.data.PairLink
import com.xrdavies.mob.data.SecretStore
import com.xrdavies.mob.data.Session
import com.xrdavies.mob.data.Status
import com.xrdavies.mob.data.decodeMachines
import com.xrdavies.mob.data.isUnauthorized
import com.xrdavies.mob.data.pair as redeemInvite
import com.xrdavies.mob.data.readable
import com.xrdavies.mob.data.sessionDesktops
import com.xrdavies.mob.notify.Notifier
import com.xrdavies.mob.notify.WatchService
import com.xrdavies.mob.stream.LiveQuality
import com.xrdavies.mob.stream.LiveSession
import com.xrdavies.mob.stream.UPDATE_MOBD
import com.xrdavies.mob.stream.LiveStatus
import com.xrdavies.mob.stream.StreamSession
import com.xrdavies.mob.stream.LiveKeys
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.util.UUID
import java.util.concurrent.TimeUnit

class AppState(context: Context) : ViewModel(), MachineEvents {
    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences("mob", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val secrets = SecretStore(appContext)

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
    val stream = StreamSession(appContext)
    val live: StateFlow<LiveSession> = MutableStateFlow(stream)
    private var desktopUsers = 0
    /**
     * The machine (and, with conversation desktops, the chat) the live view was opened for, so a
     * health check does not restart it and a new machine or chat does.
     */
    private var liveMachine: String? = null

    private var _apiClient: ApiClient = clientFor(activeMachine)
    val apiClient: ApiClient
        get() = _apiClient

    val chat = ChatStore(viewModelScope, prefs, ::markNeedsPairing, SqliteHistory(appContext))

    /** Conversation desktops on the active machine; empty unless it has `sessionDesktops`. */
    val desktops = DesktopStore(viewModelScope, ::markNeedsPairing)
    val files = FileTransferStore(appContext, viewModelScope, ::markNeedsPairing)
    private var healthJob: Job? = null

    val notifier = Notifier(appContext)

    /** Global event streams, one per machine, so every machine can notify (not only the active one). */
    private val watchers = mutableMapOf<String, MachineWatcher>()
    private val liveMachines = mutableSetOf<String>()

    /** Whether each machine's event stream is connected over the address in use; absent while not known yet. */
    private val _routeUp = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val routeUp: StateFlow<Map<String, Boolean>> = _routeUp

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
        // With conversation desktops the live view follows the open chat.
        viewModelScope.launch {
            chat.currentId.collect { if (desktopUsers > 0 && _health.value.sessionDesktops) connectDesktop() }
        }
        // A chat whose desktop did not exist yet when the live view opened: connect once it runs.
        desktops.onChange = { d ->
            val s = stream.state.value
            if (desktopUsers > 0 && d.sessionId == chat.currentId.value && d.state == DesktopState.RUNNING &&
                s.status == LiveStatus.Error && s.detail == NO_DESKTOP
            ) connectDesktop()
        }
    }

    private fun loadMachines(): List<Machine> {
        val raw = prefs.getString("machines", null)
        val stored = raw?.let(::decodeMachines)
        // No machine is made up: a fresh install starts empty and adds one from a mobd link.
        val list = stored ?: emptyList()
        var changed = false
        // Addresses from before pairing have no token and wait for the user to pair them.
        val migrated = list.map { m ->
            m.copy(addresses = m.addresses.map { a ->
                if (a.needsPairing != true && secrets.get(SecretStore.token(a.id)) == null) {
                    changed = true
                    a.copy(needsPairing = true)
                } else a
            })
        }
        if (changed) saveMachines(migrated)
        return migrated
    }

    private fun saveMachines(list: List<Machine>) {
        prefs.edit().putString("machines", gson.toJson(list)).apply()
    }

    /** The token of the address in use. */
    private fun token(m: Machine): String? = secrets.get(m.tokenKey)

    /** Paired and not refused: requests to it make sense. */
    private fun usable(m: Machine?): Boolean = m != null && m.needsPairing != true && token(m) != null

    /** A client for [m] that signs requests with its token; never another machine's. */
    fun clientFor(m: Machine?): ApiClient = ApiClient(m?.mobdUrl.orEmpty(), m?.let(::token))

    /** Points the API client, chats, health and live view at the active machine. */
    private fun activate() {
        val m = activeMachine
        prefs.edit().putString("activeMachine", m?.id).apply()
        _apiClient = clientFor(m)
        val ok = usable(m)
        chat.live = ok && m?.id in liveMachines
        _health.value = null
        _isConnected.value = false
        _healthError.value = if (m != null && !ok) NEEDS_PAIRING else null
        chat.attach(_apiClient, m?.id.orEmpty(), enabled = ok, historyKey = m?.serverId)
        files.attach(if (ok) _apiClient else null, m?.id.orEmpty())
        // Enabled once health says the server has conversation desktops.
        desktops.attach(_apiClient, m?.id.orEmpty(), enabled = false)
        healthJob?.cancel()
        healthJob = if (!ok) null else viewModelScope.launch {
            while (isActive) {
                checkHealthNow()
                delay(30_000)
            }
        }
        if (desktopUsers > 0 && ok) connectDesktop() else stream.disconnect()
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
        setWaiting(id) { emptySet() }
        saveMachines(updated)
        val gone = _machines.value.firstOrNull { it.id == id }
        _machines.value = updated
        gone?.addresses?.forEach { secrets.delete(SecretStore.token(it.id)) } ?: secrets.delete(SecretStore.token(id))
        prefs.edit().remove("session.$id").apply()
        chat.forget(gone?.serverId ?: id)
        if (id == _activeMachineId.value) {
            _activeMachineId.value = updated.firstOrNull()?.id
            activate()
        }
        syncWatchers()
    }

    /**
     * Pairs this device with the office in [link]: redeems the invite, stores the token and
     * activates the machine. Pairs a saved address of [existing] (or of the machine already paired
     * with that office) again, or adds the link's address to its row; otherwise adds a machine.
     * Each address has its own token, so a token never goes to an address it was not paired on.
     * Runs in the view model so leaving the dialog cannot lose a redeemed invite. Fails with a
     * readable message.
     */
    fun pair(link: PairLink, deviceName: String, existing: Machine?): Deferred<Unit> = viewModelScope.async {
        val r = redeemInvite(link, deviceName, pairingHttp)
        val base = existing?.let { e -> _machines.value.firstOrNull { it.id == e.id } }
            ?: _machines.value.firstOrNull { it.serverId == r.serverId }
            ?: Machine(newMachineId(), link.origin.toHttpUrl().host, link.origin)
        val (m, paired, dropped) = base.pairing(link.origin, r.serverId, r.clientId, ::newMachineId)
        check(secrets.set(SecretStore.token(paired.id), r.token)) { "Could not store the device token on this phone" }
        dropped.filter { it != SecretStore.token(paired.id) }.forEach(secrets::delete)
        Log.add("${m.name}: paired with ${link.origin}")
        _routeUp.value = _routeUp.value - m.id
        saveMachine(m)
        selectMachine(m.id)
    }

    /** Uses another of the machine's addresses and opens the machine; the app never switches by itself. */
    fun selectRoute(machine: String, address: String) {
        val m = _machines.value.firstOrNull { it.id == machine } ?: return
        if (m.addresses.none { it.id == address }) return
        if (m.address?.id != address) {
            val next = m.copy(route = address)
            Log.add("${m.name}: using ${next.mobdUrl}")
            _routeUp.value = _routeUp.value - machine
            liveMachines -= machine
            saveMachine(next)
        }
        selectMachine(machine)
    }

    /** Removes one address from this phone; the machine goes with its last one. */
    fun removeAddress(machine: String, address: String) {
        val m = _machines.value.firstOrNull { it.id == machine } ?: return
        if (m.addresses.size < 2) return deleteMachine(machine)
        secrets.delete(SecretStore.token(address))
        _routeUp.value = _routeUp.value - machine
        liveMachines -= machine
        saveMachine(m.copy(addresses = m.addresses.filterNot { it.id == address }, route = m.route?.takeIf { it != address }))
    }

    /** Saves the name and address labels from the editor; addresses and pairing stay as stored. */
    fun saveEdits(machine: String, name: String, labels: Map<String, String>) {
        val m = _machines.value.firstOrNull { it.id == machine } ?: return
        saveMachine(m.copy(name = name, addresses = m.addresses.map { a ->
            labels[a.id]?.let { l -> a.copy(label = l.trim().ifEmpty { null }) } ?: a
        }))
    }

    /** No Authorization here: POST /pair proves itself with the invite. */
    private val pairingHttp = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * mobd refused [token] for this machine: keep it in the list but stop every request until the
     * user pairs again. Ignored when the token has changed since (a late answer to an old pairing).
     */
    fun markNeedsPairing(id: String, token: String?) {
        viewModelScope.launch {
            val m = _machines.value.firstOrNull { it.id == id } ?: return@launch
            if (m.needsPairing == true || token(m) != token) return@launch
            Log.error("${m.name}: $NEEDS_PAIRING")
            // Refused: the conversations saved from this machine go too.
            chat.forget(m.serverId ?: m.id)
            saveMachine(m.withNeedsPairing(true))
        }
    }

    /** Starts a watcher for each usable machine, and restarts one whose URL or token changed. */
    private fun syncWatchers() {
        val machines = _machines.value.associateBy { it.id }
        watchers.entries.removeAll { (id, w) ->
            val m = machines[id]
            (!usable(m) || m?.mobdUrl != w.machine.mobdUrl || token(m!!) != w.client.token).also { gone ->
                if (gone) {
                    w.job.cancel()
                    liveMachines -= id
                    _routeUp.value = _routeUp.value - id
                }
            }
        }
        for (m in machines.values) {
            if (m.id !in watchers && usable(m)) watchers[m.id] = MachineWatcher(m, clientFor(m), viewModelScope, this)
        }
        updateBackgroundService()
    }

    fun setBackgroundWatch(on: Boolean) {
        _backgroundWatch.value = on
        prefs.edit().putBoolean("backgroundWatch", on).apply()
        updateBackgroundService()
    }

    /** Runs the background service while it is wanted and there is something to watch. */
    fun updateBackgroundService() {
        if (_backgroundWatch.value && _machines.value.any(::usable)) {
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
            val client = clientFor(m)
            val q = runCatching { client.api.getSession(s.id) }
                .onFailure { if (it.isUnauthorized()) markNeedsPairing(m.id, client.token) }
                .getOrNull()?.questions?.lastOrNull { it.isPending }
            notifier.question(m, s, q, _waitingCount.value)
        }
    }

    /** Sends an answer picked or typed in a notification; tells the user when it fails. */
    suspend fun answerFromNotification(machine: String, session: String, task: String, question: String, answer: String?, approved: Boolean?) {
        val m = _machines.value.firstOrNull { it.id == machine } ?: return
        if (!usable(m)) {
            notifier.answerFailed(m, session, NEEDS_PAIRING)
            return
        }
        val client = clientFor(m)
        runCatching {
            val r = client.api.answerQuestion(task, question, AnswerQuestionRequest(answer?.ifEmpty { null }, approved))
            check(r.isSuccessful) { r.errorBody()?.string()?.ifBlank { null } ?: "HTTP ${r.code()}" }
        }.onSuccess {
            Log.add("answered from notification: ${answer ?: if (approved == true) "approved" else "denied"}")
            notifier.answered(m, session)
            if (m.id == _activeMachineId.value) chat.refreshAll()
        }.onFailure {
            Log.add("notification answer failed: ${it.readable()}")
            notifier.answerFailed(m, session, it.readable())
            if (it.isUnauthorized()) markNeedsPairing(m.id, client.token)
        }
    }

    override fun onDeleted(m: Machine, id: String) {
        setWaiting(m.id) { it - id }
        if (m.id == _activeMachineId.value) chat.removeSession(id)
    }

    override fun onReset(m: Machine) {
        if (m.id != _activeMachineId.value) return
        chat.refreshAll()
        desktops.refresh()
    }

    override fun onDesktopEvent(m: Machine, e: DesktopEvent) {
        if (m.id != _activeMachineId.value) return
        when (e) {
            is DesktopEvent.Desktop -> desktops.applyDesktop(e.info)
            is DesktopEvent.Memory -> desktops.applyMemory(e.info)
            is DesktopEvent.Disk -> desktops.applyDisk(e.info)
        }
    }

    override fun onUnauthorized(m: Machine, token: String?) = markNeedsPairing(m.id, token)

    override fun onQueue(m: Machine, queued: Int) {
        _queued.value = _queued.value + (m.id to queued)
    }

    override fun onLive(m: Machine, live: Boolean) {
        if (live) liveMachines += m.id else liveMachines -= m.id
        _routeUp.value = _routeUp.value + (m.id to live)
        if (m.id != _activeMachineId.value) return
        val was = chat.live
        chat.live = live
        // Reconnected: catch up on whatever happened while the stream was down.
        if (live && !was) {
            chat.refreshAll()
            desktops.refresh()
        }
    }

    /**
     * A new chat's choices on this device until the chat exists ("turn.<machine>.runner",
     * "turn.<machine>/<runner>.model" / ".effort"). In memory only: model and effort are mobd's
     * machine and conversation settings now, so the old saved choices are removed.
     */
    private val _turnPrefs = MutableStateFlow<Map<String, String>>(emptyMap()).also {
        prefs.edit().apply { prefs.all.keys.filter { k -> k.startsWith("turn.") }.forEach(::remove) }.apply()
    }
    val turnPrefs: StateFlow<Map<String, String>> = _turnPrefs

    fun setTurnPref(key: String, value: String?) {
        val k = "turn.$key"
        _turnPrefs.value = if (value.isNullOrEmpty()) _turnPrefs.value - k else _turnPrefs.value + (k to value)
    }

    fun clearTurnPrefs(machine: String, runnerKey: String) {
        _turnPrefs.value = _turnPrefs.value - listOf("turn.$machine.runner", "turn.$runnerKey.model", "turn.$runnerKey.effort")
    }

    /** Goes up when another device changes the machine settings, so open settings reload. */
    private val _settingsTick = MutableStateFlow(0)
    val settingsTick: StateFlow<Int> = _settingsTick

    override fun onSettings(m: Machine) {
        if (m.id != _activeMachineId.value) return
        _settingsTick.value++
        checkHealth()
    }

    fun newMachineId(): String = UUID.randomUUID().toString()

    /** A view that shows the desktop is on screen; the live view stays open while any is. */
    fun retainDesktop() {
        if (desktopUsers++ == 0) connectDesktop()
    }

    fun releaseDesktop() {
        if (--desktopUsers == 0) stream.disconnect()
    }

    fun disconnectDesktop() {
        stream.disconnect()
        liveMachine = null
    }

    /** Connects the live view (mobd's stream); a session already up for this machine is kept. */
    fun connectDesktop() {
        val m = activeMachine?.takeIf(::usable) ?: return
        val h = _health.value ?: return // connects when health arrives
        if (!h.supports("stream")) return stream.unavailable(UPDATE_MOBD)
        // Conversation desktops: the open chat's, none for a new chat (its desktop starts with the first turn).
        val session = if (h.sessionDesktops) chat.currentId.value ?: return noDesktop() else null
        val key = if (session == null) m.id else "${m.id}/$session"
        val active = stream.state.value.status
        // Health checks call this too: they must not restart a working session. After an error, reconnect.
        if (liveMachine == key && (active == LiveStatus.Connecting || active == LiveStatus.Connected)) return
        liveMachine = key
        val screen = appContext.resources.displayMetrics.let { it.widthPixels to it.heightPixels }
        // Remember which transport carried the picture, so the next connect races the WebSocket sooner.
        stream.onTransport = { t -> rememberTransport(m.id, t) }
        stream.connect(_apiClient, LiveQuality.of(m.liveQuality), h.supports("streamResize"), screen,
            preferWebSocket = m.liveWebSocket == true, sessionId = session, audio = h.supports("streamAudio"), ice = h.supports("streamIce")) {
            markNeedsPairing(m.id, _apiClient.token)
        }
    }

    private fun noDesktop() {
        liveMachine = null
        stream.unavailable(NO_DESKTOP)
    }

    fun checkHealth() {
        viewModelScope.launch { checkHealthNow() }
    }

    private suspend fun checkHealthNow() {
        val client = _apiClient
        val m = activeMachine
        if (!usable(m)) {
            _isConnected.value = false
            _health.value = null
            if (m != null) _healthError.value = NEEDS_PAIRING
            return
        }
        try {
            val h = client.api.getHealth()
            if (client !== _apiClient) return
            val first = _health.value == null
            _health.value = h
            if (!_isConnected.value) Log.add("${activeMachine?.name}: connected, runners ${h.runners.orEmpty().joinToString { it.name }}")
            _isConnected.value = h.ok
            _healthError.value = null
            desktops.attach(client, m!!.id, h.sessionDesktops)
            // The live view waits for health to know whether mobd streams.
            if (first && desktopUsers > 0) connectDesktop()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (client !== _apiClient) return
            if (_healthError.value != e.readable()) Log.error("${activeMachine?.name}: health check failed: ${e.readable()}")
            _isConnected.value = false
            _health.value = null
            _healthError.value = e.readable()
            if (e.isUnauthorized()) markNeedsPairing(m!!.id, client.token)
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
        live.value.setControl(false)
        _showLiveView.value = false
    }

    private fun rememberTransport(id: String, transport: String) {
        val ws = if (transport == StreamSession.WEBSOCKET) true else null
        val m = _machines.value.firstOrNull { it.id == id } ?: return
        if (m.liveWebSocket == ws) return
        val updated = _machines.value.map { if (it.id == id) it.copy(liveWebSocket = ws) else it }
        _machines.value = updated
        saveMachines(updated)
    }

    fun setLiveQuality(id: String, quality: LiveQuality) {
        val m = _machines.value.firstOrNull { it.id == id } ?: return
        val updated = _machines.value.map { if (it.id == id) it.copy(liveQuality = quality.wire) else it }
        _machines.value = updated
        saveMachines(updated)
        if (id == _activeMachineId.value && live.value === stream) {
            val control = stream.state.value.control
            stream.reconnecting()
            val h = _health.value
            val session = if (h.sessionDesktops) chat.currentId.value ?: return noDesktop() else null
            val screen = appContext.resources.displayMetrics.let { it.widthPixels to it.heightPixels }
            stream.connect(_apiClient, quality, h?.supports("streamResize") == true, screen, control, m.liveWebSocket == true, session,
                audio = h?.supports("streamAudio") == true, ice = h?.supports("streamIce") == true) {
                markNeedsPairing(id, _apiClient.token)
            }
        }
    }
}
