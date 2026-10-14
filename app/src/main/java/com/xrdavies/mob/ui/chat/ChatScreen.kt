package com.xrdavies.mob.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DesktopMac
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xrdavies.mob.data.Message
import com.xrdavies.mob.ui.desktop.DesktopDrawer
import com.xrdavies.mob.ui.desktop.LiveViewDialog
import com.xrdavies.mob.viewmodel.AppState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(appState: AppState, onOpenDrawer: () -> Unit, onOpenSettings: () -> Unit) {
    val chat = appState.chat
    val detail by chat.detail.collectAsState()
    val currentId by chat.currentId.collectAsState()
    val loading by chat.loading.collectAsState()
    val error by chat.error.collectAsState()
    val isConnected by appState.isConnected.collectAsState()
    val showDesktop by appState.showDesktopDrawer.collectAsState()
    val machines by appState.machines.collectAsState()
    val activeId by appState.activeMachineId.collectAsState()
    val machine = machines.firstOrNull { it.id == activeId }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(error) {
        error?.let {
            snackbar.showSnackbar(it)
            chat.clearError()
        }
    }

    val messages = detail?.session?.messages.orEmpty()
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    detail?.questions?.firstOrNull { it.isPending }?.let { q ->
        QuestionDialog(q, onAnswer = { answer, approved -> chat.answer(q, answer, approved) }, onDismiss = {})
    }
    LiveViewDialog(appState)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onOpenDrawer) { Icon(Icons.Filled.Menu, "Chats") } },
                title = {
                    Column {
                        Text(
                            if (currentId == null) "New chat" else detail?.session?.displayTitle ?: "…",
                            maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(7.dp).background(if (isConnected) Color(0xFF22C55E) else Color(0xFFEF4444), CircleShape))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                machine?.name ?: "No office Mac", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                actions = {
                    IconButton(onClick = { chat.open(null) }, enabled = machine != null && currentId != null) {
                        Icon(Icons.Filled.EditNote, "New chat")
                    }
                    IconButton(onClick = appState::toggleDesktopDrawer, enabled = machine != null) {
                        Icon(
                            Icons.Filled.DesktopMac,
                            if (showDesktop) "Hide desktop" else "Show desktop",
                            tint = if (showDesktop) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            AnimatedVisibility(visible = showDesktop && machine != null) { DesktopDrawer(appState) }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    machine == null -> EmptyState("Add your office Mac to start", "Settings → Machines → Add machine") {
                        Button(onClick = onOpenSettings) { Text("Open Settings") }
                    }
                    currentId == null -> EmptyState("New chat on ${machine.name}", "Ask the agent to do something on the office Mac")
                    detail == null && loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(messages, key = { it.id }) { MessageBubble(it) }
                    }
                }
            }

            if (machine != null) Composer(appState)
        }
    }
}

@Composable
private fun EmptyState(title: String, hint: String, action: (@Composable () -> Unit)? = null) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}

@Composable
fun MessageBubble(message: Message) {
    val assistant = message.role == "assistant"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (assistant) Arrangement.Start else Arrangement.End) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (assistant) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primary,
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Text(
                text = message.content,
                modifier = Modifier.padding(12.dp),
                color = if (assistant) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}
