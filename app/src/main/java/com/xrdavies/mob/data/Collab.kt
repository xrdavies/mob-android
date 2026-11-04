package com.xrdavies.mob.data

// Agent collaboration (mobd docs/agent-collaboration-design.md): mirrors mobd's GET /collab.
// Gson ignores Kotlin defaults and nullability, so fields mobd may omit are nullable.

data class CollabState(val requests: List<CollabRequest>?, val waiting: Int?)

data class CollabRequest(
    val id: String,
    val exchangeId: String?,
    val from: String,
    val to: String,
    val fromName: String?,
    val toName: String?,
    val text: String?,
    val status: String,
    val result: String?,
    val thread: List<CollabNote>?,
    val createdAt: String?,
    val updatedAt: String?,
    val expiresAt: String?,
) {
    val ended get() = status !in OPEN_STATUSES
    /** Ended without an outcome: the user decides what happens next. */
    val needsUser get() = status in NEEDS_USER

    companion object {
        val OPEN_STATUSES = setOf("open", "in_progress", "needs_clarification")
        val NEEDS_USER = setOf("interrupted", "over_budget", "expired")
    }
}

data class CollabNote(val from: String?, val kind: String?, val text: String?, val at: String?)

fun requestStatusText(status: String): String = when (status) {
    "open" -> "Waiting"
    "in_progress" -> "Being handled"
    "needs_clarification" -> "Asked a question"
    "answered" -> "Answered"
    "failed" -> "Failed"
    "cancelled" -> "Cancelled"
    "expired" -> "Expired · needs you"
    "interrupted" -> "Interrupted · needs you"
    "over_budget" -> "Out of turns · needs you"
    else -> status
}

private val HANDLE = Regex("^[a-z][a-z0-9-]{1,31}$")
private val HEX = Regex("^[0-9a-f]+$")
private val RESERVED = setOf("all", "everyone", "here", "user", "me", "mob", "mobd", "system")

/** mobd's handle rules (internal/task/handle.go). */
fun validHandle(h: String): Boolean = HANDLE.matches(h) && !HEX.matches(h) && h !in RESERVED

/** A handle suggested from a conversation's title, or null when none fits the rules. */
fun suggestHandle(title: String?): String? {
    val words = title.orEmpty().lowercase().replace(Regex("[^a-z0-9]+"), " ").trim().split(' ').filter { it.isNotEmpty() }
    var h = ""
    for (w in words) {
        val next = if (h.isEmpty()) w else "$h-$w"
        if (next.length > 32) break
        h = next
    }
    h = h.trimStart { !it.isLetter() }.trim('-')
    return if (h.length >= 2 && HANDLE.matches(h) && !HEX.matches(h) && h !in RESERVED) h else null
}

/** How an agent is named: its handle, else its title with a short ID. */
fun agentLabel(s: Session): String = s.handle?.let { "@$it" } ?: "${s.displayTitle} (${s.id.take(6)})"

/** Why a typed mention cannot be sent; null when every one resolves. */
sealed interface MentionProblem {
    val handle: String
    data class Unknown(override val handle: String) : MentionProblem
    data class Archived(override val handle: String, val session: Session) : MentionProblem
}

/**
 * The @handles typed in [text], resolved against the machine's conversations ([all], archived
 * included). Picked mentions ([picked], session IDs) count too. The conversation itself is skipped.
 */
fun resolveMentions(text: String, all: List<Session>, current: String?, picked: Set<String>): Pair<List<String>, List<MentionProblem>> {
    val ids = linkedSetOf<String>()
    val problems = mutableListOf<MentionProblem>()
    picked.forEach { id -> if (id != current && all.any { it.id == id && it.archived != true }) ids += id }
    // Handles start with a letter; a short ID (hex) may start with a digit.
    for (m in Regex("(?<![\\w@])@([a-z0-9][a-z0-9-]{1,31})").findAll(text)) {
        val h = m.groupValues[1]
        val s = all.firstOrNull { it.handle == h }
        when {
            s != null && s.id == current -> {}
            s != null && s.archived == true -> problems += MentionProblem.Archived(h, s)
            s != null -> ids += s.id
            else -> {
                // Not a handle: a session ID or a short ID (4+ characters) of exactly one conversation.
                val byId = all.filter { it.id.startsWith(h) && it.id != current }
                when {
                    byId.size == 1 && byId[0].archived == true -> problems += MentionProblem.Archived(h, byId[0])
                    byId.size == 1 -> ids += byId[0].id
                    picked.any { id -> id.startsWith(h) } -> {}
                    else -> problems += MentionProblem.Unknown(h)
                }
            }
        }
    }
    return ids.toList() to problems.distinctBy { it.handle }
}

/** The sender in a delivered turn's header: "[Mob collaboration · from @api · request …]". */
fun peerSender(prompt: String?): String? =
    Regex("\\[Mob collaboration · from ([^·\\]]+?) ·").find(prompt.orEmpty())?.groupValues?.get(1)?.trim()

/** [text] with the @-query at its end replaced by the picked agent's [token] (handle or short ID). */
fun insertMention(text: String, token: String): String = text.replace(Regex("@[a-z0-9-]*$"), "@$token ")

/** The @-query being typed at the end of [text] ("@ap" → "ap"), or null. */
fun mentionQuery(text: String): String? = Regex("(?:^|\\s)@([a-z0-9-]{0,32})$").find(text)?.groupValues?.get(1)
