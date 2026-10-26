package com.xrdavies.mob.ui.settings

import com.xrdavies.mob.stream.LiveQuality
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xrdavies.mob.data.Machine
import com.xrdavies.mob.viewmodel.AppState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(appState: AppState) {
    val machines by appState.machines.collectAsState()
    val activeId by appState.activeMachineId.collectAsState()
    var editing by remember { mutableStateOf<Machine?>(null) }
    // "" adds a machine, an ID pairs that one again; saveable because the QR scanner may recreate the activity.
    var pairing by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<Machine?>(null) }
    var showLog by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    var agentsOf by remember { mutableStateOf<Machine?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatusCard(appState, onPair = { pairing = it })

            SettingsCard("Machines") {
                if (machines.isEmpty()) {
                    Text("No office Mac yet. Run mobd link on the Mac that runs the agent, then add it here.", style = MaterialTheme.typography.bodyMedium)
                }
                machines.forEach { m ->
                    // Tap selects the machine; long-press chooses the route (LAN or Remote) and more.
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        Row(
                            Modifier.fillMaxWidth().combinedClickable(onLongClick = { menu = true }) { appState.selectMachine(m.id) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = m.id == activeId, onClick = { appState.selectMachine(m.id) })
                            Column(Modifier.weight(1f)) {
                                Text(m.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                RouteLine(appState, m)
                                if (m.needsPairing == true && m.addresses.size == 1) {
                                    Text("Needs pairing", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                                }
                            }
                            if (m.needsPairing == true) TextButton(onClick = { pairing = m.id }) { Text("Pair again") }
                            IconButton(onClick = { editing = m }) { Icon(Icons.Filled.Edit, "Edit ${m.name}") }
                        }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            RouteMenuItems(appState, m) { menu = false }
                            if (m.addresses.size > 1) HorizontalDivider()
                            DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; editing = m })
                            DropdownMenuItem(text = { Text("Pair again or add address") }, onClick = { menu = false; pairing = m.id })
                            DropdownMenuItem(
                                text = { Text("Remove", color = MaterialTheme.colorScheme.error) },
                                onClick = { menu = false; deleting = m },
                            )
                        }
                    }
                }
                OutlinedButton(onClick = { pairing = "" }) {
                    Icon(Icons.Filled.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add machine")
                }
            }

            NotificationsCard(appState)


            SettingsCard("Log") {
                Text("Connections, event streams, live view and errors, for troubleshooting.", style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { showLog = true }) { Text("Open log") }
            }

            SettingsCard("About") {
                val context = LocalContext.current
                val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
                Text("Mob for Android $version")
                Text("MIT License", color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { showLicenses = true }, contentPadding = PaddingValues(0.dp)) { Text("Open source licences") }
            }
        }
    }

    if (showLog) LogDialog { showLog = false }
    if (showLicenses) AcknowledgementsDialog { showLicenses = false }

    editing?.let { m ->
        MachineDialog(
            initial = machines.firstOrNull { it.id == m.id } ?: m,
            onSave = { name, labels ->
                appState.saveEdits(m.id, name, labels)
                editing = null
            },
            onRemoveAddress = { appState.removeAddress(m.id, it) },
            onRemove = {
                editing = null
                deleting = m
            },
            onPairAgain = {
                editing = null
                pairing = m.id
            },
            onQuality = { appState.setLiveQuality(m.id, it) },
            onAgents = { agentsOf = m },
            onDismiss = { editing = null },
        )
    }
    agentsOf?.let { m -> AgentSettingsDialog(appState, m) { agentsOf = null } }
    pairing?.let { id ->
        PairDialog(appState, machines.firstOrNull { it.id == id }, onDismiss = { pairing = null })
    }
    deleting?.let { m ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Remove ${m.name}?") },
            text = { Text("Only removes it from this phone. Chats stay on the office Mac.") },
            confirmButton = {
                TextButton(onClick = {
                    appState.deleteMachine(m.id)
                    deleting = null
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun StatusCard(appState: AppState, onPair: (String) -> Unit) {
    val connected by appState.isConnected.collectAsState()
    val health by appState.health.collectAsState()
    val error by appState.healthError.collectAsState()
    val activeId by appState.activeMachineId.collectAsState()
    val machines by appState.machines.collectAsState()
    val machine = machines.firstOrNull { it.id == activeId } ?: return
    if (machine.needsPairing == true) return NeedsPairingBanner(machine, onPair = { onPair(machine.id) })
    SettingsCard("Status") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(12.dp).background(if (connected) Color(0xFF22C55E) else Color(0xFFEF4444), CircleShape))
            Text(
                if (connected) "Connected to ${appState.activeMachine?.name}" else "Not connected",
                color = if (connected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
            )
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        health?.problems?.takeIf { it.isNotEmpty() }?.let { problems ->
            Text("Needs attention on the office Mac", style = MaterialTheme.typography.labelLarge, color = Color(0xFFD97706))
            problems.forEach { (label, detail) ->
                Row {
                    Icon(Icons.Filled.WarningAmber, null, Modifier.size(16.dp).padding(top = 2.dp), tint = Color(0xFFD97706))
                    Spacer(Modifier.width(6.dp))
                    Column {
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                        if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        health?.let { h ->
            Text(
                "Runners: " + h.runners.orEmpty().joinToString { r -> r.title + if (r.available == false) " (unavailable)" else "" }
                    .ifBlank { h.runner ?: "—" },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        OutlinedButton(onClick = appState::checkHealth) { Text("Test connection") }
    }
}

@Composable
private fun NotificationsCard(appState: AppState) {
    val context = LocalContext.current
    val background by appState.backgroundWatch.collectAsState()
    // Re-read when the screen comes back from system settings.
    var allowed by remember { mutableStateOf(appState.notifier.allowed) }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) allowed = appState.notifier.allowed }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    SettingsCard("Notifications") {
        Text(
            "Get notified when the agent asks you something or a task finishes, on every office Mac in the list.",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (!allowed) {
            Text("Notifications are off for Mob.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }) { Text("Turn on notifications") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Keep watching in the background", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Keeps the connection open while Mob is closed. Android shows a silent notification while this is on.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = background, onCheckedChange = appState::setBackgroundWatch)
        }
    }
}

@Composable
private fun MachineDialog(
    initial: Machine,
    onSave: (name: String, labels: Map<String, String>) -> Unit,
    onRemoveAddress: (String) -> Unit,
    onRemove: () -> Unit,
    onPairAgain: () -> Unit,
    onQuality: (LiveQuality) -> Unit,
    onAgents: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var quality by remember { mutableStateOf(LiveQuality.of(initial.liveQuality)) }
    val labels = remember { mutableStateMapOf<String, String>().apply { initial.addresses.forEach { put(it.id, it.label.orEmpty()) } } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(initial.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, placeholder = { Text("Office Mac") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                // Each address comes from pairing; a new one needs a new link.
                Text("Addresses", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                initial.addresses.forEach { a ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            OutlinedTextField(
                                labels[a.id].orEmpty(), { labels[a.id] = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                                label = { Text(if (a.id == initial.address?.id) "${a.kind.title} · in use" else a.kind.title) },
                                placeholder = { Text(a.kind.title) },
                            )
                            Text(a.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (a.needsPairing == true) Text("Needs pairing", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                        }
                        if (initial.addresses.size > 1) IconButton(onClick = { onRemoveAddress(a.id) }) { Icon(Icons.Filled.Delete, "Remove ${a.url}") }
                    }
                }
                TextButton(onClick = onPairAgain) { Text("Add address…") }
                Text(
                    "Each address is paired on its own, so the device token never goes to an address it was not paired on. To add one, run mobd link (or mobd link --lan) on the office Mac and scan its code. Long-press the machine in the list to choose which address Mob uses.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("Agents", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text("Default agent, models, default model and effort, and permissions on this machine, shared by every device.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = onAgents) { Text("Agent settings") }
                // Applies at once (an open live view reconnects), like the switch in the live view.
                Text("Live view quality", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    LiveQuality.entries.forEachIndexed { i, q ->
                        SegmentedButton(
                            selected = quality == q,
                            onClick = { quality = q; onQuality(q) },
                            shape = SegmentedButtonDefaults.itemShape(i, LiveQuality.entries.size),
                        ) { Text(q.label) }
                    }
                }
                Text(quality.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onRemove) { Text("Remove machine", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(name.trim().ifBlank { "Office Mac" }, labels.toMap())
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
