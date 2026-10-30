package com.xrdavies.mob.ui.files

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xrdavies.mob.data.Health
import com.xrdavies.mob.data.Machine
import com.xrdavies.mob.viewmodel.AppState

/** Files works with a paired machine whose mobd supports transfers. needsPairing is null once paired. */
fun filesEnabled(health: Health?, machine: Machine?): Boolean =
    health?.supports("fileTransfers") == true && machine != null && machine.needsPairing != true

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(app: AppState) {
    val activeId by app.activeMachineId.collectAsState()
    val health by app.health.collectAsState()
    val listing by app.files.listing.collectAsState()
    val tasks by app.files.tasks.collectAsState()
    val error by app.files.error.collectAsState()
    val preparing by app.files.preparing.collectAsState()
    val base = app.apiClient.baseUrl.toString()
    val enabled = filesEnabled(health, app.activeMachine)
    val visible = tasks.filter { it.machine == activeId && it.base == base }
    val context = LocalContext.current
    var exporting by rememberSaveable { mutableStateOf<String?>(null) }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(app.files::importFile) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val task = tasks.firstOrNull { it.id == exporting }
        if (uri != null && task != null) app.files.export(task, uri)
        exporting = null
    }
    LaunchedEffect(activeId, base, enabled) { if (enabled) app.files.refresh() }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Files") }, actions = {
            IconButton(onClick = app.files::refresh, enabled = enabled) { Icon(Icons.Default.Refresh, "Refresh files") }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(app.activeMachine?.name ?: "Add your office computer in Settings", style = MaterialTheme.typography.titleMedium)
                if (activeId != null && !enabled) Text("Update mobd to a version with file transfers, then reconnect.")
            }
            listing?.let { data -> item {
                SelectionContainer { Text(data.directory, style = MaterialTheme.typography.bodySmall) }
                Text("Ask your agent to copy files from other directories here to download them.", style = MaterialTheme.typography.bodySmall)
            } }
            if (enabled) {
                item {
                    Button(onClick = { open.launch(arrayOf("*/*")) }, enabled = !preparing) { Text(if (preparing) "Preparing upload…" else "Upload file") }
                    Text("Transfers pause in the background. Return here and tap Continue.", style = MaterialTheme.typography.bodySmall)
                }
                items(listing?.files.orEmpty(), key = { "remote:${it.name}" }) { file ->
                    ListItem(headlineContent = { Text(file.name) }, supportingContent = { Text(Formatter.formatFileSize(context, file.size)) }, trailingContent = {
                        IconButton(onClick = { app.files.download(file) }) { Icon(Icons.Default.Download, "Download ${file.name}") }
                    })
                }
                if (listing?.files?.isEmpty() == true) item { Text("No files yet") }
            }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            if (visible.isNotEmpty()) item { Text("Transfers", style = MaterialTheme.typography.titleMedium) }
            items(visible, key = { it.id }) { task ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${if (task.upload) "↑" else "↓"} ${task.name}")
                        Text("${Formatter.formatFileSize(context, task.bytes)} / ${Formatter.formatFileSize(context, task.size)} · ${if (task.state == "ready") "Ready to save" else task.state}", style = MaterialTheme.typography.bodySmall)
                        if (task.state == "running") LinearProgressIndicator(progress = { (task.bytes.toDouble() / maxOf(1, task.size)).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        task.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (task.state == "failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (task.state == "running") TextButton(onClick = { app.files.pause(task.id) }) { Text("Pause") }
                            if (task.state in listOf("paused", "failed")) TextButton(onClick = { app.files.start(task.id) }, enabled = enabled) { Text(if (task.state == "failed") "Retry" else "Continue") }
                            if (task.state == "ready") TextButton(onClick = { exporting = task.id; save.launch(task.name) }) { Text("Save…") }
                            TextButton(onClick = { app.files.cancel(task) }) { Text(if (task.state == "done") "Dismiss" else "Cancel") }
                        }
                    }
                }
            }
        }
    }
}
