package com.xrdavies.mob.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xrdavies.mob.data.ApiClient
import com.xrdavies.mob.data.ConfigChange
import com.xrdavies.mob.data.ConfigView
import com.xrdavies.mob.data.ErrorCodeBody
import com.xrdavies.mob.data.Machine
import com.xrdavies.mob.data.Maintenance
import com.xrdavies.mob.data.MaintenanceOp
import com.xrdavies.mob.data.MaintenanceRequest
import com.xrdavies.mob.data.ReleaseCheck
import com.xrdavies.mob.data.orThrow
import com.xrdavies.mob.data.readable
import com.xrdavies.mob.viewmodel.AppState
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import retrofit2.HttpException

/**
 * One computer's mobd from afar (mobd docs/remote-maintenance.md): its config, running tasks and the
 * restart or update in progress. Shared by the mobd dialog and the Agents dialog.
 */
class MaintenanceModel(val client: ApiClient) {
    /** null until loaded; false for a mobd without remote maintenance. */
    var supported by mutableStateOf<Boolean?>(null); private set
    var config by mutableStateOf<ConfigView?>(null); private set
    var tasks by mutableIntStateOf(0); private set
    var op by mutableStateOf<MaintenanceOp?>(null); private set
    var error by mutableStateOf<String?>(null)

    val busy get() = op?.running == true

    suspend fun load() {
        try {
            supported = client.api.getHealth().supports("maintenance")
            if (supported != true) return
            coroutineScope {
                val q = async { runCatching { client.api.queue() }.getOrNull() }
                config = client.api.config()
                tasks = Maintenance.busy(q.await())
            }
        } catch (e: Exception) {
            if (supported == null) error = e.readable()
        }
    }

    /** Saves editable keys; true when saved. */
    suspend fun save(changes: Map<String, Any?>): Boolean {
        val c = config ?: return false
        error = null
        try {
            val res = client.api.saveConfig(ConfigChange(c.rev, changes))
            if (res.code() == 409) {
                load()
                error = "Another device changed this first. It is reloaded; make your change again."
                return false
            }
            res.orThrow()
            load()
            return true
        } catch (e: Exception) {
            error = e.message ?: e.readable()
        }
        return false
    }

    /** Starts a restart or upgrade and follows it to the end; mobd is unreachable for a moment meanwhile. */
    suspend fun run(action: String, version: String? = null) {
        error = null
        try {
            val id = client.api.startMaintenance(MaintenanceRequest(action, version)).operationId
            var last = MaintenanceOp(id, action, "running", step = "preparing")
            op = last
            var i = 0
            while (last.running && i++ < 400) {
                delay(1500)
                // Restarting: mobd does not answer for a few seconds.
                runCatching { client.api.operation(id) }.getOrNull()?.let { last = it; op = it }
            }
        } catch (e: HttpException) {
            val code = e.response()?.errorBody()?.string()?.let { runCatching { ApiClient.gson.fromJson(it, ErrorCodeBody::class.java)?.code }.getOrNull() }
            error = if (code == "tasks_running") "Tasks are running; try again when they finish." else e.readable()
        } catch (e: Exception) {
            error = e.readable()
        }
        load()
    }
}

/** The last operation's progress or result, colored by outcome. */
@Composable
fun OperationLine(op: MaintenanceOp) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (op.running) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(
            Maintenance.text(op), style = MaterialTheme.typography.bodyMedium,
            color = when {
                op.failed -> MaterialTheme.colorScheme.error
                op.running -> MaterialTheme.colorScheme.onSurface
                else -> MaterialTheme.colorScheme.primary
            },
        )
    }
}

/** "Restart to apply" for config changes waiting for a restart, and how the last restart went. */
@Composable
fun RestartCard(model: MaintenanceModel, name: String) {
    val config = model.config ?: return
    val pending = config.pendingRestart == true
    if (!pending && model.op == null) return
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf(false) }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            model.op?.let { if (it.running || !pending) OperationLine(it) }
            if (pending) {
                Text(
                    "Changes apply when mobd on $name restarts${if (model.tasks > 0) " · ${Maintenance.tasksText(model.tasks)}" else "."}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (config.canRestart != false) {
                    Button(onClick = { confirming = true }, enabled = !model.busy && model.tasks == 0) { Text("Restart to apply") }
                }
            }
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Restart mobd on $name?") },
            text = { Text("It is unreachable for a few seconds. If it does not come back with the new settings, it goes back to the last ones that worked.") },
            confirmButton = { TextButton(onClick = { confirming = false; scope.launch { model.run("restart") } }) { Text("Restart") } },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
}

/** mobd on one computer: version and Update, Restart, and what the last restart refused. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaintenanceDialog(appState: AppState, machine: Machine, onClose: () -> Unit) {
    val model = remember(machine) { MaintenanceModel(appState.clientFor(machine)) }
    val scope = rememberCoroutineScope()
    var release by remember { mutableStateOf<ReleaseCheck?>(null) }
    var checking by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }

    suspend fun check() {
        if (model.supported != true) return
        checking = true
        release = runCatching { model.client.api.latestRelease() }.getOrNull()
        checking = false
    }
    LaunchedEffect(machine.id) {
        if (machine.needsPairing == true) return@LaunchedEffect
        model.load()
        check()
    }

    confirm?.let { action ->
        val upgrade = action == "upgrade"
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (upgrade) "Update mobd on ${machine.name}?" else "Restart mobd on ${machine.name}?") },
            text = {
                Text(
                    if (upgrade) "mobd downloads and checks the new version, then restarts on it. If it does not start, the previous version runs again."
                    else "It is unreachable for a few seconds.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    scope.launch {
                        model.run(action, if (upgrade) release?.latest else null)
                        if (upgrade) check()
                        appState.checkHealth()
                    }
                }) { Text(if (upgrade) "Update to ${release?.latest.orEmpty()}" else "Restart") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("${machine.name} · mobd") },
                    navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                )
            }
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val muted = MaterialTheme.colorScheme.onSurfaceVariant
                when {
                    machine.needsPairing == true -> Text("Pair ${machine.name} again to manage its mobd.", color = muted)
                    model.supported == false -> Text("Update mobd on ${machine.name} to restart or update it from here: run mobd upgrade there, or the install command again.", color = muted)
                    model.supported == null -> model.error?.let { Text(it, color = MaterialTheme.colorScheme.error) } ?: CircularProgressIndicator()
                    else -> {
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Version", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                Text("mobd ${release?.current ?: "…"}", style = MaterialTheme.typography.bodyLarge)
                                val r = release
                                when {
                                    r != null && r.newer && r.canUpgrade -> {
                                        Button(onClick = { confirm = "upgrade" }, enabled = !model.busy && model.tasks == 0) { Text("Update to ${r.latest}") }
                                        if (model.tasks > 0) Text("Updates once no task runs · ${Maintenance.tasksText(model.tasks)}", style = MaterialTheme.typography.bodySmall, color = muted)
                                    }
                                    r != null && r.newer -> Text("${r.latest} available. ${r.reason.orEmpty()}", style = MaterialTheme.typography.bodySmall, color = muted)
                                    r != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Filled.Check, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                        Spacer(Modifier.width(4.dp))
                                        Text("Up to date", color = MaterialTheme.colorScheme.primary)
                                    }
                                    checking -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                }
                            }
                        }
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Service", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                model.op?.let { OperationLine(it) }
                                OutlinedButton(onClick = { confirm = "restart" }, enabled = !model.busy && model.tasks == 0 && model.config?.canRestart != false) { Text("Restart mobd") }
                                when {
                                    model.config?.canRestart == false -> Text("mobd runs in a terminal on ${machine.name}; restart it there.", style = MaterialTheme.typography.bodySmall, color = muted)
                                    model.tasks > 0 -> Text("Possible once no task runs · ${Maintenance.tasksText(model.tasks)}", style = MaterialTheme.typography.bodySmall, color = muted)
                                }
                                if (model.config?.pendingRestart == true) Text("Changed settings apply after a restart.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                            }
                        }
                        model.config?.lastRejected?.let {
                            Text("Last change refused: ${it.reason}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        model.config?.configError?.let {
                            Text("config.json: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        model.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
}
