package com.xrdavies.mob.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xrdavies.mob.viewmodel.Log

/** Settings → Log: the in-memory debug log, newest at the bottom, with Copy and Clear. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogDialog(onClose: () -> Unit) {
    val entries by Log.entries.collectAsState()
    val clipboard = LocalClipboardManager.current
    val list = rememberLazyListState()
    LaunchedEffect(entries.size) { if (entries.isNotEmpty()) list.scrollToItem(entries.size - 1) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Log") },
                    navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                    actions = {
                        TextButton(onClick = { clipboard.setText(AnnotatedString(Log.text())) }, enabled = entries.isNotEmpty()) { Text("Copy") }
                        TextButton(onClick = Log::clear, enabled = entries.isNotEmpty()) { Text("Clear") }
                    },
                )
            }
        ) { padding ->
            if (entries.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(padding).padding(24.dp)) { Text("Nothing logged yet", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(padding), state = list, contentPadding = PaddingValues(12.dp)) {
                    items(entries) { e ->
                        Text(
                            e.toString(),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = if (e.level == Log.Level.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}
