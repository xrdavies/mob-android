package com.xrdavies.mob.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.xrdavies.mob.data.AnswerQuestionRequest
import com.xrdavies.mob.data.ApiClient
import com.xrdavies.mob.data.Health
import com.xrdavies.mob.data.Machine
import com.xrdavies.mob.data.NEEDS_PAIRING
import com.xrdavies.mob.data.PairLink
import com.xrdavies.mob.data.SecretStore
import com.xrdavies.mob.data.Session
import com.xrdavies.mob.data.Status
import com.xrdavies.mob.data.decodeMachines
import com.xrdavies.mob.data.isUnauthorized
import com.xrdavies.mob.data.pair as redeemInvite
import com.xrdavies.mob.data.readable
import com.xrdavies.mob.notify.Notifier
import com.xrdavies.mob.notify.WatchService
import com.xrdavies.mob.stream.LiveQuality
import com.xrdavies.mob.stream.LiveSession
import com.xrdavies.mob.stream.StreamSession
import com.xrdavies.mob.vnc.VncSession
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
    val vnc = VncSession()
    val stream = StreamSession(appContext)
    private val _live = MutableStateFlow<LiveSession>(vnc)
    val live: StateFlow<LiveSession> = _live
    private var desktopUsers = 0
    private var desktopFor: Boolean? = null

    private var _apiClient: ApiClient = clientFor(activeMachine)
    val apiClient: ApiClient
        get() = _apiClient

    val chat = ChatStore(viewModelScope, prefs, ::markNeedsPairing)
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
        val stored = prefs.getString("machines", null)?.let(::decodeMachines)
        // Before machines existed the app stored one URL and one sign-in.
        val legacyUrl = prefs.getString("mobdUrl", "").orEmpty()
        val list = stored ?: if (legacyUrl.isBlank()) emptyList() else listOf(
            Machine(
                UUID.randomUUID().toString(), "Office Mac", legacyUrl,
                prefs.getString("vncUsername", "").orEmpty(), prefs.getString("vncPassword", "").orEmpty(),
            )
        )
        // Passwords used to be saved in plain text; move them to the secret store. Machines
        // from before pairing have no token and wait for the user to pair them.
        var changed = stored == null && list.isNotEmpty()
        val migrated = list.map { m ->
            var out = m
            val password = m.vncPassword
            if (password != null && (password.isEmpty() || secrets.set(SecretStore.vncPassword(m.id), password))) {
                out = out.copy(vncPassword = null)
                changed = true
            }
            if (out.needsPairing != true && secrets.get(SecretStore.token(m.id)) == null) {
                out = out.copy(needsPairing = true)
                changed = true
            }
            out
        }
        if (changed) saveMachines(migrated)
        if (prefs.contains("vncPassword") && migrated.none { it.vncPassword != null }) prefs.edit().remove("vncPassword").apply()
        return migrated
    }

    private fun saveMachines(list: List<Machine>) {
        // A password that could not be moved to the secret store stays until the next try.
        prefs.edit().putString("machines", gson.toJson(list)).apply()
    }

    private fun token(m: Machine): String? = secrets.get(SecretStore.token(m.id))

    /** Paired and not refused: requests to it make sense. */
    private fun usable(m: Machine?): Boolean = m != null && m.needsPairing != true && token(m) != null

    /** A client for [m] that signs requests with its token; never another machine's. */
    private fun clientFor(m: Machine?): ApiClient = ApiClient(m?.mobdUrl.orEmpty(), m?.let(::token))

    fun vncPassword(m: Machine): String = secrets.get(SecretStore.vncPassword(m.id)) ?: m.vncPassword.orEmpty()

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
        chat.attach(_apiClient, m?.id.orEmpty(), enabled = ok)
        healthJob?.cancel()
        healthJob = if (!ok) null else viewModelScope.launch {
            while (isActive) {
                checkHealthNow()
                delay(30_000)
            }
        }
        if (desktopUsers > 0 && ok) connectDesktop() else { vnc.disconnect(); stream.disconnect() }
    }

    fun selectMachine(id: String) {
        if (id == _activeMachineId.value) return
        _activeMachineId.value = id
        activate()
    }

    /** Adds or updates a machine; returns its ID. A non-null [vncPassword] replaces the stored one. */
    fun saveMachine(m: Machine, vncPassword: String? = null): String {
        if (vncPassword != null) {
            if (vncPassword.isEmpty()) secrets.delete(SecretStore.vncPassword(m.id))
            else secrets.set(SecretStore.vncPassword(m.id), vncPassword)
        }
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
        secrets.delete(SecretStore.token(id))
        secrets.delete(SecretStore.vncPassword(id))
        prefs.edit().remove("session.$id").apply()
        if (id == _activeMachineId.value) {
            _activeMachineId.value = updated.firstOrNull()?.id
            activate()
        }
        syncWatchers()
    }

    /**
     * Pairs this device with the office in [link]: redeems the invite, stores the token and
     * activates the machine. Updates [existing] (Pair again) or the machine already paired with
     * that office; otherwise adds one. Runs in the view model so leaving the dialog cannot lose
     * a redeemed invite. Fails with a readable message.
     */
    fun pair(link: PairLink, deviceName: String, existing: Machine?): Deferred<Unit> = viewModelScope.async {
        val r = redeemInvite(link, deviceName, pairingHttp)
        val base = existing?.let { e -> _machines.value.firstOrNull { it.id == e.id } }
            ?: _machines.value.firstOrNull { it.serverId == r.serverId }
        val m = (base ?: Machine(newMachineId(), link.origin.toHttpUrl().host, link.origin))
            .copy(mobdUrl = link.origin, serverId = r.serverId, clientId = r.clientId, needsPairing = null)
        // The new token and its origin are saved together, so the old token never reaches a new address.
        check(secrets.set(SecretStore.token(m.id), r.token)) { "Could not store the device token on this phone" }
        Log.add("${m.name}: paired with ${link.origin}")
        saveMachine(m)
        selectMachine(m.id)
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
            saveMachine(m.copy(needsPairing = true))
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
        if (m.id == _activeMachineId.value) chat.refreshAll()
    }

    override fun onUnauthorized(m: Machine, token: String?) = markNeedsPairing(m.id, token)

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
        if (--desktopUsers == 0) { vnc.disconnect(); stream.disconnect() }
    }

    fun disconnectDesktop() {
        vnc.disconnect()
        stream.disconnect()
        desktopFor = null
    }

    /** Connects the live view over WebRTC when mobd has `stream`, VNC otherwise; a session already up on that transport is kept. */
    fun connectDesktop() {
        val m = activeMachine?.takeIf(::usable) ?: return
        val h = _health.value
        val useStream = h?.supports("stream") == true
        val active = (if (useStream) stream else vnc).state.value.status
        // Health checks call this too: they must not restart a working session. After an error, reconnect.
        if (desktopFor == useStream && (active == com.xrdavies.mob.vnc.VncStatus.Connecting || active == com.xrdavies.mob.vnc.VncStatus.Connected)) return
        desktopFor = useStream
        vnc.disconnect()
        stream.disconnect()
        if (useStream) {
            _live.value = stream
            val screen = appContext.resources.displayMetrics.let { it.widthPixels to it.heightPixels }
            stream.connect(_apiClient, LiveQuality.of(m.liveQuality), h?.supports("streamResize") == true, screen) {
                markNeedsPairing(m.id, _apiClient.token)
            }
        } else {
            _live.value = vnc
            vnc.connect(_apiClient.okHttpClient, _apiClient.vncUrl, m.vncUsername, vncPassword(m)) {
                markNeedsPairing(m.id, _apiClient.token)
            }
        }
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

    fun setLiveQuality(id: String, quality: LiveQuality) {
        val m = _machines.value.firstOrNull { it.id == id } ?: return
        val updated = _machines.value.map { if (it.id == id) it.copy(liveQuality = quality.wire) else it }
        _machines.value = updated
        saveMachines(updated)
        if (id == _activeMachineId.value && live.value === stream) {
            val control = stream.state.value.control
            stream.disconnect()
            val h = _health.value
            val screen = appContext.resources.displayMetrics.let { it.widthPixels to it.heightPixels }
            stream.connect(_apiClient, quality, h?.supports("streamResize") == true, screen, control) {
                markNeedsPairing(id, _apiClient.token)
            }
        }
    }
}
