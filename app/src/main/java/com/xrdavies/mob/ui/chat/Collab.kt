package com.xrdavies.mob.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xrdavies.mob.data.CollabRequest
import com.xrdavies.mob.data.MentionProblem
import com.xrdavies.mob.data.Session
import com.xrdavies.mob.data.mentionQuery
import com.xrdavies.mob.data.peerSender
import com.xrdavies.mob.data.requestStatusText
import com.xrdavies.mob.data.suggestHandle
import com.xrdavies.mob.data.validHandle
import com.xrdavies.mob.viewmodel.AppState

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

/** Sets, clears or moves a conversation's handle; a taken handle offers to move it here. */
@Composable
fun HandleDialog(appState: AppState, s: Session, suggested: String? = null, onDone: () -> Unit) {
    val chat = appState.chat
    var value by remember { mutableStateOf(s.handle ?: suggested ?: suggestHandle(s.title).orEmpty()) }
    var problem by remember { mutableStateOf<String?>(null) }
    var taken by remember { mutableStateOf(false) }
    val h = value.trim().removePrefix("@")
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Agent handle") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Other agents and you mention this conversation as @handle. The title keeps describing its work.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value, { value = it.lowercase(); problem = null; taken = false }, singleLine = true,
                    prefix = { Text("@") }, isError = h.isNotEmpty() && !validHandle(h))
                (problem ?: if (h.isNotEmpty() && !validHandle(h)) "2–32 lowercase letters, digits and -, starting with a letter" else null)
                    ?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = h.isEmpty() || validHandle(h), onClick = {
                chat.setHandle(s, h, take = taken) { err ->
                    when {
                        err == null -> onDone()
                        err.contains("another conversation") -> { taken = true; problem = "@$h belongs to another conversation. Save again to give it to this one." }
                        else -> problem = err
                    }
                }
            }) { Text(if (taken) "Give @$h to this conversation" else if (h.isEmpty() && s.handle != null) "Remove" else "Save") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

/**
 * The @ picker above the composer while an @-query is typed: handles first, then titles. Archived
 * agents are greyed out and cannot be picked; their row offers Unarchive or moving the handle here.
 */
@Composable
fun MentionPicker(appState: AppState, text: String, current: String?, onPick: (Session) -> Unit) {
    val q = mentionQuery(text) ?: return
    val chat = appState.chat
    val sessions by chat.sessions.collectAsState()
    val archived by chat.archived.collectAsState()
    LaunchedEffect(Unit) { chat.loadArchived() }
    val all = (sessions + archived.orEmpty()).distinctBy { it.id }.filter { it.id != current }
    val matches = all.filter {
        it.handle?.startsWith(q) == true || (q.isNotEmpty() && it.displayTitle.contains(q, ignoreCase = true)) || q.isEmpty()
    }.sortedWith(compareBy<Session> { it.handle == null }.thenBy { it.archived == true }).take(8)
    if (matches.isEmpty()) return
    var naming by remember { mutableStateOf<Session?>(null) }
    naming?.let { s -> HandleDialog(appState, s) { naming = null } }
    Surface(tonalElevation = 4.dp, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Column(Modifier.padding(vertical = 4.dp)) {
            matches.forEach { s ->
                val off = s.archived == true
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = !off) { onPick(s) }.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        val color = if (off) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface
                        Text(s.handle?.let { "@$it · ${s.displayTitle}" } ?: s.displayTitle, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(s.id.take(6), s.runner, "archived".takeIf { off }).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                    if (off) {
                        TextButton(onClick = { chat.setArchived(s, false) }) { Text("Unarchive") }
                        if (s.handle != null && current != null) TextButton(onClick = {
                            chat.sessions.value.firstOrNull { it.id == current }?.let { me -> chat.setHandle(me, s.handle, take = true) }
                        }) { Text("Give @${s.handle} here") }
                    } else if (s.handle == null) {
                        TextButton(onClick = { naming = s }) { Text("Name") }
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
            if (!r.ended) TextButton(onClick = { appState.chat.cancelRequest(r); onClose() }) {
                Text("Cancel request", color = MaterialTheme.colorScheme.error)
            }
        },
    )
}

/** A turn another agent started: its delivered text, labelled with the sender, not a user bubble. */
@Composable
fun PeerMessage(prompt: String) {
    val sender = peerSender(prompt) ?: "another agent"
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Groups, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("From $sender", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(prompt, style = MaterialTheme.typography.bodySmall, maxLines = 12, overflow = TextOverflow.Ellipsis)
        }
    }
}
