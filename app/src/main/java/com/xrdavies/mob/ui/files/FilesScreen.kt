package com.xrdavies.mob.ui.files

import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xrdavies.mob.data.FileTransfer
import com.xrdavies.mob.data.Health
import com.xrdavies.mob.data.Machine
import com.xrdavies.mob.data.joinRemote
import com.xrdavies.mob.viewmodel.AppState
import kotlinx.coroutines.delay

/** Files works with a paired machine whose mobd supports transfers. needsPairing is null once paired. */
fun filesEnabled(health: Health?, machine: Machine?): Boolean =
    health?.supports("fileTransfers") == true && machine != null && machine.needsPairing != true

private data class Entry(val name: String, val folder: Boolean)

/**
 * The office computer's shared folder as one list, like the desktop's left pane. Folders open
 * (never above the shared folder); tapping a file downloads it into Downloads. Uploads go into the
 * open folder. Whole folders are not transferred on the phone.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FilesScreen(app: AppState) {
    val activeId by app.activeMachineId.collectAsState()
    val health by app.health.collectAsState()
    val listing by app.files.listing.collectAsState()
    val path by app.files.path.collectAsState()
    val tasks by app.files.tasks.collectAsState()
    val error by app.files.error.collectAsState()
    val preparing by app.files.preparing.collectAsState()
    val base = app.apiClient.baseUrl.toString()
    val enabled = filesEnabled(health, app.activeMachine)
    val manage = health?.supports("fileFolders") == true
    val mine = tasks.filter { it.machine == activeId && it.base == base }
    val context = LocalContext.current
    var renaming by remember { mutableStateOf<Entry?>(null) }
    var deleting by remember { mutableStateOf<Entry?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> app.files.importFiles(uris) }
    LaunchedEffect(activeId, base, enabled) { if (enabled) app.files.refresh() }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Files") }, actions = {
            IconButton(onClick = app.files::refresh, enabled = enabled) { Icon(Icons.Default.Refresh, "Refresh files") }
            IconButton(onClick = { pick.launch(arrayOf("*/*")) }, enabled = enabled && !preparing) { Icon(Icons.Default.Add, "Upload files") }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(vertical = 8.dp)) {
            when {
                activeId == null -> item { Hint("Add your office computer in Settings") }
                !enabled -> item { Hint("Update mobd to a version with file transfers, then reconnect.") }
                else -> {
                    transfers(mine, app)
                    item { Breadcrumb(listing?.directory, path, app.files::open) }
                    val data = listing
                    if (data == null) item { Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) { CircularProgressIndicator() } }
                    else {
                        items(data.folders.orEmpty(), key = { "d:${it.name}" }) { f ->
                            EntryRow(Entry(f.name, true), manage, onOpen = { app.files.open(joinRemote(path, f.name)) },
                                onRename = { renaming = it }, onDelete = { deleting = it }) {
                                Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary)
                                Text(f.name, Modifier.weight(1f).padding(start = 16.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline)
                            }
                        }
                        items(data.files, key = { "f:${it.name}" }) { f ->
                            EntryRow(Entry(f.name, false), manage, onOpen = { app.files.download(f) }, onDownload = { app.files.download(f) },
                                onRename = { renaming = it }, onDelete = { deleting = it }) {
                                Text(f.name, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(Formatter.formatFileSize(context, f.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (data.files.isEmpty() && data.folders.isNullOrEmpty()) item {
                            Hint(if (path.isEmpty()) "No files yet. Tap + to upload, or ask your agent to copy files here." else "This folder is empty.")
                        }
                    }
                }
            }
            error?.let { item { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) } }
        }
    }
    renaming?.let { e ->
        // The name is selected without its extension, as in Finder and on the desktop.
        val stem = if (e.folder) e.name.length else e.name.lastIndexOf('.').takeIf { it > 0 } ?: e.name.length
        var field by remember(e) { mutableStateOf(TextFieldValue(e.name, TextRange(0, stem))) }
        val focus = remember { FocusRequester() }
        LaunchedEffect(e) { focus.requestFocus() }
        val name = field.text.trim()
        AlertDialog(onDismissRequest = { renaming = null }, title = { Text(if (e.folder) "Rename folder" else "Rename file") },
            text = { OutlinedTextField(field, { field = it }, Modifier.focusRequester(focus), singleLine = true, label = { Text("Name") }) },
            confirmButton = { TextButton(onClick = { app.files.rename(e.name, name); renaming = null }, enabled = name.isNotEmpty() && name != e.name) { Text("Rename") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } })
    }
    deleting?.let { e ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete ${e.name}${if (e.folder) " and everything in it" else ""}?") },
            text = { Text("It is removed from the office computer and cannot be restored.") },
            confirmButton = { TextButton(onClick = { app.files.delete(e.name); deleting = null }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } })
    }
}

@Composable
private fun Hint(text: String) = Text(text, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Breadcrumb(directory: String?, path: String, open: (String) -> Unit) {
    val parts = path.split('/').filter { it.isNotEmpty() }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { open("") }) { Text(directory?.substringAfterLast('/') ?: "Shared folder", color = if (parts.isEmpty()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant) }
        parts.forEachIndexed { i, part ->
            Text("›", color = MaterialTheme.colorScheme.outline)
            TextButton(onClick = { open(parts.take(i + 1).joinToString("/")) }) {
                Text(part, color = if (i == parts.lastIndex) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A folder or file: tap to open or download; long-press or ⋮ for the actions. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(entry: Entry, manage: Boolean, onOpen: () -> Unit, onDownload: (() -> Unit)? = null,
                onRename: (Entry) -> Unit, onDelete: (Entry) -> Unit, content: @Composable RowScope.() -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val actions = manage || onDownload != null
    Row(Modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = { if (actions) menu = true },
        onClickLabel = if (entry.folder) "Open" else "Download").padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        content()
        if (actions) Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Actions for ${entry.name}") }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                onDownload?.let { d -> DropdownMenuItem(text = { Text("Download") }, leadingIcon = { Icon(Icons.Default.Download, null) }, onClick = { menu = false; d() }) }
                if (manage) {
                    DropdownMenuItem(text = { Text("Rename…") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { menu = false; onRename(entry) })
                    DropdownMenuItem(text = { Text("Delete…", color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onDelete(entry) })
                }
            }
        }
    }
}

/** Active transfers, then the most recent finished one: an upload fades, a download stays with Open. */
private fun androidx.compose.foundation.lazy.LazyListScope.transfers(mine: List<FileTransfer>, app: AppState) {
    val active = mine.filter { it.state != "done" }
    val recent = mine.filter { it.state == "done" }.maxByOrNull { it.finishedAt ?: 0 }
    items(active, key = { "t:${it.id}" }) { TransferRow(it, app) }
    recent?.let { r -> item(key = "done:${r.id}") {
        val context = LocalContext.current
        LaunchedEffect(r.id) {
            mine.filter { it.state == "done" && it.id != r.id }.forEach { app.files.dismiss(it.id) }
            if (r.upload) { delay(4000); app.files.dismiss(r.id) }
        }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
            Text("${if (r.upload) "Uploaded" else "Downloaded"} ${r.name}", Modifier.weight(1f).padding(start = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            r.localUri?.takeIf { it.startsWith("content://") }?.let { uri ->
                TextButton(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                        .onFailure { app.files.report(IllegalStateException("No app can open ${r.name}")) }
                }) { Text("Open") }
            }
            IconButton(onClick = { app.files.dismiss(r.id) }) { Icon(Icons.Default.Close, "Dismiss") }
        }
    } }
    if (active.isNotEmpty()) item { Hint("Transfers pause when Mob is in the background; tap Continue when you're back.") }
    if (active.isNotEmpty() || recent != null) item { HorizontalDivider(Modifier.padding(vertical = 4.dp)) }
}

@Composable
private fun TransferRow(task: FileTransfer, app: AppState) {
    val context = LocalContext.current
    val sizes = "${Formatter.formatFileSize(context, task.bytes)} of ${Formatter.formatFileSize(context, task.size)}"
    val status = when (task.state) {
        "failed" -> task.message ?: "Failed"
        "queued" -> "Waiting · $sizes"
        "paused" -> "Paused · $sizes"
        else -> task.message ?: sizes
    }
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (task.upload) Icons.Default.Upload else Icons.Default.Download, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(task.name, Modifier.weight(1f).padding(start = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (task.state == "running" || task.state == "queued") IconButton(onClick = { app.files.pause(task.id) }) { Icon(Icons.Default.Pause, "Pause") }
            else IconButton(onClick = { app.files.start(task.id) }) { Icon(if (task.state == "failed") Icons.Default.Refresh else Icons.Default.PlayArrow, if (task.state == "failed") "Retry" else "Continue") }
            IconButton(onClick = { app.files.cancel(task) }) { Icon(Icons.Default.Close, "Cancel") }
        }
        LinearProgressIndicator(progress = { (task.bytes.toDouble() / maxOf(1, task.size)).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(end = 12.dp))
        Text(status, style = MaterialTheme.typography.bodySmall, maxLines = 2,
            color = if (task.state == "failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
