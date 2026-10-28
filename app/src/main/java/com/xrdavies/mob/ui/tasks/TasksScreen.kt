package com.xrdavies.mob.ui.tasks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xrdavies.mob.data.Status
import com.xrdavies.mob.data.Task
import com.xrdavies.mob.data.queueReasonText
import com.xrdavies.mob.data.readable
import com.xrdavies.mob.ui.common.relativeTime
import com.xrdavies.mob.viewmodel.AppState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(appState: AppState, onOpenChat: (String) -> Unit) {
    val activeId by appState.activeMachineId.collectAsState()
    var tasks by remember(activeId) { mutableStateOf<List<Task>?>(null) }
    var error by remember(activeId) { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(activeId, refresh) {
        if (activeId == null) return@LaunchedEffect
        while (isActive) {
            try {
                tasks = appState.apiClient.api.listTasks(limit = 100).tasks.orEmpty().sortedByDescending { it.createdAt }
                error = null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.readable()
            }
            delay(if (tasks.orEmpty().any { Status.busy(it.status) }) 3000 else 15000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tasks") },
                actions = { IconButton(onClick = { refresh++ }) { Icon(Icons.Filled.Refresh, "Refresh") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            )
        }
    ) { padding ->
        val list = tasks
        when {
            activeId == null -> Centered("Add your office computer in Settings", padding)
            list == null && error != null -> Centered("Could not load tasks\n$error", padding)
            list == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> Centered("No tasks yet\nTasks you create will appear here", padding)
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(list, key = { it.id }) { task -> TaskCard(task) { onOpenChat(task.sessionId) } }
            }
        }
    }
}

@Composable
private fun Centered(text: String, padding: PaddingValues) {
    Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
fun TaskCard(task: Task, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    task.prompt.orEmpty(), style = MaterialTheme.typography.bodyLarge,
                    maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                StatusBadge(task)
            }
            Text(
                listOfNotNull(task.runner, task.model, relativeTime(task.createdAt).takeIf { it.isNotEmpty() }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            queueReasonText(task.queueReason)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            task.error?.takeIf { task.status == Status.FAILED && it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun StatusBadge(task: Task) {
    val (color, label) = when {
        task.waiting == true -> Color(0xFFF59E0B) to "Needs you"
        task.status == Status.QUEUED -> Color(0xFF6B7280) to (task.queuePosition?.let { "Queued #$it" } ?: "Queued")
        task.status == Status.RUNNING -> Color(0xFF2563EB) to "Running"
        task.status == Status.SUCCEEDED -> Color(0xFF16A34A) to "Succeeded"
        task.status == Status.FAILED -> Color(0xFFDC2626) to "Failed"
        task.status == Status.CANCELLED -> Color(0xFFEA580C) to "Cancelled"
        else -> Color(0xFF6B7280) to task.status
    }
    Surface(color = color, shape = MaterialTheme.shapes.small) {
        Text(label, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = Color.White)
    }
}
