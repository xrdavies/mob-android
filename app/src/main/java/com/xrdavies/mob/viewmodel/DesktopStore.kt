package com.xrdavies.mob.viewmodel

import com.xrdavies.mob.data.ApiClient
import com.xrdavies.mob.data.ApiError
import com.xrdavies.mob.data.DesktopInfo
import com.xrdavies.mob.data.DiskInfo
import com.xrdavies.mob.data.MemoryInfo
import com.xrdavies.mob.data.SEEDED
import com.xrdavies.mob.data.apiError
import com.xrdavies.mob.data.apply
import com.xrdavies.mob.data.isUnauthorized
import com.xrdavies.mob.data.readable
import com.xrdavies.mob.data.seedErrorText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import retrofit2.Response

/**
 * The active machine's conversation desktops (Linux mobd with `sessionDesktops`): GET /desktops on
 * connect, kept current by the global stream's `desktop`, `memory` and `disk` events. Disabled (and
 * empty) on servers with one shared desktop. [onUnauthorized] gets the machine and token mobd refused.
 */
class DesktopStore(
    private val scope: CoroutineScope,
    private val onUnauthorized: (machine: String, token: String?) -> Unit,
) {
    private var api: ApiClient? = null
    private var machineId = ""
    private var generation = 0

    /** Desktops by conversation ID. */
    private val _desktops = MutableStateFlow<Map<String, DesktopInfo>>(emptyMap())
    val desktops: StateFlow<Map<String, DesktopInfo>> = _desktops

    private val _memory = MutableStateFlow<MemoryInfo?>(null)
    val memory: StateFlow<MemoryInfo?> = _memory

    private val _disk = MutableStateFlow<DiskInfo?>(null)
    val disk: StateFlow<DiskInfo?> = _disk

    /** The low-resource banner key the user dismissed; it shows again when a level changes. */
    private val _dismissed = MutableStateFlow<String?>(null)
    val dismissed: StateFlow<String?> = _dismissed

    /** Result of the last action (close, seed), shown once by the UI and then cleared. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    /** Called on the main thread when a desktop event arrives (AppState reconnects the live view). */
    var onChange: ((DesktopInfo) -> Unit)? = null

    val enabled get() = api != null

    /** Follows [client]'s desktops when [enabled]; otherwise clears everything. */
    fun attach(client: ApiClient, machine: String, enabled: Boolean) {
        if (enabled && api === client) return
        generation++
        api = client.takeIf { enabled }
        machineId = machine
        _desktops.value = emptyMap()
        _memory.value = null
        _disk.value = null
        _dismissed.value = null
        refresh()
    }

    fun clearMessage() {
        _message.value = null
    }

    fun dismiss(key: String) {
        _dismissed.value = key
    }

    /** GET /desktops: on connect, after a reconnect or `reset`, and while the banner shows. */
    fun refresh() = launch(quiet = true) { client ->
        val r = client.api.listDesktops()
        if (client !== api) return@launch
        _desktops.value = r.desktops.orEmpty().associateBy { it.sessionId }
        r.memory?.let { _memory.value = it }
        r.disk?.let { _disk.value = it }
    }

    fun applyDesktop(d: DesktopInfo) {
        if (api == null) return
        _desktops.value = _desktops.value.apply(d)
        onChange?.invoke(d)
    }

    fun applyMemory(m: MemoryInfo) {
        if (api != null) _memory.value = m
    }

    fun applyDisk(d: DiskInfo) {
        if (api != null) _disk.value = d
    }

    /** DELETE /desktops/{id}: stops the running turn, then closes the desktop. */
    fun close(sessionId: String) = launch { client ->
        client.api.closeDesktop(sessionId).failure()?.let { _message.value = it.message }
        refresh()
    }

    /** POST /desktops/{id}/seed: new conversations start with this one's browser logins. */
    fun seed(sessionId: String) = launch { client ->
        _message.value = client.api.seedDesktop(sessionId).failure()?.let(::seedErrorText) ?: SEEDED
    }

    private fun <T> Response<T>.failure(): ApiError? =
        if (isSuccessful) null else apiError(errorBody()?.string(), code())

    /** Runs [block] against the current machine; drops failures if the machine changed meanwhile. [quiet]: log only. */
    private fun launch(quiet: Boolean = false, block: suspend (ApiClient) -> Unit) {
        val client = api ?: return
        val gen = generation
        scope.launch {
            try {
                if (gen == generation) block(client)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen != generation) return@launch
                Log.error("desktops: ${e.readable()}")
                if (e.isUnauthorized()) onUnauthorized(machineId, client.token)
                else if (!quiet) _message.value = e.readable()
            }
        }
    }
}
