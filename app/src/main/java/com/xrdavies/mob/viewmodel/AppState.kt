package com.xrdavies.mob.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.xrdavies.mob.data.ApiClient
import com.xrdavies.mob.data.Health
import com.xrdavies.mob.data.Machine
import com.xrdavies.mob.data.readable
import com.xrdavies.mob.vnc.VncSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

class AppState(context: Context) : ViewModel() {
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

    init {
        activate()
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
        return m.id
    }

    fun deleteMachine(id: String) {
        val updated = _machines.value.filterNot { it.id == id }
        _machines.value = updated
        saveMachines(updated)
        prefs.edit().remove("session.$id").apply()
        if (id == _activeMachineId.value) {
            _activeMachineId.value = updated.firstOrNull()?.id
            activate()
        }
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
            _isConnected.value = h.ok
            _healthError.value = null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (client !== _apiClient) return
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
