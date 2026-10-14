package com.xrdavies.mob.viewmodel

import com.xrdavies.mob.data.ApiClient
import com.xrdavies.mob.data.Machine
import com.xrdavies.mob.data.OPEN
import com.xrdavies.mob.data.Queue
import com.xrdavies.mob.data.Session
import com.xrdavies.mob.data.readable
import com.xrdavies.mob.data.sse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What a machine's global stream reports; called on the scope's (main) thread. */
interface MachineEvents {
    /** [before] is the last known summary, null for a chat that appeared since. */
    fun onSession(m: Machine, s: Session, before: Session?)
    fun onDeleted(m: Machine, id: String)
    fun onReset(m: Machine)
    fun onLive(m: Machine, live: Boolean)
    /** Every chat on the machine, after (re)connecting. */
    fun onSeed(m: Machine, sessions: List<Session>)
    /** Turns waiting for the desktop on this machine (all chats). */
    fun onQueue(m: Machine, queued: Int)
}

/**
 * Follows one machine's GET /events (session summaries) and remembers the last summary of
 * every chat, so callers see transitions: started waiting, finished, failed.
 */
class MachineWatcher(val machine: Machine, scope: CoroutineScope, private val events: MachineEvents) {
    private val known = mutableMapOf<String, Session>()

    val job: Job = scope.launch {
        val client = ApiClient(machine.mobdUrl)
        var lastId: String? = null
        var failures = 0
        while (isActive) {
            try {
                // Seed without notifying, so only changes from now on count.
                if (lastId == null) seed(client)
                client.streamClient.sse(client.url("events"), lastId).collect { e ->
                    e.id?.let { lastId = it }
                    when (e.type) {
                        OPEN -> {
                            Log.add("${machine.name}: events connected")
                            failures = 0
                            events.onLive(machine, true)
                        }
                        "session" -> runCatching { ApiClient.gson.fromJson(e.data, Session::class.java) }.getOrNull()?.let { s ->
                            val before = known.put(s.id, s)
                            events.onSession(machine, s, before)
                        }
                        "session.deleted" -> runCatching { ApiClient.gson.fromJson(e.data, Session::class.java) }.getOrNull()?.let { s ->
                            known.remove(s.id)
                            events.onDeleted(machine, s.id)
                        }
                        "queue" -> runCatching { ApiClient.gson.fromJson(e.data, Queue::class.java) }.getOrNull()?.let {
                            events.onQueue(machine, it.queued.orEmpty().size)
                        }
                        "reset" -> {
                            Log.add("${machine.name}: events reset, refetching")
                            seed(client)
                            events.onReset(machine)
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Offline or mobd restarted; retry with backoff below.
                Log.error("${machine.name}: events failed: ${e.readable()}")
            }
            failures++
            events.onLive(machine, false)
            delay(minOf(60_000L, 2_000L shl minOf(failures, 5)))
        }
    }

    private suspend fun seed(client: ApiClient) {
        val list = client.api.listSessions("all").sessions.orEmpty()
        known.clear()
        list.forEach { known[it.id] = it }
        events.onSeed(machine, list)
        runCatching { client.api.queue() }.getOrNull()?.let { events.onQueue(machine, it.queued.orEmpty().size) }
    }
}
