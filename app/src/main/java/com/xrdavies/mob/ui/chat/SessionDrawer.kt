package com.xrdavies.mob.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xrdavies.mob.data.Session
import com.xrdavies.mob.data.Status
import com.xrdavies.mob.ui.common.relativeTime
import com.xrdavies.mob.viewmodel.AppState

/** Chat list for the active machine, with the machine switcher on top. */
@Composable
fun SessionDrawer(appState: AppState, onClose: () -> Unit, onManageMachines: () -> Unit) {
    val chat = appState.chat
    val sessions by chat.sessions.collectAsState()
    val archived by chat.archived.collectAsState()
    val currentId by chat.currentId.collectAsState()
    var showArchived by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Session?>(null) }
    var deleting by remember { mutableStateOf<Session?>(null) }
    var query by remember { mutableStateOf("") }
    val searching = query.isNotBlank()
    // Search covers archived chats too, so load them once a search starts.
    LaunchedEffect(searching) { if (searching) chat.loadArchived() }
    val machines by appState.machines.collectAsState()
    val activeId by appState.activeMachineId.collectAsState()

    val pinned = sessions.filter { it.pinned == true }
    val recent = sessions.filter { it.pinned != true }

    ModalDrawerSheet(Modifier.widthIn(max = 320.dp)) {
        MachineSwitcher(appState, onManageMachines)
        HorizontalDivider()
        FilledTonalButton(
            onClick = {
                chat.open(null)
                onClose()
            },
            enabled = appState.activeMachine != null,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Icon(Icons.Filled.Add, null)
            Spacer(Modifier.width(8.dp))
            Text("New chat")
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search chats") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = { if (searching) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, "Clear search") } },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        )
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (searching) {
                val q = query.trim()
                val found = (sessions + archived.orEmpty()).distinctBy { it.id }.filter { it.displayTitle.contains(q, ignoreCase = true) }
                val otherMachines = machines.filter { it.id != activeId && it.name.contains(q, ignoreCase = true) }
                item { SectionLabel(if (found.isEmpty()) "No chats match" else "Chats") }
                items(found, key = { "f" + it.id }) { s ->
                    SessionRow(s, s.id == currentId, appState, { renaming = s }, { deleting = s }) {
                        query = ""
                        onClose()
                    }
                }
                if (otherMachines.isNotEmpty()) {
                    item { SectionLabel("Machines") }
                    items(otherMachines, key = { "m" + it.id }) { m ->
                        NavigationDrawerItem(
                            label = { Text("Switch to ${m.name}") },
                            icon = { Icon(Icons.Filled.DesktopMac, null) },
                            selected = false,
                            onClick = {
                                appState.selectMachine(m.id)
                                query = ""
                            },
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                }
                return@LazyColumn
            }
            if (pinned.isNotEmpty()) {
                item { SectionLabel("Pinned") }
                items(pinned, key = { "p" + it.id }) { s ->
                    SessionRow(s, s.id == currentId, appState, { renaming = s }, { deleting = s }) { onClose() }
                }
            }
            item { SectionLabel("Recent") }
            if (recent.isEmpty()) {
                item {
                    Text(
                        "No chats yet", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
                    )
                }
            }
            items(recent, key = { "r" + it.id }) { s ->
                SessionRow(s, s.id == currentId, appState, { renaming = s }, { deleting = s }) { onClose() }
            }
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            showArchived = !showArchived
                            if (showArchived) chat.loadArchived()
                        }
                        .padding(horizontal = 28.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Archived", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Icon(if (showArchived) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (showArchived) {
                val list = archived.orEmpty()
                if (list.isEmpty()) item {
                    Text(
                        "Nothing archived", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp),
                    )
                }
                items(list, key = { "a" + it.id }) { s ->
                    SessionRow(s, s.id == currentId, appState, { renaming = s }, { deleting = s }) { onClose() }
                }
            }
        }
    }

    renaming?.let { s ->
        var title by remember(s.id) { mutableStateOf(TextFieldValue(s.title.orEmpty(), TextRange(0, s.title.orEmpty().length))) }
        val focus = remember { FocusRequester() }
        LaunchedEffect(s.id) { focus.requestFocus() }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename chat") },
            text = { OutlinedTextField(title, { title = it }, singleLine = true, modifier = Modifier.fillMaxWidth().focusRequester(focus)) },
            confirmButton = {
                TextButton(enabled = title.text.isNotBlank(), onClick = {
                    chat.rename(s, title.text.trim())
                    renaming = null
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
    deleting?.let { s ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete chat?") },
            text = { Text("“${s.displayTitle}” and its screenshots are removed from the office Mac. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    chat.delete(s)
                    deleting = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun MachineSwitcher(appState: AppState, onManageMachines: () -> Unit) {
    val machines by appState.machines.collectAsState()
    val activeId by appState.activeMachineId.collectAsState()
    val connected by appState.isConnected.collectAsState()
    val active = machines.firstOrNull { it.id == activeId }
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { open = true }
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.DesktopMac, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(active?.name ?: "No office Mac", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (active != null) {
                        Box(Modifier.size(7.dp).background(if (connected) Color(0xFF22C55E) else Color(0xFFEF4444), CircleShape))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        active?.mobdUrl ?: "Add one in Settings", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(Icons.Filled.UnfoldMore, "Switch machine")
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            machines.forEach { m ->
                DropdownMenuItem(
                    text = { Text(m.name) },
                    leadingIcon = { if (m.id == activeId) Icon(Icons.Filled.Check, null) else Spacer(Modifier.size(24.dp)) },
                    onClick = {
                        open = false
                        appState.selectMachine(m.id)
                    },
                )
            }
            if (machines.isNotEmpty()) HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Manage machines…") },
                leadingIcon = { Icon(Icons.Filled.Settings, null) },
                onClick = {
                    open = false
                    onManageMachines()
                },
            )
        }
    }
}

@Composable
private fun SessionRow(
    s: Session,
    selected: Boolean,
    appState: AppState,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onOpened: () -> Unit,
) {
    val chat = appState.chat
    var menu by remember { mutableStateOf(false) }
    NavigationDrawerItem(
        label = {
            Column {
                Text(
                    s.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
                Text(
                    listOfNotNull(s.runner, relativeTime(s.updatedAt).takeIf { it.isNotEmpty() }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        icon = { SessionStatusIcon(s) },
        selected = selected,
        onClick = {
            chat.open(s.id)
            onOpened()
        },
        badge = {
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.MoreVert, "Chat actions") }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Filled.Edit, null) }, onClick = {
                        menu = false
                        onRename()
                    })
                    if (s.archived != true) DropdownMenuItem(
                        text = { Text(if (s.pinned == true) "Unpin" else "Pin") },
                        leadingIcon = { Icon(Icons.Filled.PushPin, null) },
                        onClick = {
                            menu = false
                            chat.setPinned(s, s.pinned != true)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(if (s.archived == true) "Unarchive" else "Archive") },
                        leadingIcon = { Icon(if (s.archived == true) Icons.Outlined.Unarchive else Icons.Outlined.Archive, null) },
                        onClick = {
                            menu = false
                            chat.setArchived(s, s.archived != true)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        enabled = !Status.busy(s.status),
                        onClick = {
                            menu = false
                            onDelete()
                        },
                    )
                }
            }
        },
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}

/** Running spinner, waiting (needs you) dot, failure dot, or a plain chat bubble. */
@Composable
fun SessionStatusIcon(s: Session) {
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        when {
            s.waiting == true -> Box(Modifier.size(10.dp).background(Color(0xFFF59E0B), CircleShape))
            s.status == Status.RUNNING -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            s.status == Status.QUEUED -> Icon(Icons.Filled.Schedule, "Queued", Modifier.size(18.dp))
            s.status == Status.FAILED -> Box(Modifier.size(10.dp).background(Color(0xFFEF4444), CircleShape))
            else -> Icon(Icons.Filled.ChatBubbleOutline, null, Modifier.size(18.dp))
        }
    }
}
