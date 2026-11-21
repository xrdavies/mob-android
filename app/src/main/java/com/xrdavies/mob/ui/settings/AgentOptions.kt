package com.xrdavies.mob.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.xrdavies.mob.data.AgentOption
import kotlinx.coroutines.launch

/**
 * An agent's config.json settings as mobd lists them (feature agentOptions, mobd
 * docs/agent-adapters.md): a row per option by its type, saved to agents.<name>.<key> and applied by
 * Restart to apply. No agent names here: what an agent offers comes from mobd.
 */
@Composable
fun AgentOptionRows(name: String, options: List<AgentOption>, model: MaintenanceModel) {
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<AgentOption?>(null) }
    var draft by remember { mutableStateOf("") }
    fun key(o: AgentOption) = "agents.$name.${o.key}"
    fun canEdit(o: AgentOption) = o.overridden != true && model.config?.canEdit(key(o)) == true && !model.busy
    // What config.json will apply (shown while a restart is pending), else what runs.
    fun value(o: AgentOption): Any? = model.config?.values?.agents?.get(name)?.get(o.key) ?: o.value
    // "" goes back to the default (Gson drops nulls).
    fun save(o: AgentOption, v: Any) { scope.launch { model.save(mapOf(key(o) to v)) } }

    editing?.let { o ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(o.label) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    o.help?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    OutlinedTextField(
                        draft, { draft = it }, singleLine = true, placeholder = { Text("Default") },
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                        keyboardOptions = KeyboardOptions(keyboardType = if (o.type == "number") KeyboardType.Decimal else KeyboardType.Uri),
                    )
                    Text("Empty goes back to the default.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val v = draft.trim()
                    save(o, if (o.type == "number") v.toDoubleOrNull() ?: v else v)
                    editing = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }

    for (o in options) {
        val v = value(o)
        val enabled = canEdit(o)
        val note = if (o.overridden == true) "Set on the computer; change it there." else o.help
        when {
            o.type == "bool" -> Row(verticalAlignment = Alignment.CenterVertically) {
                OptionLabel(o.label, note, Modifier.weight(1f))
                Switch(checked = v == true, enabled = enabled, onCheckedChange = { save(o, it) })
            }
            o.type == "choice" && !o.choices.isNullOrEmpty() -> Column {
                Choice(o.label, AgentOption.text(v), o.choices.map { it.value to it.label }, enabled) { save(o, it) }
                note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    OptionLabel(o.label, note)
                    Text(
                        AgentOption.text(v).ifEmpty { "Not set" }, fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { draft = AgentOption.text(v); editing = o }, enabled = enabled) { Text("Change") }
            }
        }
    }
}

@Composable
private fun OptionLabel(label: String, note: String?, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label)
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
