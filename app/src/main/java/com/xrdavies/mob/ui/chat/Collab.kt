package com.xrdavies.mob.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.xrdavies.mob.data.*
import com.xrdavies.mob.ui.common.Markdown
import com.xrdavies.mob.viewmodel.AppState

private val CollabWarning = Color(0xFFD97706)

// Agent collaboration UI (mobd docs/agent-collaboration-design.md): handles, the @ picker, pending
// requests, and peer messages. Shown only when mobd has "agentCollaboration".

/** The agent's handle as a small badge next to a title. */
@Composable
fun HandleBadge(handle: String?, archived: Boolean = false) {
    if (handle.isNullOrEmpty()) return
    Text(
        "@$handle",
        style = MaterialTheme.typography.labelSmall,
        color = if (archived) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/**
 * Sets, changes, moves or removes a conversation's handle. A handle another conversation holds is
 * only moved after this dialog says whose it is.
 */
@Composable
fun HandleDialog(appState: AppState, s: Session, onDone: () -> Unit) {
    val chat = appState.chat
    val sessions by chat.sessions.collectAsState()
    val archived by chat.archived.collectAsState()
    LaunchedEffect(Unit) { chat.loadArchived() }
    val others = (sessions + archived.orEmpty()).distinctBy { it.id }.filter { it.id != s.id }
    val current = s.handle.orEmpty()
    val suggested = remember(others) { suggestHandle(s.title, others.mapNotNull { it.handle }.toSet()).orEmpty() }
    var value by remember { mutableStateOf(current.ifEmpty { suggested }) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    // mobd said the handle is taken by a conversation not in the list.
    var takenElsewhere by remember { mutableStateOf("") }
    val problem = if (value.isEmpty()) "" else handleProblem(value)
    val holder = others.firstOrNull { it.handle == value }
    val moving = problem.isEmpty() && (holder != null || (value.isNotEmpty() && takenElsewhere == value))
    val canSave = value.isNotEmpty() && problem.isEmpty() && value != current && !busy
    val colors = MaterialTheme.colorScheme

    fun save(handle: String, take: Boolean) {
        busy = true
        chat.setHandle(s, handle, take) { err ->
            busy = false
            when {
                err == null -> onDone()
                err.contains("another conversation") -> takenElsewhere = handle
                else -> error = err
            }
        }
    }

    Dialog(onDismissRequest = { if (!busy) onDone() }) {
        Surface(shape = RoundedCornerShape(20.dp), color = colors.surfaceContainerHigh) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(36.dp).background(colors.primary.copy(alpha = 0.12f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.AlternateEmail, null, Modifier.size(18.dp), tint = colors.primary)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (current.isEmpty()) "Give this agent a handle" else "Change handle", style = MaterialTheme.typography.titleMedium)
                        Text(listOfNotNull(s.displayTitle, s.runner?.let { RUNNER_LABELS[it] ?: it }).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value, { value = normalizeHandleInput(it); error = "" }, Modifier.fillMaxWidth(), singleLine = true,
                    prefix = { Text("@", fontFamily = FontFamily.Monospace) },
                    suffix = { Text("${value.length}/32", style = MaterialTheme.typography.labelSmall) },
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                    isError = problem.isNotEmpty() || error.isNotEmpty(),
                    placeholder = { Text("order-service", fontFamily = FontFamily.Monospace) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                    supportingText = { Text(problem.ifEmpty { error }.ifEmpty { "Lowercase letters, digits and -. Unique on this office computer." }) },
                )
                if (current.isEmpty() && suggested.isNotEmpty() && value != suggested) {
                    SuggestionChip(onClick = { value = suggested }, label = { Text("Use @$suggested", fontFamily = FontFamily.Monospace) })
                }
                Spacer(Modifier.height(8.dp))
                if (moving) {
                    Row(Modifier.fillMaxWidth().background(CollabWarning.copy(alpha = 0.12f), RoundedCornerShape(10.dp)).padding(12.dp)) {
                        Icon(Icons.Filled.WarningAmber, null, Modifier.size(16.dp), tint = CollabWarning)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "@$value belongs to ${holder?.let { "\"${it.displayTitle}\"" } ?: "an archived conversation"}. Moving it leaves that " +
                                "conversation without a handle; its past requests keep their names.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else {
                    Text(
                        "In another conversation, type @${value.takeIf { it.isNotEmpty() && problem.isEmpty() } ?: "handle"} to ask this agent for help. " +
                            "The title keeps describing its work.",
                        Modifier.fillMaxWidth().background(colors.surfaceVariant, RoundedCornerShape(10.dp)).padding(12.dp),
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(12.dp))
                if (current.isNotEmpty()) {
                    TextButton(onClick = { save("", false) }, enabled = !busy) { Text("Remove @$current", color = colors.error) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDone, enabled = !busy) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { save(value, moving) }, enabled = canSave) {
                        Text(if (moving) "Move @$value here" else if (current.isEmpty()) "Set handle" else "Save", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/**
 * The @ picker above the composer while an @-query is typed: agents with a handle first, then other
 * conversations. Archived agents are greyed out and cannot be picked; their row offers Unarchive or
 * moving the handle here. Picking a conversation without a handle offers to name it first.
 */
@Composable
fun MentionPicker(appState: AppState, text: String, current: String?, onPick: (Session) -> Unit) {
    val q = mentionQuery(text) ?: return
    val chat = appState.chat
    val sessions by chat.sessions.collectAsState()
    val archived by chat.archived.collectAsState()
    LaunchedEffect(Unit) { chat.loadArchived() }
    var naming by remember(text) { mutableStateOf<Session?>(null) }
    var error by remember(text) { mutableStateOf("") }
    val all = (sessions + archived.orEmpty()).distinctBy { it.id }
    val matches = mentionCandidates(all, q, current)
    val colors = MaterialTheme.colorScheme
    val agent = naming
    if (agent != null) {
        val handle = remember(agent.id) { suggestHandle(agent.title, all.mapNotNull { it.handle }.toSet()) }
        Surface(tonalElevation = 4.dp, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text("Give ${agent.displayTitle} a handle?", maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("You and other agents can then mention it by name instead of its ID.",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                if (error.isNotEmpty()) Text(error, style = MaterialTheme.typography.bodySmall, color = colors.error)
                Spacer(Modifier.height(8.dp))
                if (handle != null) {
                    Button(onClick = {
                        chat.setHandle(agent, handle) { err -> if (err == null) onPick(agent.copy(handle = handle)) else error = err }
                    }, Modifier.fillMaxWidth()) { Text("Name it @$handle", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                OutlinedButton(onClick = { onPick(agent) }, Modifier.fillMaxWidth()) { Text("Mention as @${agent.id.take(6)}") }
                TextButton(onClick = { naming = null }, Modifier.align(Alignment.End)) { Text("Back") }
            }
        }
        return
    }
    if (matches.isEmpty()) return
    Surface(tonalElevation = 4.dp, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
            matches.forEachIndexed { i, s ->
                if (i == 0 || (matches[i - 1].handle != null) != (s.handle != null)) {
                    Text(if (s.handle != null) "Agents" else "Conversations", Modifier.padding(start = 12.dp, top = 6.dp, bottom = 2.dp),
                        style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
                val off = s.archived == true
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = !off) { if (s.handle != null) onPick(s) else naming = s }.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        val color = if (off) colors.outline else colors.onSurface
                        Text(buildAnnotatedString {
                            s.handle?.let { withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)) { append("@$it  ") } }
                            append(s.displayTitle)
                        }, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(s.id.take(6).takeIf { s.handle == null }, s.runner?.let { RUNNER_LABELS[it] ?: it }, "archived".takeIf { off }).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = colors.outline)
                    }
                    if (off) {
                        TextButton(onClick = { chat.setArchived(s, false) }) { Text("Unarchive") }
                        if (s.handle != null && current != null) TextButton(onClick = {
                            chat.sessions.value.firstOrNull { it.id == current }?.let { me -> chat.setHandle(me, s.handle, take = true) }
                        }) { Text("Give @${s.handle} here") }
                    }
                }
            }
        }
    }
}

/** Why a typed mention stopped the send; an archived agent's row offers the same actions as the picker. */
@Composable
fun MentionProblemRow(appState: AppState, p: MentionProblem, current: String?, onFixed: () -> Unit) {
    val chat = appState.chat
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (p is MentionProblem.Archived) "@${p.handle} is archived" else "No agent is called @${p.handle}",
            Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
        )
        if (p is MentionProblem.Archived) {
            TextButton(onClick = { chat.setArchived(p.session, false); onFixed() }) { Text("Unarchive") }
            val me = chat.sessions.value.firstOrNull { it.id == current }
            if (me != null) TextButton(onClick = { chat.setHandle(me, p.handle, take = true); onFixed() }) { Text("Give @${p.handle} here") }
        }
    }
}

/** Unfinished requests of the open conversation, above the timeline; ended ones needing the user too. */
@Composable
fun CollabStrip(appState: AppState, modifier: Modifier = Modifier) {
    val chat = appState.chat
    val requests by chat.collab.collectAsState()
    var dismissed by remember { mutableStateOf(setOf<String>()) }
    var open by remember { mutableStateOf<CollabRequest?>(null) }
    val shown = requests.filter { !it.ended || (it.needsUser && it.id !in dismissed) }
    open?.let { r -> RequestSheet(appState, requests.firstOrNull { it.id == r.id } ?: r) { open = null } }
    if (shown.isEmpty()) return
    Surface(tonalElevation = 2.dp, shape = RoundedCornerShape(12.dp), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            shown.take(3).forEach { r ->
                val current = chat.currentId.value
                val other = if (r.from == current) "To ${r.toName}" else "From ${r.fromName}"
                Row(Modifier.fillMaxWidth().clickable { open = r }.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Groups, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text("$other · ${requestStatusText(r.status)}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (r.needsUser) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    if (r.ended) TextButton(onClick = { dismissed = dismissed + r.id }) { Text("Dismiss") }
                }
            }
            if (shown.size > 3) Text("+${shown.size - 3} more", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun RequestSheet(appState: AppState, r: CollabRequest, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("${r.fromName} → ${r.toName}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(requestStatusText(r.status), fontWeight = FontWeight.SemiBold)
                Text(r.text.orEmpty())
                r.thread.orEmpty().forEach { n ->
                    Text((if (n.kind == "clarification") "Question: " else "Answer: ") + n.text.orEmpty(), style = MaterialTheme.typography.bodySmall)
                }
                r.result?.takeIf { it.isNotEmpty() }?.let { Text("Result: $it") }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
        dismissButton = {
            if (!r.ended) Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = { appState.chat.cancelRequest(r); onClose() }) {
                    Text("Cancel request", color = MaterialTheme.colorScheme.error)
                }
                // Being handled: the other agent's turn keeps working unless it is stopped too.
                if (r.status == "in_progress") TextButton(onClick = { appState.chat.cancelRequest(r, stopTurn = true); onClose() }) {
                    Text("Cancel and stop its turn", color = MaterialTheme.colorScheme.error)
                }
            }
        },
    )
}

/** An agent's coloured circle: the same agent keeps its colour everywhere. */
@Composable
private fun AgentAvatar(id: String, label: String) {
    Box(Modifier.size(24.dp).background(agentColor(id), CircleShape), contentAlignment = Alignment.Center) {
        Text(agentInitial(label), color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
    }
}

private fun agentColor(id: String) = Color.hsl(agentHue(id).toFloat(), 0.55f, 0.48f)

/**
 * A turn another agent started, on the left with the agent's name and colour, like someone in a group
 * chat; only what the user typed sits on the right. mobd's instructions to the recipient are left out.
 */
@Composable
fun PeerMessage(task: Task?, message: Message, appState: AppState) {
    val chat = appState.chat
    val requests by chat.collab.collectAsState()
    val sessions by chat.sessions.collectAsState()
    val archived by chat.archived.collectAsState()
    val all = (sessions + archived.orEmpty()).distinctBy { it.id }
    val parts = peerParts(task?.origin?.deliveries, message.content, requests, all)
    val colors = MaterialTheme.colorScheme
    if (parts.isEmpty()) {
        Text(message.content, Modifier.padding(start = 34.dp), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        parts.forEach { p ->
            val id = p.senderId
            if (id == null) {
                Row(Modifier.padding(start = 34.dp)) {
                    Icon(Icons.Outlined.Info, null, Modifier.size(14.dp).padding(top = 1.dp), tint = colors.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Text((p.context?.let { "Request \u201c$it\u201d: " } ?: "") + p.body, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                return@forEach
            }
            val session = all.firstOrNull { it.id == id }
            val tint = agentColor(id)
            Row(Modifier.fillMaxWidth()) {
                AgentAvatar(id, p.name)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            p.name, Modifier.weight(1f, fill = false).then(if (session != null) Modifier.clickable { chat.open(session.id) } else Modifier),
                            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        val meta = listOfNotNull(session?.runner?.let { RUNNER_LABELS[it] ?: it }, p.label.takeIf { it.isNotEmpty() }).joinToString(" · ")
                        if (meta.isNotEmpty()) Text(meta, Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1)
                    }
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(tint.copy(alpha = 0.08f))
                            .drawBehind { drawRect(tint, size = Size(2.dp.toPx(), size.height)) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) { Markdown(p.body) }
                    p.context?.let {
                        Text("Re: $it", Modifier.padding(top = 2.dp), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** A user's text with @mentions of other conversations as tinted links that open them. */
@Composable
fun MentionText(text: String, appState: AppState, color: Color, modifier: Modifier = Modifier) {
    val chat = appState.chat
    val sessions by chat.sessions.collectAsState()
    val archived by chat.archived.collectAsState()
    val current by chat.currentId.collectAsState()
    val all = (sessions + archived.orEmpty()).distinctBy { it.id }
    val style = TextLinkStyles(SpanStyle(fontWeight = FontWeight.SemiBold, background = color.copy(alpha = 0.2f)))
    Text(buildAnnotatedString {
        mentionSegments(text, all, current).forEach { seg ->
            val session = seg.session
            if (session == null) append(seg.text)
            else withLink(LinkAnnotation.Clickable(session.id, style) { chat.open(session.id) }) { append(seg.text) }
        }
    }, modifier, color = color)
}

/** Highlights resolved @mentions while typing; the text and its offsets stay as typed. */
class MentionHighlight(private val sessions: List<Session>, private val self: String?, private val tint: Color) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val out = buildAnnotatedString {
            mentionSegments(text.text, sessions, self).forEach { seg ->
                if (seg.session == null) append(seg.text)
                else withStyle(SpanStyle(background = tint.copy(alpha = 0.2f), color = tint)) { append(seg.text) }
            }
        }
        return TransformedText(out, OffsetMapping.Identity)
    }
}
