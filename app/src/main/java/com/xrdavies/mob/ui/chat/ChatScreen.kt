package com.xrdavies.mob.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DesktopMac
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.xrdavies.mob.data.TimelineItem
import com.xrdavies.mob.data.buildTimeline
import com.xrdavies.mob.data.byMemory
import com.xrdavies.mob.data.bytes
import com.xrdavies.mob.data.resourceWarning
import com.xrdavies.mob.ui.desktop.DesktopDrawer
import com.xrdavies.mob.ui.desktop.LiveViewDialog
import com.xrdavies.mob.ui.settings.NeedsPairingBanner
import com.xrdavies.mob.ui.settings.PairDialog
import com.xrdavies.mob.viewmodel.AppState
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(appState: AppState, onOpenDrawer: (() -> Unit)?, onOpenSettings: () -> Unit) {
    val chat = appState.chat
    val detail by chat.detail.collectAsState()
    val currentId by chat.currentId.collectAsState()
    val loading by chat.loading.collectAsState()
    val error by chat.error.collectAsState()
    val isConnected by appState.isConnected.collectAsState()
    val health by appState.health.collectAsState()
    val queued by appState.queued.collectAsState()
    val waitingCount by appState.waitingCount.collectAsState()
    val showDesktop by appState.showDesktopDrawer.collectAsState()
    val machines by appState.machines.collectAsState()
    val activeId by appState.activeMachineId.collectAsState()
    val machine = machines.firstOrNull { it.id == activeId }
    var pairing by rememberSaveable { mutableStateOf(false) }
    if (pairing && machine != null) PairDialog(appState, machine, onDismiss = { pairing = false })

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(error) {
        error?.let {
            snackbar.showSnackbar(it)
            chat.clearError()
        }
    }

    val items = remember(detail) {
        detail?.let { buildTimeline(it.tasks, it.session.messages.orEmpty(), it.activities, it.questions) }.orEmpty()
    }
    val lastFooter = items.lastOrNull { it is TimelineItem.Footer }?.id
    val listState = rememberLazyListState()
    // Follow new output while the user is at the bottom; jump there when a chat opens.
    val atBottom by remember { derivedStateOf { !listState.canScrollForward } }
    var followed by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(items.size, items.lastOrNull(), currentId) {
        if (items.isEmpty()) return@LaunchedEffect
        if (followed != currentId) {
            listState.scrollToItem(items.size - 1)
            followed = currentId
        } else if (atBottom) listState.animateScrollToItem(items.size - 1)
    }
    // A new question always comes into view, even if it arrived while the app was in the background.
    val pending = items.indexOfLast { it is TimelineItem.Ask && it.question.isPending }
    val pendingId = items.getOrNull(pending)?.id
    LaunchedEffect(pendingId) {
        if (pendingId != null) listState.animateScrollToItem(items.size - 1)
    }
    LiveViewDialog(appState)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    // No drawer button when the chat list stays beside the chat (tablets).
                    if (onOpenDrawer != null) IconButton(onClick = onOpenDrawer) {
                        BadgedBox(badge = { if (waitingCount > 0) Badge { Text("$waitingCount") } }) { Icon(Icons.Filled.Menu, "Chats") }
                    }
                },
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
                            val waiting = queued[machine?.id] ?: 0
                            if (waiting > 0) {
                                Text(" · queue $waiting", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                            val problems = health?.problems.orEmpty()
                            if (isConnected && problems.isNotEmpty()) {
                                Text(
                                    " · ${problems.size} setup issue" + if (problems.size > 1) "s" else "",
                                    style = MaterialTheme.typography.bodySmall, color = Color(0xFFD97706), maxLines = 1,
                                    modifier = Modifier.clickable(onClick = onOpenSettings),
                                )
                            }
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
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            // Wide screens show the desktop in a column beside the chat, like the desktop app.
            val side = maxWidth >= 720.dp
            val paneWidth = min(maxWidth * 0.4f, 520.dp)
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (machine?.needsPairing == true) {
                        NeedsPairingBanner(machine, onPair = { pairing = true }, Modifier.widthIn(max = 840.dp).padding(8.dp))
                    }
                    ResourceBanner(appState, Modifier.widthIn(max = 840.dp).padding(8.dp))
                    AnimatedVisibility(visible = !side && showDesktop && machine != null) { DesktopDrawer(appState) }

                    Box(Modifier.weight(1f).widthIn(max = 840.dp).fillMaxWidth()) {
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
                                items(items, key = { it.id }) { TimelineRow(it, appState, detail?.session?.runner, it.id == lastFooter) }
                            }
                        }
                    }

                    if (machine != null) Box(Modifier.widthIn(max = 840.dp)) { Composer(appState) }
                }
                if (side && showDesktop && machine != null) {
                    VerticalDivider()
                    DesktopDrawer(appState, Modifier.width(paneWidth), maxHeight = Dp.Unspecified)
                }
            }
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
private fun ResourceBanner(appState: AppState, modifier: Modifier = Modifier) {
    val desktops = appState.desktops
    if (!desktops.enabled) return
    val memory by desktops.memory.collectAsState()
    val disk by desktops.disk.collectAsState()
    val dismissed by desktops.dismissed.collectAsState()
    val message by desktops.message.collectAsState()
    val warning = resourceWarning(memory, disk)
    if (warning == null || dismissed == warning.key) return
    var showDesktops by remember { mutableStateOf(false) }
    LaunchedEffect(message) {
        message?.let {
            // A close/seed result arrived; hide the list and let the user see the message.
            showDesktops = false
            desktops.clearMessage()
        }
    }
    // Refresh while shown so the list stays current.
    LaunchedEffect(warning.key) {
        while (true) {
            delay(15_000)
            desktops.refresh()
        }
    }
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF3C7))) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Icon(Icons.Filled.Warning, null, Modifier.size(20.dp).padding(top = 2.dp), tint = Color(0xFFD97706))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    warning.lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                IconButton(onClick = { desktops.dismiss(warning.key) }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Close, "Dismiss", Modifier.size(18.dp))
                }
            }
            if (showDesktops) {
                val list by desktops.desktops.collectAsState()
                list.byMemory().forEach { d ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.5f)).padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(d.title ?: d.sessionId, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(bytes(d.memoryBytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { appState.desktops.close(d.sessionId) }) { Text("Close desktop") }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (!showDesktops) TextButton(onClick = { showDesktops = true }) { Text("Show desktops") }
            }
        }
    }
}
