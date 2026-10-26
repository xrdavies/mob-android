package com.xrdavies.mob.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

// Mirrors the mobd v3 API (mob/docs/mobd-v3.md). Gson ignores Kotlin defaults and
// nullability, so every field mobd may omit is nullable and read through a helper.

data class Check(val ok: Boolean, val unknown: Boolean?, val detail: String?)

data class Health(
    val ok: Boolean,
    val features: List<String>?,
    val runners: List<Runner>?,
    val runner: String?,
    val checks: Map<String, Check>?,
) {
    fun supports(feature: String) = features.orEmpty().contains(feature)

    /** Readiness checks that failed (permissions, Screen Sharing, agent CLIs), as label to detail. */
    val problems: List<Pair<String, String>>
        get() = checks.orEmpty().filter { (_, c) -> !c.ok && c.unknown != true }.map { (k, c) ->
            (CHECK_LABELS[k] ?: k) to c.detail.orEmpty()
        }
    fun runner(name: String?): Runner? = runners.orEmpty().let { list -> list.firstOrNull { it.name == (name ?: runner) } ?: list.firstOrNull { it.default == true } }
}

private val CHECK_LABELS = mapOf(
    "accessibility" to "Accessibility",
    "screenRecording" to "Screen Recording",
    "actuation" to "Desktop control",
    "codex" to "Codex",
    "claude" to "Claude Code",
    "pi" to "Pi",
)

data class Runner(
    val name: String,
    val label: String?,
    val models: List<String>?,
    val efforts: List<String>?,
    val images: Boolean?,
    val resume: Boolean?,
    val cost: Boolean?,
    val steer: Boolean?,
    val available: Boolean?,
    val detail: String?,
    val default: Boolean?,
    /** The default settings.json names, else the agent's own; null when neither is set. */
    val defaultModel: String? = null,
    val defaultEffort: String? = null,
    val permissions: String? = null,
) {
    val title get() = label ?: name
}

/** The menu entry for "no choice": names the agent's default when mobd reports it. */
fun defaultLabel(what: String, value: String?): String = if (value.isNullOrEmpty()) "Default $what" else "Default ($value)"

object Status {
    const val QUEUED = "queued"
    const val RUNNING = "running"
    const val SUCCEEDED = "succeeded"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"

    fun terminal(s: String?) = s == SUCCEEDED || s == FAILED || s == CANCELLED
    fun busy(s: String?) = s == QUEUED || s == RUNNING
}

data class Task(
    val id: String,
    val sessionId: String,
    val runner: String?,
    val prompt: String?,
    val model: String?,
    val effort: String?,
    val status: String,
    val eventId: Long?,
    val attachments: List<String>?,
    val createdAt: String?,
    val updatedAt: String?,
    val startedAt: String?,
    val finishedAt: String?,
    val usage: Usage?,
    val error: String?,
    val queuePosition: Int?,
    /** Why a queued turn still waits (slots, desktops, memory, disk, desktopStarting); see [queueReasonText]. */
    val queueReason: String?,
    /** True while the turn waits for the user to answer a question. */
    val waiting: Boolean?,
) {
    val isTerminal get() = Status.terminal(status)
}

data class Usage(
    val inputTokens: Long?,
    val cachedInputTokens: Long?,
    val outputTokens: Long?,
    val reasoningOutputTokens: Long?,
    val costUsd: Double?,
)

data class Session(
    val id: String,
    val title: String?,
    val runner: String?,
    val resumeId: String?,
    val activeTaskId: String?,
    val status: String?,
    val pinned: Boolean?,
    val waiting: Boolean?,
    val archived: Boolean?,
    val createdAt: String?,
    val updatedAt: String?,
    // Only GET /sessions/{id} includes messages; lists and events omit them.
    val messages: List<Message>?,
    /** The conversation's model and effort (mobd with "settings"); null = machine default. */
    val model: String? = null,
    val effort: String? = null,
) {
    val displayTitle get() = title?.takeIf { it.isNotBlank() } ?: "New chat"
}

data class Message(
    val id: String,
    val taskId: String,
    val role: String,
    val content: String,
    val createdAt: String?,
)

data class Question(
    val id: String,
    val taskId: String,
    val kind: String,
    val text: String,
    val details: String?,
    val options: List<String>?,
    val allowText: Boolean?,
    val status: String,
    val answer: String?,
    val approved: Boolean?,
    val createdAt: String?,
) {
    val isApproval get() = kind == "approval"
    val isPending get() = status == "pending"
}

data class PlanItem(val text: String, val completed: Boolean?)
data class FileChange(val path: String, val kind: String?)

data class Activity(
    val id: String,
    val taskId: String,
    val kind: String,
    val status: String,
    val title: String,
    val server: String?,
    val tool: String?,
    val arguments: Map<String, Any?>?,
    val command: String?,
    val exitCode: Int?,
    val text: String?,
    val truncated: Boolean?,
    val error: String?,
    val images: List<String>?,
    val items: List<PlanItem>?,
    val changes: List<FileChange>?,
    val startedAt: String?,
    val updatedAt: String?,
)

data class SessionsResponse(val sessions: List<Session>?)
data class TasksResponse(val tasks: List<Task>?)
data class Queue(val running: List<Task>?, val queued: List<Task>?)

data class SessionDetail(
    val session: Session,
    val tasks: List<Task>?,
    val activities: List<Activity>?,
    val questions: List<Question>?,
    /** The conversation's revision; null from mobd versions without incremental fetch. */
    val rev: Long? = null,
    /** False when this answers `since` with only the turns changed after it. */
    val full: Boolean? = null,
)

data class Upload(val id: String, val name: String?, val mime: String?, val size: Long?, val url: String?)

data class CreateTaskRequest(
    val prompt: String,
    val sessionId: String? = null,
    val runner: String? = null,
    val model: String? = null,
    val effort: String? = null,
    val attachments: List<String>? = null,
)

/** model / effort set the conversation's settings; "" returns to the machine default. */
data class PatchSessionRequest(
    val title: String? = null, val pinned: Boolean? = null, val archived: Boolean? = null,
    val model: String? = null, val effort: String? = null,
)

data class AnswerQuestionRequest(val answer: String? = null, val approved: Boolean? = null)

data class SteerRequest(val prompt: String)

/**
 * One way to reach a machine's mobd (its LAN name, a Tailscale or Cloudflare address), paired on its
 * own: each address has its own device token ([SecretStore.token] of its [id]), so a token never goes
 * to an address it was not paired on.
 */
data class MachineAddress(
    val id: String,
    val url: String,
    val clientId: String? = null,
    /** mobd refused this address's token (or there is none): pair this address again. */
    val needsPairing: Boolean? = null,
    /** A name the user gave it; null shows the kind ("LAN" or "Remote"). */
    val label: String? = null,
) {
    val kind: AddressKind get() = AddressKind.of(url)
}

/** LAN for .local names and private or link-local addresses; Remote for anything else (Tailscale, Cloudflare, a VPN or public name). */
enum class AddressKind(val title: String) {
    LAN("LAN"), REMOTE("Remote");

    companion object {
        fun of(url: String): AddressKind {
            val host = url.toHttpUrlOrNull()?.host?.lowercase()?.trim('[', ']') ?: return REMOTE
            if (host.endsWith(".local")) return LAN
            val parts = host.split('.').mapNotNull { it.toIntOrNull() }
            if (parts.size == 4 && host.split('.').size == 4) {
                val (a, b) = parts
                return if (a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254)) LAN else REMOTE
            }
            if (':' in host && (host.startsWith("fe80") || host.startsWith("fc") || host.startsWith("fd"))) return LAN
            return REMOTE
        }
    }
}

/**
 * An office Mac running mobd: one row, reached over one or more addresses. Stored on the phone only;
 * the device tokens live in [SecretStore]. Gson skips Kotlin defaults, so fields added later must be nullable.
 */
data class Machine(
    val id: String,
    val name: String,
    val addresses: List<MachineAddress>,
    /** The address in use, chosen by the user; the first when unset or gone. */
    val route: String? = null,
    /** From pairing; identifies the office, it does not prove it. */
    val serverId: String? = null,
    /** Live desktop quality: auto (default), high or low; null uses mobd's auto profile. */
    val liveQuality: String? = null,
    /** The live view last needed mobd's WebSocket here (no UDP path): try it sooner next time. */
    val liveWebSocket: Boolean? = null,
) {
    /** The first address keeps the machine's ID, so its token key is the one from before machines had several addresses. */
    constructor(id: String, name: String, mobdUrl: String) : this(id, name, listOf(MachineAddress(id, mobdUrl)))

    /** The address in use. */
    val address: MachineAddress? get() = addresses.firstOrNull { it.id == route } ?: addresses.firstOrNull()
    val mobdUrl: String get() = address?.url.orEmpty()
    val clientId: String? get() = address?.clientId
    /** The address in use needs pairing (the others may still work). */
    val needsPairing: Boolean? get() = address?.needsPairing ?: if (address == null) true else null
    val tokenKey: String get() = SecretStore.token(address?.id ?: id)

    /** Marks the address in use as refused (or not). */
    fun withNeedsPairing(on: Boolean?): Machine {
        val a = address ?: return this
        return copy(addresses = addresses.map { if (it.id == a.id) it.copy(needsPairing = on) else it })
    }

    /** "LAN", "Remote", or the user's label; a second address of the same kind is numbered. */
    fun label(a: MachineAddress): String {
        a.label?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val same = addresses.filter { it.kind == a.kind && it.label.isNullOrBlank() }
        val n = same.indexOfFirst { it.id == a.id }
        return if (same.size > 1 && n > 0) "${a.kind.title} ${n + 1}" else a.kind.title
    }

    /**
     * The machine after pairing [origin]: that address paired again when it is saved, else added next
     * to the others; a machine from before pairing, or of another office, keeps only the new one. The
     * paired address is the one in use. Returns the machine, the paired address and the token keys of
     * the addresses that went.
     */
    fun pairing(origin: String, serverId: String, clientId: String, newId: () -> String): Triple<Machine, MachineAddress, List<String>> {
        var list = addresses
        var dropped = emptyList<String>()
        if (this.serverId != serverId) {
            dropped = list.map { SecretStore.token(it.id) }
            list = emptyList()
        }
        val i = list.indexOfFirst { sameOrigin(it.url, origin) }
        val base = if (i >= 0) list[i] else MachineAddress(if (list.isEmpty()) id else newId(), origin)
        val paired = base.copy(url = origin, clientId = clientId, needsPairing = null)
        list = if (i >= 0) list.toMutableList().also { it[i] = paired } else list + paired
        return Triple(copy(addresses = list, route = paired.id, serverId = serverId), paired, dropped)
    }

    companion object {
        /** The address's host, for subtitles. */
        fun host(url: String): String = url.toHttpUrlOrNull()?.host ?: url
    }
}

/** Same scheme, host and port. */
fun sameOrigin(a: String, b: String): Boolean {
    val x = ApiClient.normalize(a)
    val y = ApiClient.normalize(b)
    return x.scheme == y.scheme && x.host == y.host && x.port == y.port
}

/** The saved shape, including the fields from before machines had several addresses. */
private data class StoredMachine(
    val id: String?, val name: String?, val addresses: List<MachineAddress>?, val route: String?,
    val mobdUrl: String?, val serverId: String?, val clientId: String?, val needsPairing: Boolean?,
    val liveQuality: String?, val liveWebSocket: Boolean?,
)

/** The saved machine list, or null when it cannot be read. Older saves get one address from mobdUrl. */
fun decodeMachines(json: String): List<Machine>? = runCatching {
    ApiClient.gson.fromJson<List<StoredMachine>>(json, object : com.google.gson.reflect.TypeToken<List<StoredMachine>>() {}.type)
        .map { s ->
            val id = s.id!!
            val addresses = s.addresses?.takeIf { it.isNotEmpty() }
                ?: listOf(MachineAddress(id, s.mobdUrl.orEmpty(), s.clientId, s.needsPairing))
            Machine(id, s.name.orEmpty(), addresses, s.route, s.serverId, s.liveQuality, s.liveWebSocket)
        }
}.getOrNull()
