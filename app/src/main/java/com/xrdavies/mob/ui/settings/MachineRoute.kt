package com.xrdavies.mob.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xrdavies.mob.data.Machine
import com.xrdavies.mob.viewmodel.AppState

/**
 * Whether [m] answers over the address in use, null while not known yet: the active machine by its
 * health check, the others by their event stream.
 */
fun reachable(m: Machine, activeId: String?, connected: Boolean, healthError: String?, up: Map<String, Boolean>): Boolean? = when {
    m.needsPairing == true -> null
    m.id == activeId -> if (connected) true else if (healthError != null) false else null
    else -> up[m.id]
}

@Composable
fun rememberReachable(appState: AppState, m: Machine): Boolean? {
    val activeId by appState.activeMachineId.collectAsState()
    val connected by appState.isConnected.collectAsState()
    val error by appState.healthError.collectAsState()
    val up by appState.routeUp.collectAsState()
    return reachable(m, activeId, connected, error, up)
}

/** The address in use does not answer (or needs pairing) and there is another to switch to. */
private fun fallback(m: Machine, up: Boolean?) =
    m.address?.let { a -> if (up == false || m.needsPairing == true) m.addresses.firstOrNull { it.id != a.id } else null }

/**
 * A machine's subtitle: "● LAN · office-mac.local", or, when the address in use does not answer (or
 * needs pairing) and there is another, "○ LAN not reachable · Use Remote". The app never switches by itself.
 */
@Composable
fun RouteLine(appState: AppState, m: Machine) {
    val up = rememberReachable(appState, m)
    val a = m.address ?: return
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(if (up == true) Color(0xFF22C55E) else if (up == false) Color(0xFFEF4444) else muted.copy(alpha = 0.4f), CircleShape))
        Spacer(Modifier.width(6.dp))
        val other = fallback(m, up)
        when {
            other != null -> {
                Text("${m.label(a)} ${if (m.needsPairing == true) "needs pairing" else "not reachable"} · ", style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1)
                Text(
                    "Use ${m.label(other)}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary, maxLines = 1,
                    modifier = Modifier.clickable { appState.selectRoute(m.id, other.id) }.padding(vertical = 4.dp),
                )
            }
            m.addresses.size > 1 -> Text("${m.label(a)} · ${Machine.host(a.url)}", style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            else -> Text(a.url, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** "Use LAN ✓" / "Use Remote" for a machine with more than one address. */
@Composable
fun RouteMenuItems(appState: AppState, m: Machine, onDone: () -> Unit) {
    if (m.addresses.size < 2) return
    m.addresses.forEach { a ->
        DropdownMenuItem(
            text = { Text("Use ${m.label(a)}") },
            leadingIcon = { if (a.id == m.address?.id) Icon(Icons.Filled.Check, null) else Spacer(Modifier.size(24.dp)) },
            onClick = {
                onDone()
                appState.selectRoute(m.id, a.id)
            },
        )
    }
}

/** Above the chat: the address in use does not answer (or needs pairing) and the machine has another. One tap switches. */
@Composable
fun RouteBanner(appState: AppState, m: Machine, modifier: Modifier = Modifier) {
    val up = rememberReachable(appState, m)
    val a = m.address ?: return
    val other = fallback(m, up) ?: return
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.WifiOff, null, tint = Color(0xFFD97706))
            Spacer(Modifier.width(10.dp))
            Text("${m.label(a)} ${if (m.needsPairing == true) "needs pairing" else "not reachable"}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { appState.selectRoute(m.id, other.id) }) { Text("Use ${m.label(other)}") }
        }
    }
}
