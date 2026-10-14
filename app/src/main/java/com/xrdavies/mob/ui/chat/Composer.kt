package com.xrdavies.mob.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xrdavies.mob.data.Status
import com.xrdavies.mob.data.readable
import com.xrdavies.mob.data.uploadImage
import com.xrdavies.mob.ui.common.duration
import com.xrdavies.mob.viewmodel.AppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_ATTACHMENTS = 10

private class Attachment(val key: Int, val uri: Uri) {
    var preview by mutableStateOf<Bitmap?>(null)
    var id by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
}

/**
 * Prompt box with images, runner / model / effort, Stop, and adding to a running turn.
 * Like the desktop app: one turn at a time per chat; while a turn runs you can draft the
 * next message, or send it into the turn when the runner supports steering.
 */
@Composable
fun Composer(appState: AppState) {
    val chat = appState.chat
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val health by appState.health.collectAsState()
    val connected by appState.isConnected.collectAsState()
    val prefs by appState.turnPrefs.collectAsState()
    val machineId by appState.activeMachineId.collectAsState()
    val detail by chat.detail.collectAsState()
    val currentId by chat.currentId.collectAsState()
    val sending by chat.sending.collectAsState()
    val cancelling by chat.cancelling.collectAsState()

    var text by remember { mutableStateOf("") }
    val attachments = remember { mutableStateListOf<Attachment>() }
    var nextKey by remember { mutableIntStateOf(0) }

    val h = health
    val m = machineId.orEmpty()
    val available = h?.runners.orEmpty().filter { it.available != false }
    // A conversation keeps its runner; a new chat uses the picked one while it is available.
    val picked = if (currentId != null) detail?.session?.runner else available.firstOrNull { it.name == prefs["turn.$m.runner"] }?.name
    val runner = h?.runner(picked)
    val runnerKey = "$m/${runner?.name ?: h?.runner.orEmpty()}"
    val models = runner?.models ?: h?.models.orEmpty()
    val efforts = runner?.efforts ?: h?.efforts.orEmpty()
    val canOptions = h?.supports("taskOptions") == true
    val model = prefs["turn.$runnerKey.model"]?.takeIf { models.isEmpty() || it in models }
    val effort = prefs["turn.$runnerKey.effort"]?.takeIf { it in efforts }
    val canUpload = h?.supports("uploads") == true && runner?.images != false

    val task = detail?.current
    val busy = detail?.busy == true
    val canSteer = task?.status == Status.RUNNING && runner?.steer == true && h?.supports("steer") == true && !cancelling
    val uploading = attachments.any { it.id == null && it.error == null }
    val ready = attachments.mapNotNull { it.id }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_ATTACHMENTS)) { uris ->
        uris.take(MAX_ATTACHMENTS - attachments.size).forEach { uri ->
            val a = Attachment(nextKey++, uri)
            attachments += a
            scope.launch { a.preview = thumbnail(context, uri) }
            scope.launch {
                try {
                    a.id = appState.apiClient.uploadImage(context, uri).id
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    a.error = e.readable()
                }
            }
        }
    }

    fun send() {
        val prompt = text.trim()
        if (prompt.isEmpty()) return
        if (canSteer) {
            chat.steer(prompt) { ok -> if (ok && text.trim() == prompt) text = "" }
            return
        }
        if (busy || uploading || sending) return
        chat.send(
            prompt,
            runner = if (currentId == null) runner?.name else null,
            model = model.takeIf { canOptions },
            effort = effort.takeIf { canOptions },
            attachments = ready,
        ) { ok ->
            if (!ok) return@send
            if (text.trim() == prompt) text = ""
            attachments.clear()
        }
    }

    Surface(tonalElevation = 2.dp, shadowElevation = 4.dp, shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (task != null && Status.busy(task.status)) TurnStatus(task.status, task.queuePosition, task.waiting == true, cancelling, task.startedAt)

            if (attachments.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    items(attachments, key = { it.key }) { a -> AttachmentChip(a) { attachments.remove(a) } }
                }
            }

            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = {
                    Text(
                        when {
                            canSteer -> "Add to what the agent is doing…"
                            busy -> "The agent is working… you can draft the next message"
                            else -> "Ask mob to do something on ${appState.activeMachine?.name ?: "the office Mac"}…"
                        },
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp, max = 160.dp),
                enabled = connected,
                maxLines = 6,
                shape = RoundedCornerShape(12.dp),
            )

            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    enabled = connected && canUpload && attachments.size < MAX_ATTACHMENTS,
                ) { Icon(Icons.Filled.AddPhotoAlternate, "Attach images") }

                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (currentId == null && available.size > 1) {
                        OptionChip(Icons.Filled.SmartToy, runner?.title ?: "Agent", "Agent", available.map { it.name to it.title }, runner?.name) {
                            appState.setTurnPref("$m.runner", it)
                        }
                    } else if (currentId != null && runner != null && h?.runners.orEmpty().size > 1) {
                        Text(runner.title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 6.dp))
                    }
                    if (canOptions && models.isNotEmpty()) {
                        OptionChip(Icons.Filled.Memory, model ?: "Model", "Default model", models.map { it to it }, model) {
                            appState.setTurnPref("$runnerKey.model", it)
                        }
                    }
                    if (canOptions && efforts.isNotEmpty()) {
                        OptionChip(Icons.Filled.Psychology, effort ?: "Effort", "Default effort", efforts.map { it to it }, effort) {
                            appState.setTurnPref("$runnerKey.effort", it)
                        }
                    }
                }

                if (busy) {
                    if (canSteer && text.isNotBlank()) {
                        FilledTonalIconButton(onClick = ::send, enabled = !sending) { Icon(Icons.AutoMirrored.Filled.Send, "Send to running turn") }
                    }
                    FilledIconButton(
                        onClick = chat::cancel,
                        enabled = !cancelling,
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) {
                        if (cancelling) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onError)
                        else Icon(Icons.Filled.Stop, "Stop")
                    }
                } else {
                    FilledIconButton(
                        onClick = ::send,
                        enabled = connected && text.isNotBlank() && !sending && !uploading,
                    ) {
                        if (sending) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(Icons.AutoMirrored.Filled.Send, "Send")
                    }
                }
            }
        }
    }
}

@Composable
private fun TurnStatus(status: String, queuePosition: Int?, waiting: Boolean, cancelling: Boolean, startedAt: String?) {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(status) {
        while (true) {
            delay(1000)
            tick++
        }
    }
    val (color, label) = when {
        cancelling -> MaterialTheme.colorScheme.error to "Stopping…"
        status == Status.QUEUED -> MaterialTheme.colorScheme.onSurfaceVariant to ("Queued" + (queuePosition?.let { " #$it" } ?: "") + " · waits for the desktop")
        waiting -> Color(0xFFD97706) to "Waiting for your answer"
        // Reading tick recomposes this line every second.
        else -> MaterialTheme.colorScheme.primary to "Working… ${tick.let { duration(startedAt, null) }}"
    }
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (status == Status.RUNNING && !waiting && !cancelling) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
        else Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
private fun OptionChip(
    icon: ImageVector,
    label: String,
    defaultLabel: String,
    options: List<Pair<String, String>>,
    value: String?,
    onChange: (String?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { open = true },
            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 96.dp)) },
            leadingIcon = { Icon(icon, null, Modifier.size(16.dp)) },
            modifier = Modifier.height(32.dp),
        )
        DropdownMenu(open, onDismissRequest = { open = false }) {
            if (defaultLabel != "Agent") {
                DropdownMenuItem(
                    text = { Text(defaultLabel) },
                    leadingIcon = { if (value == null) Icon(Icons.Filled.Check, null) else Spacer(Modifier.size(24.dp)) },
                    onClick = {
                        open = false
                        onChange(null)
                    },
                )
            }
            options.forEach { (v, title) ->
                DropdownMenuItem(
                    text = { Text(title) },
                    leadingIcon = { if (v == value) Icon(Icons.Filled.Check, null) else Spacer(Modifier.size(24.dp)) },
                    onClick = {
                        open = false
                        onChange(v)
                    },
                )
            }
        }
    }
}

@Composable
private fun AttachmentChip(a: Attachment, onRemove: () -> Unit) {
    Box(Modifier.size(64.dp)) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, if (a.error != null) MaterialTheme.colorScheme.error else Color.Transparent, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            a.preview?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            when {
                a.error != null -> Icon(Icons.Filled.ErrorOutline, a.error, tint = MaterialTheme.colorScheme.error)
                a.id == null -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
        Icon(
            Icons.Filled.Close, "Remove image", tint = Color.White,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(2.dp)
                .size(20.dp)
                .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                .clip(CircleShape)
                .padding(2.dp),
        )
        // A transparent button over the close icon keeps the touch target reachable.
        IconButton(onClick = onRemove, modifier = Modifier.align(Alignment.TopEnd).size(28.dp)) {}
    }
}

private suspend fun thumbnail(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 192) sample *= 2
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
    }.getOrNull()
}
