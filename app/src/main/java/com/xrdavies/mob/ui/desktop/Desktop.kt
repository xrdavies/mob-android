package com.xrdavies.mob.ui.desktop

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import com.xrdavies.mob.data.NEEDS_PAIRING
import com.xrdavies.mob.data.isUnauthorized
import com.xrdavies.mob.data.latestClick
import com.xrdavies.mob.data.screenGeometry
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
import androidx.compose.ui.unit.Dp
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
 * Thumbnail under the Chat top bar, or beside the chat on tablets (hidden by default; toggled from the top bar).
 * Always view-only: tapping it opens the full-screen live view.
 */
@Composable
fun DesktopDrawer(appState: AppState, modifier: Modifier = Modifier, maxHeight: Dp = 260.dp) {
    RetainDesktop(appState)
    val state by appState.vnc.state.collectAsState()
    val aspect = if (state.width > 0 && state.height > 0) state.width.toFloat() / state.height else 16f / 10f
    Box(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.TopCenter) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
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
    // Where the last touch reached the Mac, in desktop pixels.
    var mark by remember { mutableStateOf<IntOffset?>(null) }
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
                    .then(
                        if (control) Modifier.controlGestures(
                            vnc, state.width, state.height,
                            view = { scale to offset },
                            onZoom = { zoom, pan, size ->
                                scale = (scale * zoom).coerceIn(1f, 6f)
                                val maxX = (scale - 1) * size.width / 2
                                val maxY = (scale - 1) * size.height / 2
                                offset = Offset((offset.x + pan.x).coerceIn(-maxX, maxX), (offset.y + pan.y).coerceIn(-maxY, maxY))
                            },
                            onTouch = { mark = it },
                        ) else Modifier
                    )
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
                ) {
                    DesktopCanvas(appState, snapshotWidth = 1600, Modifier.fillMaxSize())
                    if (control) mark?.let { TouchMarker(it, state.width, state.height, scale) }
                }
            }

            if (control) KeysBar(vnc) {
                keyboardFocus.requestFocus()
                keyboard?.show()
            }

            Text(
                when {
                    control -> "Tap = click · long-press = right-click · drag · two fingers: scroll or pinch to zoom"
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
    var snapshotAt by remember { mutableStateOf<java.time.LocalTime?>(null) }

    // The agent's last click pings once on the picture, like the desktop app.
    val detail by appState.chat.detail.collectAsState()
    val activities = detail?.activities.orEmpty()
    val click = remember(activities) { latestClick(activities) }
    val geometry = remember(activities) { screenGeometry(activities) }
    val ping = remember(click?.id) { Animatable(0f) }
    LaunchedEffect(click?.id) { if (click != null) ping.animateTo(1f, tween(1600)) }

    val machines by appState.machines.collectAsState()
    val activeId by appState.activeMachineId.collectAsState()
    val needsPairing = machines.firstOrNull { it.id == activeId }?.needsPairing == true
    LaunchedEffect(live, needsPairing, activeId) {
        if (live) return@LaunchedEffect
        if (needsPairing) {
            snapshotError = NEEDS_PAIRING
            return@LaunchedEffect
        }
        val client = appState.apiClient
        while (isActive) {
            try {
                snapshot = client.screen(snapshotWidth)
                snapshotAt = java.time.LocalTime.now()
                snapshotError = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                snapshotError = e.message
                if (e.isUnauthorized()) {
                    activeId?.let { appState.markNeedsPairing(it, client.token) }
                    break
                }
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
            val left = ((size.width - w) / 2).roundToInt()
            val top = ((size.height - h) / 2).roundToInt()
            drawImage(
                bmp.asImageBitmap(),
                dstOffset = IntOffset(left, top),
                dstSize = IntSize(w, h),
                filterQuality = FilterQuality.Medium,
            )
            if (click != null && geometry != null && ping.value < 1f) {
                val c = Offset(left + (click.x / geometry.pointWidth * w).toFloat(), top + (click.y / geometry.pointHeight * h).toFloat())
                val t = ping.value
                drawCircle(Color(0xFF3B82F6).copy(alpha = 1f - t), radius = (8 + 22 * t).dp.toPx(), center = c, style = Stroke(2.5.dp.toPx()))
                drawCircle(Color(0xFF3B82F6).copy(alpha = 0.5f * (1f - t)), radius = 6.dp.toPx(), center = c)
            }
        }
        if (!live && snapshot == null) {
            val text = when (state.status) {
                VncStatus.Connecting -> "Connecting…"
                else -> listOfNotNull(state.detail.takeIf { it.isNotBlank() }, snapshotError?.let { "Snapshot: $it" })
                    .joinToString("\n").ifBlank { "Office desktop is not connected" }
            }
            Text(text, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
        }
        if (!live) SnapshotLabel(snapshotAt.takeIf { snapshot != null }, snapshotError, Modifier.align(Alignment.BottomStart))
    }
}

/** "Snapshot · 17:33:05", or why snapshots fail (usually Screen Recording on the office Mac). */
@Composable
private fun SnapshotLabel(at: java.time.LocalTime?, error: String?, modifier: Modifier) {
    val text = when {
        error != null && at != null -> "Snapshot · ${at.format(SNAPSHOT_TIME)} · not updating"
        error != null -> "Snapshot unavailable. Check Screen Recording on the office Mac."
        at != null -> "Snapshot · ${at.format(SNAPSHOT_TIME)}"
        else -> return
    }
    Text(
        text, color = Color.White, style = MaterialTheme.typography.labelSmall,
        modifier = modifier.padding(8.dp).background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

private val SNAPSHOT_TIME = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")

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

/** Maps a point in the view box (screen space, before zoom) to desktop pixels. */
internal fun toDesktop(p: Offset, size: IntSize, fw: Int, fh: Int, scale: Float, offset: Offset): IntOffset {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val lx = cx + (p.x - cx - offset.x) / scale
    val ly = cy + (p.y - cy - offset.y) / scale
    val s = min(size.width.toFloat() / fw, size.height.toFloat() / fh)
    val x = ((lx - (size.width - fw * s) / 2) / s).roundToInt().coerceIn(0, fw - 1)
    val y = ((ly - (size.height - fh * s) / 2) / s).roundToInt().coerceIn(0, fh - 1)
    return IntOffset(x, y)
}

/**
 * Control-mode touches: tap = click, long-press = right-click, one-finger drag = mouse drag,
 * two fingers = scroll wheel, or zoom the view when they pinch.
 */
private fun Modifier.controlGestures(
    vnc: VncSession,
    fw: Int,
    fh: Int,
    view: () -> Pair<Float, Offset>,
    onZoom: (Float, Offset, IntSize) -> Unit,
    onTouch: (IntOffset) -> Unit,
): Modifier {
    if (fw <= 0 || fh <= 0) return this
    return pointerInput(fw, fh) {
        fun remote(p: Offset): IntOffset = view().let { (scale, off) -> toDesktop(p, size, fw, fh, scale, off) }
        val step = 28.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val slop = viewConfiguration.touchSlop
            var mode = Gesture.Undecided
            var last = remote(down.position)
            var zoomed = 1f
            var panned = Offset.Zero
            var wheel = Offset.Zero
            while (true) {
                val event = if (mode == Gesture.Undecided) {
                    withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { awaitPointerEvent() }
                } else awaitPointerEvent()
                if (event == null) {
                    // Held still: right-click, then wait for the finger to lift.
                    last = remote(down.position)
                    vnc.click(last.x, last.y, VncSession.BUTTON_RIGHT)
                    onTouch(last)
                    mode = Gesture.Done
                    continue
                }
                val pressed = event.changes.filter { it.pressed }
                if (pressed.isEmpty()) break
                if (pressed.size >= 2 && mode in listOf(Gesture.Undecided, Gesture.TwoFinger, Gesture.Zoom, Gesture.Scroll)) {
                    val zoom = event.calculateZoom()
                    val pan = event.calculatePan()
                    if (mode == Gesture.Undecided) mode = Gesture.TwoFinger
                    if (mode == Gesture.TwoFinger) {
                        zoomed *= zoom
                        panned += pan
                        if (kotlin.math.abs(zoomed - 1f) > 0.12f) mode = Gesture.Zoom
                        else if (panned.getDistance() > slop) mode = Gesture.Scroll
                    }
                    when (mode) {
                        Gesture.Zoom -> onZoom(zoom, pan, size)
                        Gesture.Scroll -> {
                            // Natural scrolling: fingers moving up scroll the page down.
                            wheel -= pan
                            val dx = (wheel.x / step).toInt()
                            val dy = (wheel.y / step).toInt()
                            if (dx != 0 || dy != 0) {
                                val at = remote(event.calculateCentroid())
                                vnc.scroll(at.x, at.y, dx, dy)
                                wheel -= Offset(dx * step, dy * step)
                            }
                        }
                        else -> Unit
                    }
                    event.changes.forEach { it.consume() }
                    continue
                }
                val change = pressed.first()
                if (mode == Gesture.Undecided && (change.position - down.position).getDistance() > slop) {
                    mode = Gesture.Drag
                    val start = remote(down.position)
                    vnc.pointer(start.x, start.y, 0)
                    vnc.pointer(start.x, start.y, VncSession.BUTTON_LEFT)
                }
                if (mode == Gesture.Drag) {
                    last = remote(change.position)
                    vnc.pointer(last.x, last.y, VncSession.BUTTON_LEFT)
                    onTouch(last)
                    change.consume()
                }
            }
            when (mode) {
                Gesture.Undecided -> {
                    last = remote(down.position)
                    vnc.click(last.x, last.y)
                    onTouch(last)
                }
                Gesture.Drag -> vnc.pointer(last.x, last.y, 0)
                else -> Unit
            }
        }
    }
}

private enum class Gesture { Undecided, Drag, TwoFinger, Zoom, Scroll, Done }

/** A ring where the last touch reached the Mac (the Mac draws its own pointer). */
@Composable
private fun TouchMarker(at: IntOffset, fw: Int, fh: Int, scale: Float) {
    if (fw <= 0 || fh <= 0) return
    Canvas(Modifier.fillMaxSize()) {
        val s = min(size.width / fw, size.height / fh)
        val c = Offset((size.width - fw * s) / 2 + at.x * s, (size.height - fh * s) / 2 + at.y * s)
        val r = 9.dp.toPx() / scale
        drawCircle(Color.White.copy(alpha = 0.9f), r, c, style = Stroke(2.dp.toPx() / scale))
        drawCircle(Color(0xFF3B82F6).copy(alpha = 0.35f), r, c)
    }
}

/** Keys the phone keyboard lacks: modifiers (latched for the next key), Esc, Tab, arrows, paste. */
@Composable
private fun KeysBar(vnc: VncSession, onKeyboard: () -> Unit) {
    val mods by vnc.modifiers.collectAsState()
    val clipboard = LocalClipboardManager.current
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        @Composable
        fun Key(label: String, description: String, on: Boolean = false, onClick: () -> Unit) {
            Text(
                label,
                color = if (on) Color.Black else Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (on) Color.White else Color.White.copy(alpha = 0.14f))
                    .clickable(onClickLabel = description, onClick = onClick)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        IconButton(onClick = onKeyboard, modifier = Modifier.size(36.dp)) { Icon(Icons.Filled.Keyboard, "Keyboard", tint = Color.White) }
        listOf(
            "⌘" to VncSession.XK_META_L, "⌥" to VncSession.XK_ALT_L,
            "⌃" to VncSession.XK_CONTROL_L, "⇧" to VncSession.XK_SHIFT_L,
        ).forEach { (label, sym) -> Key(label, "Hold $label for the next key", sym in mods) { vnc.toggleModifier(sym) } }
        Key("esc", "Escape") { vnc.tapKey(VncSession.XK_ESCAPE) }
        Key("tab", "Tab") { vnc.tapKey(VncSession.XK_TAB) }
        Key("←", "Left") { vnc.tapKey(VncSession.XK_LEFT) }
        Key("↑", "Up") { vnc.tapKey(VncSession.XK_UP) }
        Key("↓", "Down") { vnc.tapKey(VncSession.XK_DOWN) }
        Key("→", "Right") { vnc.tapKey(VncSession.XK_RIGHT) }
        Key("⌫", "Delete") { vnc.tapKey(VncSession.XK_BACKSPACE) }
        Key("⏎", "Return") { vnc.tapKey(VncSession.XK_RETURN) }
        Key("Paste", "Type the phone's clipboard") { clipboard.getText()?.text?.let(vnc::typeText) }
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
        // Surface forces full-size children; without wrapContentSize this invisible field covers
        // the screen and swallows every touch meant for the desktop.
        modifier = Modifier.wrapContentSize(Alignment.TopStart).size(1.dp).alpha(0f).focusRequester(focus),
    )
}
