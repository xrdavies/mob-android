package com.xrdavies.mob.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(appState: AppState) {
    val machines by appState.machines.collectAsState()
    val activeId by appState.activeMachineId.collectAsState()
    var editing by remember { mutableStateOf<Machine?>(null) }
    var deleting by remember { mutableStateOf<Machine?>(null) }

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
            StatusCard(appState)

            SettingsCard("Machines") {
                if (machines.isEmpty()) {
                    Text("No office Mac yet. Add the mobd URL of the Mac that runs the agent.", style = MaterialTheme.typography.bodyMedium)
                }
                machines.forEach { m ->
                    Row(
                        Modifier.fillMaxWidth().clickable { appState.selectMachine(m.id) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = m.id == activeId, onClick = { appState.selectMachine(m.id) })
                        Column(Modifier.weight(1f)) {
                            Text(m.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                m.mobdUrl + if (m.vncUsername.isNotBlank()) " · sign-in as ${m.vncUsername}" else "",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { editing = m }) { Icon(Icons.Filled.Edit, "Edit ${m.name}") }
                        IconButton(onClick = { deleting = m }) { Icon(Icons.Filled.Delete, "Delete ${m.name}") }
                    }
                }
                OutlinedButton(onClick = { editing = Machine(appState.newMachineId(), "", "") }) {
                    Icon(Icons.Filled.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add machine")
                }
            }

            NotificationsCard(appState)

            SettingsCard("About") {
                Text("mob-android v0.1.0")
                Text("MIT License", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    editing?.let { m ->
        MachineDialog(
            initial = m,
            isNew = machines.none { it.id == m.id },
            onSave = {
                appState.saveMachine(it)
                editing = null
            },
            onDismiss = { editing = null },
        )
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
private fun StatusCard(appState: AppState) {
    val connected by appState.isConnected.collectAsState()
    val health by appState.health.collectAsState()
    val error by appState.healthError.collectAsState()
    val activeId by appState.activeMachineId.collectAsState()
    if (activeId == null) return
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
            Text("Notifications are off for mob.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
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
                    "Keeps the connection open while mob is closed. Android shows a silent notification while this is on.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = background, onCheckedChange = appState::setBackgroundWatch)
        }
    }
}

@Composable
private fun MachineDialog(initial: Machine, isNew: Boolean, onSave: (Machine) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var url by remember { mutableStateOf(initial.mobdUrl) }
    var user by remember { mutableStateOf(initial.vncUsername) }
    var password by remember { mutableStateOf(initial.vncPassword) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Add machine" else "Edit machine") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, placeholder = { Text("Office Mac") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    url, { url = it }, label = { Text("mobd URL") }, placeholder = { Text("http://192.168.1.100:8080") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
                )
                Text("Desktop sign-in (optional)", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                OutlinedTextField(user, { user = it }, label = { Text("Mac user name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    password, { password = it }, label = { Text("Mac password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Lets the live view sign in to Screen Sharing as this user. Stored on this phone only. Without it the desktop shows snapshots.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = url.isNotBlank(), onClick = {
                onSave(initial.copy(name = name.trim().ifBlank { "Office Mac" }, mobdUrl = url.trim(), vncUsername = user.trim(), vncPassword = password))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
