package com.xrdavies.mob.ui.desktop

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xrdavies.mob.viewmodel.AppState
import com.xrdavies.mob.vnc.VncSession
import com.xrdavies.mob.vnc.VncStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.min
import kotlin.math.roundToInt

private val Live = Color(0xFF22C55E)
private val Waiting = Color(0xFFF59E0B)
private val Muted = Color(0xFF9CA3AF)

/** Keeps the shared live view open while the calling view is on screen. */
@Composable
private fun RetainDesktop(appState: AppState) {
    DisposableEffect(appState) {
        appState.retainDesktop()
        onDispose { appState.releaseDesktop() }
    }
}

/**
 * Thumbnail under the Chat top bar (hidden by default; toggled from the top bar).
 * Always view-only: tapping it opens the full-screen live view.
 */
@Composable
fun DesktopDrawer(appState: AppState, modifier: Modifier = Modifier) {
    RetainDesktop(appState)
    val state by appState.vnc.state.collectAsState()
    val aspect = if (state.width > 0 && state.height > 0) state.width.toFloat() / state.height else 16f / 10f
    Box(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 260.dp)
                .aspectRatio(aspect)
                .clip(RoundedCornerShape(12.dp))
                .clickable { appState.openLiveView() }
        ) {
            DesktopCanvas(appState, snapshotWidth = 960, Modifier.fillMaxSize())
            StatusChip(appState, Modifier.align(Alignment.TopStart).padding(8.dp))
            Icon(
                Icons.Filled.OpenInFull, "Open full screen", tint = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                    .padding(6.dp)
                    .size(16.dp)
            )
        }
    }
}

/** Full-screen live view with the View / Control switch. */
@Composable
fun LiveViewDialog(appState: AppState) {
    val show by appState.showLiveView.collectAsState()
    if (!show) return
    Dialog(
        onDismissRequest = appState::closeLiveView,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        RetainDesktop(appState)
        LiveView(appState)
    }
}

@Composable
private fun LiveView(appState: AppState) {
    val vnc = appState.vnc
    val state by vnc.state.collectAsState()
    val live = state.status == VncStatus.Connected
    val control = state.control
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val keyboardFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    Surface(Modifier.fillMaxSize(), color = Color.Black) {
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = appState::closeLiveView) { Icon(Icons.Filled.Close, "Close", tint = Color.White) }
                StatusChip(appState)
                Spacer(Modifier.weight(1f))
                if (live) {
                    if (control) {
                        IconButton(onClick = {
                            keyboardFocus.requestFocus()
                            keyboard?.show()
                        }) { Icon(Icons.Filled.Keyboard, "Keyboard", tint = Color.White) }
                    }
                    ModeSwitch(control) { vnc.setControl(it) }
                } else if (state.status != VncStatus.Connecting) {
                    TextButton(onClick = appState::connectDesktop) { Text("Reconnect", color = Color.White) }
                }
            }

            // View mode: pinch to zoom, drag to pan, double-tap to reset. Control mode: touches go to the Mac.
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clipToBounds()
                    .pointerInput(control) {
                        if (control) return@pointerInput
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 6f)
                            val maxX = (scale - 1) * size.width / 2
                            val maxY = (scale - 1) * size.height / 2
                            offset = Offset((offset.x + pan.x).coerceIn(-maxX, maxX), (offset.y + pan.y).coerceIn(-maxY, maxY))
                        }
                    }
                    .pointerInput(control) {
                        if (control) return@pointerInput
                        detectTapGestures(onDoubleTap = {
                            scale = 1f
                            offset = Offset.Zero
                        })
                    }
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        }
                        .then(if (control) Modifier.controlInput(vnc, state.width, state.height) else Modifier)
                ) {
                    DesktopCanvas(appState, snapshotWidth = 1600, Modifier.fillMaxSize())
                }
            }

            Text(
                when {
                    control -> "Tap to click · long-press to right-click · drag to drag"
                    live -> "View only · pinch to zoom · switch to Control to use the Mac"
                    state.status == VncStatus.Connecting -> "View only · connecting the live view…"
                    else -> "View only snapshot · " + state.detail.ifBlank { "live view not connected" }
                },
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().padding(8.dp),
            )
        }
        if (control) KeyboardSink(vnc, keyboardFocus)
    }
}

/** Live frames when the VNC session is up, otherwise /screen snapshots. */
@Composable
private fun DesktopCanvas(appState: AppState, snapshotWidth: Int, modifier: Modifier) {
    val vnc = appState.vnc
    val state by vnc.state.collectAsState()
    val frame by vnc.frame.collectAsState()
    val live = state.status == VncStatus.Connected
    var snapshot by remember { mutableStateOf<Bitmap?>(null) }
    var snapshotError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(live) {
        if (live) return@LaunchedEffect
        while (isActive) {
            try {
                snapshot = appState.apiClient.screen(snapshotWidth)
                snapshotError = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                snapshotError = e.message
            }
            delay(2000)
        }
    }

    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            frame // redraw on every update
            val bmp = (if (live) vnc.bitmap else snapshot) ?: return@Canvas
            val s = min(size.width / bmp.width, size.height / bmp.height)
            val w = (bmp.width * s).roundToInt()
            val h = (bmp.height * s).roundToInt()
            drawImage(
                bmp.asImageBitmap(),
                dstOffset = IntOffset(((size.width - w) / 2).roundToInt(), ((size.height - h) / 2).roundToInt()),
                dstSize = IntSize(w, h),
                filterQuality = FilterQuality.Medium,
            )
        }
        if (!live && snapshot == null) {
            val text = when (state.status) {
                VncStatus.Connecting -> "Connecting…"
                else -> listOfNotNull(state.detail.takeIf { it.isNotBlank() }, snapshotError?.let { "Snapshot: $it" })
                    .joinToString("\n").ifBlank { "Office desktop is not connected" }
            }
            Text(text, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
        }
    }
}

@Composable
private fun StatusChip(appState: AppState, modifier: Modifier = Modifier) {
    val state by appState.vnc.state.collectAsState()
    val (color, label) = when (state.status) {
        VncStatus.Connected -> Live to (if (state.control) "Controlling" else "Live")
        VncStatus.Connecting -> Waiting to "Connecting"
        else -> Muted to "Snapshot"
    }
    Row(
        modifier
            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ModeSwitch(control: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .padding(end = 8.dp)
            .background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(50))
            .padding(3.dp)
    ) {
        listOf(false to "View", true to "Control").forEach { (value, label) ->
            val selected = control == value
            Text(
                label,
                color = if (selected) Color.Black else Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) Color.White else Color.Transparent)
                    .clickable { onChange(value) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}

/** Maps touches on the fitted desktop to Mac pointer events (Control mode only). */
private fun Modifier.controlInput(vnc: VncSession, fw: Int, fh: Int): Modifier {
    if (fw <= 0 || fh <= 0) return this
    fun IntSize.map(p: Offset): Pair<Int, Int> {
        val s = min(width.toFloat() / fw, height.toFloat() / fh)
        val x = ((p.x - (width - fw * s) / 2) / s).roundToInt().coerceIn(0, fw - 1)
        val y = ((p.y - (height - fh * s) / 2) / s).roundToInt().coerceIn(0, fh - 1)
        return x to y
    }
    return pointerInput(fw, fh) {
        detectTapGestures(
            onTap = { p -> size.map(p).let { (x, y) -> vnc.click(x, y) } },
            onLongPress = { p -> size.map(p).let { (x, y) -> vnc.click(x, y, VncSession.BUTTON_RIGHT) } },
        )
    }.pointerInput(fw, fh) {
        var last = 0 to 0
        detectDragGestures(
            onDragStart = { p ->
                last = size.map(p)
                vnc.pointer(last.first, last.second, 0)
                vnc.pointer(last.first, last.second, VncSession.BUTTON_LEFT)
            },
            onDrag = { change, _ ->
                last = size.map(change.position)
                vnc.pointer(last.first, last.second, VncSession.BUTTON_LEFT)
            },
            onDragEnd = { vnc.pointer(last.first, last.second, 0) },
            onDragCancel = { vnc.pointer(last.first, last.second, 0) },
        )
    }
}

/**
 * Invisible field that receives the phone keyboard. It always holds one space so a
 * backspace on an "empty" field is still seen; typed text is forwarded and cleared.
 */
@Composable
private fun KeyboardSink(vnc: VncSession, focus: FocusRequester) {
    val blank = TextFieldValue(" ", TextRange(1))
    var value by remember { mutableStateOf(blank) }
    BasicTextField(
        value = value,
        onValueChange = { v ->
            val old = value.text
            val new = v.text
            when {
                new.length < old.length -> repeat(old.length - new.length) { vnc.tapKey(VncSession.XK_BACKSPACE) }
                new.startsWith(old) -> new.substring(old.length).forEach(vnc::type)
            }
            value = blank
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { vnc.tapKey(VncSession.XK_RETURN) }),
        modifier = Modifier.size(1.dp).alpha(0f).focusRequester(focus),
    )
}
