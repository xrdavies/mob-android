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

/** Why [h] cannot be a handle, in words for the user; "" when it can. */
fun handleProblem(h: String): String = when {
    h.length < 2 -> "At least 2 characters."
    h.length > 32 -> "At most 32 characters."
    !h[0].let { it in 'a'..'z' } -> "Start with a letter."
    !Regex("^[a-z0-9-]+$").matches(h) -> "Use lowercase letters, digits and -."
    HEX.matches(h) -> "Only 0–9 and a–f reads as a conversation ID; add another letter."
    h in RESERVED -> "@$h is reserved."
    else -> ""
}

/** What the handle field keeps of typed text: lowercase, no leading @, spaces and _ as -. */
fun normalizeHandleInput(s: String): String =
    s.lowercase().replace(Regex("^@+"), "").replace(Regex("[\\s_]+"), "-").replace(Regex("[^a-z0-9-]"), "").take(32)

/** An agent's name where space is short: "@handle", else its title cut to [max] characters with a short ID. */
fun agentShortLabel(s: Session, max: Int = 32): String {
    s.handle?.let { return "@$it" }
    val t = s.displayTitle
    val cut = if (t.codePointCount(0, t.length) > max) t.take(max - 1).trimEnd() + "…" else t
    return "$cut (${s.id.take(6)})"
}

/** A stable hue (0–359) for an agent, so it keeps its colour everywhere (same as Desktop). */
fun agentHue(id: String): Int = id.fold(0) { h, c -> (h * 31 + c.code) % 360 }

/** The avatar letter for a label: its first letter or digit. */
fun agentInitial(label: String): String = label.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"

/** A run of text: plain, or an @mention of another known conversation. */
data class MentionSegment(val text: String, val session: Session? = null)

/** [text] split into plain runs and @mentions of other conversations (handle or unique short ID). */
fun mentionSegments(text: String, sessions: List<Session>, self: String?): List<MentionSegment> {
    val out = mutableListOf<MentionSegment>()
    var last = 0
    for (m in Regex("(^|\\s)@([a-zA-Z0-9-]{2,32})(?=$|[\\s.,;:!?)])").findAll(text)) {
        val word = m.groupValues[2].lowercase()
        var hit = sessions.firstOrNull { it.handle == word }
        if (hit == null && HEX.matches(word) && word.length >= 6) {
            hit = sessions.filter { it.id.startsWith(word) }.singleOrNull()
        }
        if (hit == null || hit.id == self) continue
        val start = m.range.first + m.groupValues[1].length
        if (start > last) out += MentionSegment(text.substring(last, start))
        out += MentionSegment("@" + m.groupValues[2], hit)
        last = start + m.groupValues[2].length + 1
    }
    if (last < text.length) out += MentionSegment(text.substring(last))
    return out
}

/** Who a delivery is from, by session ID: the requester for requests and answers, else the recipient. */
fun deliverySender(kind: String?, r: CollabRequest): String? = when (kind) {
    "notice" -> null
    "request", "answer" -> r.from
    else -> r.to
}

/** What a delivery says about itself, after the sender's name. */
fun deliveryLabel(kind: String?, status: String? = null): String = when (kind) {
    "request" -> "asks"
    "clarification" -> "needs clarification"
    "answer" -> "answered your question"
    "result" -> if (status == "failed") "couldn't complete your request" else "replied"
    else -> ""
}

/** The part of a delivered message its sender wrote, without mobd's instructions to the recipient. */
fun deliveryBody(body: String): String = body
    .replace(Regex("^Request: "), "")
    .replace(Regex("\\n\\nWhen done, call complete_request\\([\\s\\S]*$"), "")
    .replace(Regex("\\n\\nAnswer with answer_clarification\\([\\s\\S]*$"), "")
    .replace(Regex("^Result of your request \"(?:[^\"\\\\]|\\\\.)*\" to .+? \\((\\w+)\\):\\n"), "")
    .trim()

/** A delivered turn's prompt split into its messages: who sent each, and the text. */
fun parseDelivery(prompt: String): List<Pair<String, String>> =
    prompt.split("\n\n---\n\n").mapNotNull { part ->
        Regex("\\[Mob collaboration · from (.+?) · request (\\S+) · exchange \\S+\\]\\n([\\s\\S]*)").find(part)
            ?.let { it.groupValues[1] to it.groupValues[3].trim() }
    }

/** A readable title for a collaboration tool step, or null for other steps. */
fun collabStepTitle(a: Activity, sessions: List<Session> = emptyList()): String? {
    if (a.server != "mob-mcp") return null
    fun arg(k: String) = a.arguments?.get(k) as? String ?: ""
    val to = arg("to").removePrefix("@").lowercase()
    val byId = if (to.length >= 6 && HEX.matches(to)) sessions.filter { it.id.startsWith(to) } else emptyList()
    val hit = sessions.firstOrNull { it.handle == to } ?: byId.singleOrNull()
    val who = when {
        hit != null -> agentShortLabel(hit)
        to.isEmpty() -> "an agent"
        HEX.matches(to) -> to
        else -> "@$to"
    }
    return when (a.tool) {
        "send_request" -> "Asked $who: ${arg("request")}"
        "ask_clarification" -> "Asked for clarification: ${arg("text")}"
        "answer_clarification" -> "Answered the clarification: ${arg("text")}"
        "complete_request" -> if (arg("outcome") == "failed") "Reported it couldn't: ${arg("result")}" else "Replied: ${arg("result")}"
        "list_collaborators" -> "Looked up the mentioned agents"
        else -> null
    }
}

/**
 * Conversations for the @ picker matching [query], not [self]: with a handle first, then others;
 * archived ones last within each group.
 */
fun mentionCandidates(sessions: List<Session>, query: String, self: String?, limit: Int = 8): List<Session> {
    val q = query.lowercase()
    fun score(s: Session): Int = when {
        q.isEmpty() -> if (s.handle != null) 0 else 1
        s.handle?.startsWith(q) == true -> 0
        s.displayTitle.lowercase().split(Regex("\\s+")).any { it.startsWith(q) } -> if (s.handle != null) 0 else 1
        q.length >= 4 && s.id.startsWith(q) -> 2
        else -> -1
    }
    return sessions.filter { it.id != self }.map { it to score(it) }.filter { it.second >= 0 }
        .sortedWith(compareBy<Pair<Session, Int>> { it.second }.thenBy { it.first.archived == true })
        .take(limit).map { it.first }
}

/** A handle suggested from [title] that nobody holds: numbered when taken, "agent" when the title gives none. */
fun suggestHandle(title: String?, taken: Set<String>): String? {
    val base = suggestHandle(title) ?: "agent"
    if (base !in taken) return base
    return (2..99).map { "${base.take(28)}-$it" }.firstOrNull { validHandle(it) && it !in taken }
}

/** One message in a collab turn: who sent it (null = Mob), what they did and what they wrote. */
data class PeerPart(val senderId: String?, val name: String, val label: String, val body: String, val context: String? = null)

/**
 * What a collab turn carried, from mobd's structured deliveries when their requests are known, else
 * from the labelled prompt (requests load after the conversation opens).
 */
fun peerParts(deliveries: List<Delivery>?, prompt: String, requests: List<CollabRequest>, sessions: List<Session>): List<PeerPart> {
    val list = deliveries.orEmpty()
    if (list.isNotEmpty() && list.all { d -> d.kind == "notice" || requests.any { it.id == d.requestId } }) {
        return list.map { d ->
            val r = requests.firstOrNull { it.id == d.requestId }
            val sender = r?.let { deliverySender(d.kind, it) }
            val session = sessions.firstOrNull { it.id == sender }
            val saved = if (r != null && sender != null) (if (sender == r.from) r.fromName else r.toName) ?: sender else "Mob"
            PeerPart(sender, session?.let { agentShortLabel(it) } ?: saved, deliveryLabel(d.kind, r?.status), d.text.orEmpty(), r?.text.takeIf { d.kind != "request" })
        }
    }
    return parseDelivery(prompt).map { (from, body) -> PeerPart(from.takeIf { it != "Mob" }, from, "", deliveryBody(body)) }
}
