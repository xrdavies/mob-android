package com.xrdavies.mob.ui.settings

import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.xrdavies.mob.data.Machine
import com.xrdavies.mob.data.PairLink
import com.xrdavies.mob.data.deviceNameError
import com.xrdavies.mob.data.readable
import com.xrdavies.mob.viewmodel.AppState
import kotlinx.coroutines.launch

/** The name mobd shows for this phone: the one set in Android settings, else the model. */
private fun defaultDeviceName(context: Context): String =
    Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: Build.MODEL

/**
 * Adds an office Mac from a `mobd link` link or QR code, or pairs [machine] again.
 * State is saveable: the scanner may recreate the activity.
 */
@Composable
fun PairDialog(appState: AppState, machine: Machine?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf(defaultDeviceName(context)) }
    var confirmed by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val parsed = remember(text) { if (text.isBlank()) null else runCatching { PairLink.parse(text) } }
    val link = parsed?.getOrNull()
    // The saved office this link belongs to: the machine paired again, or the one with its server ID.
    val target = machine ?: link?.let { l -> appState.machines.value.firstOrNull { it.serverId == l.serverId } }
    val otherOffice = target?.serverId != null && link != null && link.serverId != target.serverId
    // Another address of the saved office: pairing adds it to the same row, no confirmation needed.
    val newAddress = target?.serverId != null && !otherOffice && link != null && target.addresses.none { link.sameOrigin(it.url) }
    val needsConfirm = otherOffice
    val linkError = parsed?.exceptionOrNull()?.readable()

    val scanner = rememberLauncherForActivityResult(ScanContract()) { r ->
        r.contents?.let {
            text = it.trim()
            confirmed = false
            error = null
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (machine == null) "Add machine" else "Pair ${machine.name} again") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "On the office Mac run mobd link, then scan its QR code or paste the link it prints.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    text, {
                        text = it
                        confirmed = false
                        error = null
                    },
                    label = { Text("Pairing link") }, placeholder = { Text("http://192.168.1.20:8080/pair#…") },
                    singleLine = true, enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    trailingIcon = {
                        IconButton(enabled = !busy, onClick = {
                            val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
                            clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.let {
                                text = it.trim()
                                confirmed = false
                                error = null
                            }
                        }) { Icon(Icons.Filled.ContentPaste, "Paste") }
                    },
                    isError = linkError != null,
                    supportingText = if (linkError != null) ({ Text(linkError) }) else null,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(enabled = !busy, onClick = {
                    scanner.launch(
                        ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setPrompt("Scan the QR code printed by mobd link")
                            .setBeepEnabled(false)
                            .setOrientationLocked(false)
                    )
                }) {
                    Icon(Icons.Filled.QrCodeScanner, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Scan QR code")
                }

                if (link != null) {
                    Text("Pairs with ${link.origin}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    if (link.insecure) Warning("Not encrypted: use only on a trusted network or a Tailscale/VPN address")
                    if (newAddress && target != null) Text(
                        "Adds this address to ${target.name} as ${com.xrdavies.mob.data.AddressKind.of(link.origin).title}, next to " +
                            target.addresses.joinToString { target.label(it) } + ". Mob uses it from now on; long-press ${target.name} in the machine list to switch.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (otherOffice) Warning("This link is for a different office (its server ID differs from the one paired before). Pairing replaces its addresses.")
                    if (needsConfirm) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(confirmed, { confirmed = it }, enabled = !busy)
                            Text("Replace it with this office", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    OutlinedTextField(
                        name, { name = it }, label = { Text("Name of this device") }, singleLine = true, enabled = !busy,
                        isError = deviceNameError(name) != null,
                        supportingText = { Text(deviceNameError(name) ?: "Shown on the office Mac in the list of paired devices") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Text(
                    "Connect over the same network, a VPN or Tailscale address, or a self-hosted Tailscale Funnel HTTPS address: mobd link prints the right link for each. Mob relay — coming soon.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && link != null && deviceNameError(name) == null && (!needsConfirm || confirmed),
                onClick = {
                    val l = link ?: return@TextButton
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            appState.pair(l, name, machine).await()
                            onDismiss()
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = e.readable()
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(if (newAddress) "Add address" else "Pair") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Warning(text: String) {
    Row {
        Icon(Icons.Filled.WarningAmber, null, Modifier.size(16.dp).padding(top = 2.dp), tint = Color(0xFFD97706))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

/** Shown instead of a connection error when mobd refused this device or it was never paired. */
@Composable
fun NeedsPairingBanner(machine: Machine, onPair: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${machine.name} needs pairing", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onErrorContainer)
                Text(
                    "Run mobd link on the office Mac, then pair this device again.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Button(onClick = onPair) { Text("Pair") }
        }
    }
}
