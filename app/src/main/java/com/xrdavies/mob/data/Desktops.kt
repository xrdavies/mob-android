package com.xrdavies.mob.data

import com.google.gson.JsonParser

// Conversation desktops on Linux mobd (mobd docs/mobd-v3.md, "Conversation desktops (Linux)"):
// every chat gets its own desktop. Only servers with the `sessionDesktops` feature have them;
// without it (macOS) nothing here is used.

const val SESSION_DESKTOPS = "sessionDesktops"

const val NO_DESKTOP = "No desktop yet — it starts with the conversation's first turn"
const val DESKTOP_ASLEEP = "Asleep"
const val CLOSE_DESKTOP_WARNING = "The running turn will be stopped and open windows closed. Files and browser logins are kept."
const val DESKTOP_CLOSES = "Its desktop closes; open windows are lost."
const val SEEDED = "New conversations start with this conversation's browser logins."

/** The server gives every conversation its own desktop. */
val Health?.sessionDesktops: Boolean get() = this?.supports(SESSION_DESKTOPS) == true

data class DesktopInfo(
    val sessionId: String,
    val title: String?,
    val display: String?,
    val width: Int?,
    val height: Int?,
    val scale: Double?,
    val state: String?,
    /** Why it closed or failed (archived, deleted, user, crashed…); "oomKill" when the kernel ended one of its apps. */
    val reason: String?,
    val memoryBytes: Long?,
    val diskBytes: Long?,
    val startedAt: String?,
    val lastUsedAt: String?,
    val activeTaskId: String?,
    val viewers: Int?,
) {
    val asleep get() = state == DesktopState.ASLEEP
    /** Running or asleep: the chat list marks it, its menu offers Close desktop. */
    val open get() = state != DesktopState.CLOSED && state != DesktopState.FAILED
}

object DesktopState {
    const val STARTING = "starting"
    const val RUNNING = "running"
    const val ASLEEP = "asleep"
    const val WAKING = "waking"
    const val CLOSING = "closing"
    const val CLOSED = "closed"
    const val FAILED = "failed"
}

object Level {
    const val OK = "ok"
    const val LOW = "low"
    const val CRITICAL = "critical"
}

data class MemoryInfo(val level: String?, val availableBytes: Long?, val totalBytes: Long?, val pressure: Double?, val warnPercent: Double?, val floorPercent: Double?)
data class DiskInfo(val level: String?, val freeBytes: Long?, val totalBytes: Long?, val path: String?)

/** GET /desktops; a shared desktop (macOS) answers `{desktops: []}`. */
data class DesktopsResponse(val desktops: List<DesktopInfo>?, val memory: MemoryInfo?, val disk: DiskInfo?)

/** Desktops by conversation after a `desktop` event: a closed one goes, any other replaces the last. */
fun Map<String, DesktopInfo>.apply(event: DesktopInfo): Map<String, DesktopInfo> =
    if (event.state == DesktopState.CLOSED) this - event.sessionId else this + (event.sessionId to event)

/** Largest memory first, as the low-memory banner lists them. */
fun Map<String, DesktopInfo>.byMemory(): List<DesktopInfo> = values.sortedByDescending { it.memoryBytes ?: 0 }

/** Why a queued turn still waits, or null for an unknown reason (the position still shows). */
fun queueReasonText(reason: String?): String? = when (reason) {
    "slots" -> "Waiting for a free slot"
    "desktops" -> "Waiting: the desktop limit is reached"
    "memory" -> "Waiting: the office computer is low on memory"
    "disk" -> "Waiting: the office computer is low on disk space"
    "desktopStarting" -> "Starting the desktop"
    else -> null
}

/** The low memory / low disk banner: what it says, and [key] so a dismissal lasts until a level changes. */
data class ResourceWarning(val key: String, val lines: List<String>)

fun resourceWarning(memory: MemoryInfo?, disk: DiskInfo?): ResourceWarning? {
    val mem = memory?.level?.takeIf { it != Level.OK }
    val dsk = disk?.level?.takeIf { it != Level.OK }
    if (mem == null && dsk == null) return null
    val lines = mutableListOf<String>()
    if (mem != null) lines += "The office computer is low on memory. Close desktops or delete conversations you no longer need." +
        if (mem == Level.CRITICAL) " New conversations wait until memory is freed." else ""
    if (dsk != null) lines += "The office computer is low on disk space. Close desktops or delete conversations you no longer need." +
        if (dsk == Level.CRITICAL) " New conversations wait until disk space is freed." else ""
    return ResourceWarning("${mem ?: Level.OK}/${dsk ?: Level.OK}", lines)
}

/** "1.2 GB", "340 MB". */
fun bytes(n: Long?): String {
    val b = n ?: 0
    return when {
        b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> "${b shr 20} MB"
        else -> "${b shr 10} KB"
    }
}

/** mobd's `{"error": "...", "code": "..."}`; [message] falls back to the status. */
data class ApiError(val code: String?, val message: String)

fun apiError(body: String?, status: Int): ApiError {
    val o = runCatching { JsonParser().parse(body.orEmpty()).asJsonObject }.getOrNull()
    fun str(k: String) = runCatching { o?.get(k)?.takeIf { it.isJsonPrimitive }?.asString }.getOrNull()?.takeIf { it.isNotBlank() }
    val code = str("code")
    val message = str("error") ?: "HTTP $status"
    return ApiError(code, message)
}

/** POST /desktops/{id}/seed refused. */
fun seedErrorText(e: ApiError): String = when (e.code) {
    "browser_running" -> "Close the browser in this conversation first"
    else -> e.message
}

/** GET /screen with `sessionDesktops`: the conversation has no desktop yet. */
class NoDesktopException : java.io.IOException(NO_DESKTOP)

/** A `desktop`, `memory` or `disk` event on GET /events. */
sealed class DesktopEvent {
    data class Desktop(val info: DesktopInfo) : DesktopEvent()
    data class Memory(val info: MemoryInfo) : DesktopEvent()
    data class Disk(val info: DiskInfo) : DesktopEvent()
}

/** Null for other event types and malformed data. */
fun parseDesktopEvent(type: String, data: String): DesktopEvent? = runCatching {
    when (type) {
        "desktop" -> ApiClient.gson.fromJson(data, DesktopInfo::class.java)?.takeIf { it.sessionId != null }?.let { DesktopEvent.Desktop(it) }
        "memory" -> ApiClient.gson.fromJson(data, MemoryInfo::class.java)?.let { DesktopEvent.Memory(it) }
        "disk" -> ApiClient.gson.fromJson(data, DiskInfo::class.java)?.let { DesktopEvent.Disk(it) }
        else -> null
    }
}.getOrNull()
