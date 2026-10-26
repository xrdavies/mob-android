package com.xrdavies.mob.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xrdavies.mob.data.AgentSettings
import com.xrdavies.mob.data.ApiClient
import com.xrdavies.mob.data.ErrorCodeBody
import com.xrdavies.mob.data.Machine
import com.xrdavies.mob.data.MachineSettings
import com.xrdavies.mob.data.SettingsInfo
import com.xrdavies.mob.data.TurnSettings
import com.xrdavies.mob.data.readable
import com.xrdavies.mob.viewmodel.AppState
import kotlinx.coroutines.launch
import retrofit2.HttpException

private fun permissionLabel(p: String?) = if (p == "approve") "Ask before commands and edits" else "Run without asking"

/** The code mobd sends with an error (settings_changed …), when there is one. */
private fun Throwable.code(): String? = (this as? HttpException)?.response()?.errorBody()?.string()
    ?.let { runCatching { ApiClient.gson.fromJson(it, ErrorCodeBody::class.java)?.code }.getOrNull() }

/**
 * Machine settings shared by every device: the default agent, and per agent its models,
 * default model and effort, and permissions (mobd with "settings").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentSettingsDialog(appState: AppState, machine: Machine, focus: String? = null, onClose: () -> Unit) {
    val tick by appState.settingsTick.collectAsState()
    // Any paired machine, not only the active one.
    val client = remember(machine) { appState.clientFor(machine) }
    val scroll = rememberScrollState()
    // Where each agent's card starts, to scroll to the one the chat's model menu opened.
    val cardTop = remember { mutableStateMapOf<String, Int>() }
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<SettingsInfo?>(null) }
    var edit by remember { mutableStateOf<MachineSettings?>(null) }
    var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var saving by remember { mutableStateOf(false) }
    // Shown on the button for a moment after a save succeeds.
    var justSaved by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<List<String>?>(null) }
    val adding = remember { mutableStateMapOf<String, String>() }
    fun label(name: String) = info?.agents?.get(name)?.label ?: name

    suspend fun load() {
        try {
            val v = client.api.settings()
            info = v
            // Keep local edits unless another device saved meanwhile.
            if (edit == null || edit?.rev != v.settings.rev) edit = v.settings
        } catch (e: java.io.IOException) {
            message = "Connect to ${machine.name} to change its agents. (${e.readable()})" to true
        } catch (e: Exception) {
            message = e.readable() to true
        }
    }
    LaunchedEffect(tick) { if (machine.needsPairing != true) load() }
    LaunchedEffect(info != null, cardTop[focus]) {
        val y = focus?.let { cardTop[it] } ?: return@LaunchedEffect
        if (info != null) scroll.animateScrollTo(y)
    }

    fun save(confirmed: Boolean) {
        val v = info ?: return
        val e = edit ?: return
        val risky = TurnSettings.newlyUnguarded(v.settings, e)
        if (risky.isNotEmpty() && !confirmed) {
            confirm = risky
            return
        }
        saving = true
        scope.launch {
            try {
                val saved = client.api.saveSettings(e)
                info = saved
                edit = saved.settings
                message = null
                justSaved = true
                launch {
                    kotlinx.coroutines.delay(2000)
                    justSaved = false
                }
                appState.checkHealth()
            } catch (err: Exception) {
                if (err.code() == "settings_changed") {
                    edit = null
                    load()
                    message = "Another device changed these settings. They are reloaded; make your change again." to true
                } else {
                    message = err.readable() to true
                }
            } finally {
                saving = false
            }
        }
    }

    confirm?.let { agents ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Run without asking?") },
            text = { Text("${agents.joinToString { label(it) }} will run commands and edit files on ${machine.name} without asking you first.") },
            confirmButton = { TextButton(onClick = { confirm = null; save(true) }) { Text("Allow and save") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("${machine.name} · Agents") },
                    navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                    actions = {
                        // The button itself shows the save: Saving…, then ✓ Saved, then greyed out until a change.
                        TextButton(onClick = { save(false) }, enabled = edit != null && edit != info?.settings && !saving && !justSaved) {
                            when {
                                saving -> {
                                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Saving…")
                                }
                                justSaved -> {
                                    Icon(Icons.Filled.Check, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(4.dp))
                                    Text("Saved", color = MaterialTheme.colorScheme.primary)
                                }
                                else -> Text("Save")
                            }
                        }
                    },
                )
            }
        ) { padding ->
            val v = info
            val e = edit
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(scroll).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                message?.let { (text, error) ->
                    Text(text, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                }
                if (machine.needsPairing == true) {
                    Text("Pair ${machine.name} again to change its agents.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    return@Column
                }
                if (v == null || e == null) {
                    if (message == null) CircularProgressIndicator()
                    return@Column
                }
                Text(
                    "Shared by every device paired with this machine. Each chat keeps the model and effort it started with; change them for one chat in its message box.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val agents = v.agents.orEmpty().keys.sorted()
                Choice("Default agent", e.defaultAgent, agents.map { it to label(it) }) { edit = e.copy(defaultAgent = it) }
                // An empty default sends none: the agent's own applies.
                fun agentDefault(value: String?) = value?.let { "Agent's default ($it)" } ?: "Agent's default"
                for (name in agents) {
                    val agent = v.agents!![name]!!
                    val a = e.agents[name] ?: continue
                    val models = a.models
                    fun update(change: (AgentSettings) -> AgentSettings) {
                        edit = TurnSettings.with(e, name, change)
                        message = null
                    }
                    ElevatedCard(Modifier.fillMaxWidth().onGloballyPositioned { cardTop[name] = it.positionInParent().y.toInt() }) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(label(name), style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Models offered",
                                style = MaterialTheme.typography.labelLarge,
                            )
                            if (models.isEmpty()) Text("None listed: chats use the default model.", style = MaterialTheme.typography.bodySmall)
                            for (m in models) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(m, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                                    IconButton(onClick = {
                                        update { it.copy(models = models - m, defaultModel = if (it.defaultModel == m) "" else it.defaultModel) }
                                    }) { Icon(Icons.Filled.Close, "Remove $m") }
                                }
                            }
                            fun add() {
                                val next = TurnSettings.addModel(models, adding[name].orEmpty())
                                if (next == null) {
                                    message = "Model names use letters, digits and . _ : / - (no spaces)." to true
                                } else {
                                    update { it.copy(models = next) }
                                    adding[name] = ""
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    adding[name].orEmpty(), { adding[name] = it }, Modifier.weight(1f), singleLine = true,
                                    placeholder = { Text("Add a model ID") },
                                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { add() }),
                                )
                                OutlinedButton(onClick = { add() }, enabled = adding[name].orEmpty().isNotBlank()) { Text("Add") }
                            }
                            Choice("Default model", a.defaultModel, listOf("" to agentDefault(agent.agentDefaultModel)) + models.map { it to it }) { v2 ->
                                update { it.copy(defaultModel = v2) }
                            }
                            Choice("Default effort", a.defaultEffort, listOf("" to agentDefault(agent.agentDefaultEffort)) + agent.efforts.orEmpty().map { it to it }) { v2 ->
                                update { it.copy(defaultEffort = v2) }
                            }
                            Choice(
                                "Permissions", a.permissions,
                                listOf("bypass" to permissionLabel("bypass"), "approve" to permissionLabel("approve")),
                            ) { v2 -> update { it.copy(permissions = v2) } }
                        }
                    }
                }
            }
        }
    }
}

/** A labelled dropdown of (value, label) choices. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Choice(label: String, value: String, options: List<Pair<String, String>>, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(open, { open = it }) {
        OutlinedTextField(
            options.firstOrNull { it.first == value }?.second ?: value, {}, readOnly = true, label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(open, { open = false }) {
            for ((v, l) in options) DropdownMenuItem(text = { Text(l) }, onClick = { onChange(v); open = false })
        }
    }
}
