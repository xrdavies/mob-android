package com.xrdavies.mob.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xrdavies.mob.data.*
import com.xrdavies.mob.data.queueReasonText
import com.xrdavies.mob.ui.common.Markdown
import com.xrdavies.mob.ui.common.RemoteImage
import com.xrdavies.mob.ui.common.duration
import com.xrdavies.mob.ui.common.tokens
import com.xrdavies.mob.viewmodel.AppState

private val Success = Color(0xFF16A34A)
private val Warning = Color(0xFFD97706)

private val RUNNER_LABELS = mapOf("codex" to "Codex", "claude" to "Claude Code")

/** One timeline entry. [last] marks the newest turn footer (it offers Retry). */
@Composable
fun TimelineRow(item: TimelineItem, appState: AppState, runner: String?, last: Boolean) {
    when (item) {
        is TimelineItem.User -> UserMessage(item.message, item.task, appState)
        is TimelineItem.Assistant -> AssistantMessage(item.message, runner)
        is TimelineItem.Steps -> Column(Modifier.padding(start = 34.dp)) { item.activities.forEach { ActivityRow(it, appState) } }
        is TimelineItem.Group -> ActivityGroup(item.activities, appState)
        is TimelineItem.Ask -> QuestionCard(item.question, appState)
        is TimelineItem.Queued -> Note(Icons.Filled.Schedule, "Queued" + (item.task.queuePosition?.let { " #$it" } ?: "") + (queueReasonText(item.task.queueReason)?.let { " · $it" } ?: " · waits for the desktop"))
        is TimelineItem.Working -> Working(item)
        is TimelineItem.Footer -> TurnFooter(item.task, appState, last)
    }
}

@Composable
private fun UserMessage(message: Message, task: Task?, appState: AppState) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        task?.attachments?.takeIf { it.isNotEmpty() }?.let { ids ->
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ids.forEach { id ->
                    RemoteImage(appState.apiClient.okHttpClient, appState.apiClient.artifactUrl(id), Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)))
                }
            }
        }
        Surface(
            shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Text(message.content, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), color = MaterialTheme.colorScheme.onPrimary)
        }
    }
}

@Composable
private fun AssistantMessage(message: Message, runner: String?) {
    val clipboard = LocalClipboardManager.current
    Row(Modifier.fillMaxWidth()) {
        Box(
            Modifier.size(24.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.AutoAwesome, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onPrimary) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Mob", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(6.dp))
                Text(RUNNER_LABELS[runner ?: "codex"] ?: runner.orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { clipboard.setText(AnnotatedString(message.content)) }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Outlined.ContentCopy, "Copy reply", Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Markdown(message.content)
        }
    }
}

private fun activityIcon(a: Activity): ImageVector = when (a.kind) {
    "tool" -> when (a.tool) {
        "click" -> Icons.Outlined.AdsClick
        "type_text", "key" -> Icons.Outlined.Keyboard
        "screenshot" -> Icons.Outlined.PhotoCamera
        "open_app", "list_windows" -> Icons.Outlined.Web
        else -> Icons.Outlined.Build
    }
    "command" -> Icons.Outlined.Terminal
    "reasoning" -> Icons.Outlined.Psychology
    "plan" -> Icons.Outlined.Checklist
    "file_change" -> Icons.Outlined.Difference
    "web_search" -> Icons.Outlined.Language
    "notice" -> Icons.Outlined.WarningAmber
    else -> Icons.Outlined.Circle
}

@Composable
private fun StepStatus(a: Activity) {
    Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
        when (a.status) {
            "running" -> CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 1.5.dp)
            "failed" -> Icon(Icons.Filled.Close, "Failed", Modifier.size(15.dp), tint = MaterialTheme.colorScheme.error)
            else -> Icon(activityIcon(a), null, Modifier.size(16.dp), tint = if (a.kind == "notice") Warning else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** One agent step: tool call, command, reasoning, plan, file change, search or notice. Tap for details. */
@Composable
fun ActivityRow(a: Activity, appState: AppState) {
    var open by remember(a.id) { mutableStateOf(false) }
    val notice = a.kind == "notice"
    val meta = when (a.kind) {
        "tool" -> a.tool.orEmpty()
        "command" -> "shell"
        "reasoning", "notice" -> ""
        else -> a.kind.replace('_', ' ')
    }
    val took = if (a.status == "running" || notice) "" else duration(a.startedAt, a.updatedAt)
    Column {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { open = !open }.padding(horizontal = 4.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepStatus(a)
            Spacer(Modifier.width(8.dp))
            Text(
                a.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = if (a.kind == "reasoning") FontStyle.Italic else FontStyle.Normal,
                color = when {
                    notice -> Warning
                    a.kind == "reasoning" -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
            val metaText = listOf(meta, took).filter { it.isNotEmpty() }.joinToString(" · ")
            if (metaText.isNotEmpty()) Text(metaText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp))
            Icon(Icons.Filled.ChevronRight, null, Modifier.size(16.dp).rotate(if (open) 90f else 0f), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AnimatedVisibility(open) { ActivityDetails(a, appState) }
    }
}

@Composable
private fun Mono(text: String, italic: Boolean = false) {
    var all by remember { mutableStateOf(false) }
    val long = text.lines().size > 16 || text.length > 1500
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))) {
        Text(
            if (long && !all) text.lines().take(16).joinToString("\n").take(1500) + "\n…" else text,
            Modifier.padding(8.dp),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (long) TextButton(onClick = { all = !all }, modifier = Modifier.fillMaxWidth()) { Text(if (all) "Show less" else "Show all") }
    }
}

@Composable
private fun ActivityDetails(a: Activity, appState: AppState) {
    Column(Modifier.fillMaxWidth().padding(start = 30.dp, end = 4.dp, top = 2.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (a.kind == "tool") a.arguments?.takeIf { it.isNotEmpty() }?.forEach { (k, v) ->
            Row {
                Text(k, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(min = 64.dp))
                Text(v.toString(), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
            }
        }
        if (a.kind == "command" && !a.command.isNullOrEmpty()) Mono("$ ${a.command}")
        a.images?.forEach { id ->
            RemoteImage(
                appState.apiClient.okHttpClient, appState.apiClient.artifactUrl(id),
                Modifier.fillMaxWidth().heightIn(max = 220.dp).aspectRatio(16f / 10f).clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Fit,
            )
        }
        if (a.kind == "plan") a.items?.forEach { it ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (it.completed == true) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank, null, Modifier.size(16.dp),
                    tint = if (it.completed == true) Success else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    it.text, style = MaterialTheme.typography.bodySmall,
                    textDecoration = if (it.completed == true) TextDecoration.LineThrough else null,
                    color = if (it.completed == true) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        if (a.kind == "file_change") a.changes?.forEach { c ->
            Row {
                Text(c.kind.orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(56.dp))
                Text(c.path, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
            }
        }
        if (!a.text.isNullOrEmpty() && a.tool != "screenshot") Mono(a.text + if (a.truncated == true) "\n… (truncated)" else "", italic = a.kind == "reasoning")
        if (a.kind == "command" && a.exitCode != null) {
            val ok = a.exitCode == 0
            Text(
                "exit ${a.exitCode}", style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = if (ok) Success else MaterialTheme.colorScheme.error,
                modifier = Modifier.background((if (ok) Success else MaterialTheme.colorScheme.error).copy(alpha = 0.1f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        a.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}

/** Three or more consecutive steps, folded: "5 steps · 0:42 · latest step". */
@Composable
private fun ActivityGroup(activities: List<Activity>, appState: AppState) {
    var open by remember(activities.first().id) { mutableStateOf(false) }
    val latest = activities.last()
    val failed = activities.count { it.status == "failed" }
    val running = activities.any { it.status == "running" }
    Column(Modifier.padding(start = 34.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { open = !open }.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (running) CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 1.5.dp)
            else Icon(Icons.Outlined.Layers, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text("${activities.size} steps", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(" · " + duration(activities.first().startedAt, latest.updatedAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (failed > 0) Text(" · $failed failed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Text(
                if (open) "" else latest.title, Modifier.weight(1f).padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(Icons.Filled.ChevronRight, null, Modifier.size(16.dp).rotate(if (open) 90f else 0f), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(start = 4.dp, end = 4.dp, bottom = 4.dp)) { activities.forEach { ActivityRow(it, appState) } }
        }
    }
}

@Composable
private fun Note(icon: ImageVector, text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Row(Modifier.padding(start = 34.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(14.dp), tint = color)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
private fun Working(item: TimelineItem.Working) {
    Row(Modifier.padding(start = 34.dp), verticalAlignment = Alignment.CenterVertically) {
        if (item.waiting) Box(Modifier.size(8.dp).background(Warning, CircleShape))
        else CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            if (item.waiting) "Waiting for your answer" else item.latest?.title?.let { "Working · $it" } ?: "Working…",
            style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (item.waiting) Warning else MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun TurnFooter(task: Task, appState: AppState, last: Boolean) {
    val chat = appState.chat
    val u = task.usage
    val total = (u?.inputTokens ?: 0) + (u?.outputTokens ?: 0)
    val meta = listOfNotNull(
        duration(task.startedAt, task.finishedAt).takeIf { task.startedAt != null },
        "${tokens(total)} tokens".takeIf { total > 0 },
        u?.costUsd?.takeIf { it > 0 }?.let { if (it < 0.01) "$%.4f".format(it) else "$%.2f".format(it) },
        task.model,
        task.effort?.let { "effort $it" },
    )
    Column(Modifier.padding(start = 34.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (task.status == Status.FAILED) {
            Row(
                Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.06f), RoundedCornerShape(10.dp))
                    .border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Turn failed", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium)
                    task.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                if (last) TextButton(onClick = { chat.send(task.prompt.orEmpty(), model = task.model, effort = task.effort, attachments = task.attachments) }) {
                    Icon(Icons.Filled.Refresh, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Retry")
                }
            }
        }
        if (task.status == Status.CANCELLED) Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Block, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(4.dp))
            Text("Cancelled", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (meta.isNotEmpty()) Text(meta.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A decision the agent is waiting for, answered in the chat; settled ones show the outcome. */
@Composable
fun QuestionCard(q: Question, appState: AppState) {
    val chat = appState.chat
    val approval = q.isApproval
    if (!q.isPending) {
        val (line, color) = when {
            q.status == "answered" && approval -> (if (q.approved == true) "Approved" else "Denied") to (if (q.approved == true) Success else MaterialTheme.colorScheme.error)
            q.status == "answered" -> "You answered: ${q.answer.orEmpty()}" to MaterialTheme.colorScheme.onSurfaceVariant
            q.status == "expired" -> (if (approval) "No answer in time — treated as denied" else "No answer in time") to MaterialTheme.colorScheme.onSurfaceVariant
            else -> "Cancelled" to MaterialTheme.colorScheme.onSurfaceVariant
        }
        Row(
            Modifier.padding(start = 34.dp).fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)).padding(10.dp),
        ) {
            Icon(if (approval) Icons.Outlined.Shield else Icons.Outlined.HelpOutline, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Column {
                Text(q.text, style = MaterialTheme.typography.bodyMedium)
                Text(line + if (approval && q.status == "answered" && !q.answer.isNullOrBlank()) " · “${q.answer}”" else "", style = MaterialTheme.typography.labelSmall, color = color)
            }
        }
        return
    }

    var text by remember(q.id) { mutableStateOf("") }
    var sending by remember(q.id) { mutableStateOf(false) }
    fun answer(answer: String?, approved: Boolean?) {
        sending = true
        chat.answer(q, answer, approved)
    }
    val options = q.options.orEmpty()
    val freeText = q.allowText == true || options.isEmpty()
    Column(
        Modifier.padding(start = 34.dp).fillMaxWidth()
            .background(Warning.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
            .border(1.dp, Warning.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(if (approval) Icons.Outlined.Shield else Icons.Outlined.HelpOutline, null, Modifier.size(18.dp), tint = Warning)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(if (approval) "APPROVAL NEEDED" else "THE AGENT ASKS", style = MaterialTheme.typography.labelSmall, color = Warning)
                Text(q.text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                q.details?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        if (approval) {
            OutlinedTextField(text, { text = it }, placeholder = { Text("Optional note for the agent") }, enabled = !sending, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { answer(text.trim().ifEmpty { null }, true) }, enabled = !sending) {
                    Icon(Icons.Filled.Check, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Approve")
                }
                OutlinedButton(onClick = { answer(text.trim().ifEmpty { null }, false) }, enabled = !sending) {
                    Icon(Icons.Filled.Close, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error); Spacer(Modifier.width(4.dp))
                    Text("Deny", color = MaterialTheme.colorScheme.error)
                }
            }
        } else {
            if (options.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { o -> OutlinedButton(onClick = { answer(o, null) }, enabled = !sending) { Text(o) } }
            }
            if (freeText) Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    text, { text = it }, placeholder = { Text(if (options.isEmpty()) "Type your answer" else "Or type another answer") },
                    enabled = !sending, singleLine = true, modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = { answer(text.trim(), null) }, enabled = !sending && text.isNotBlank()) { Text("Send") }
            }
        }
    }
}
