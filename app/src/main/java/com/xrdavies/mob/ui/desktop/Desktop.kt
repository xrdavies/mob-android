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
import androidx.compose.material.icons.filled.Speed
import androidx.compose.ui.layout.onSizeChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.xrdavies.mob.stream.LiveQuality
import com.xrdavies.mob.stream.LiveSession
import com.xrdavies.mob.stream.StreamSession
import com.xrdavies.mob.stream.StreamVideo
import com.xrdavies.mob.stream.devicePixels
import com.xrdavies.mob.stream.readoutText
import com.xrdavies.mob.stream.signalBars
import com.xrdavies.mob.viewmodel.AppState
import com.xrdavies.mob.stream.LiveKeys
import com.xrdavies.mob.stream.LiveStatus
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
    val live by appState.live.collectAsState()
    val state by live.state.collectAsState()
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
            DesktopCanvas(appState, Modifier.fillMaxSize())
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
    val live by appState.live.collectAsState()
    val state by live.state.collectAsState()
    val liveConnected = state.status == LiveStatus.Connected
    val control = state.control
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    // Where the last touch reached the Mac, in desktop pixels.
    var mark by remember { mutableStateOf<IntOffset?>(null) }
    val keyboardFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // A touch or key in flight never ends once the app leaves the screen.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { live.setControl(false) }

    Surface(Modifier.fillMaxSize(), color = Color.Black) {
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = appState::closeLiveView) { Icon(Icons.Filled.Close, "Close", tint = Color.White) }
                StatusChip(appState)
                Spacer(Modifier.weight(1f))
                QualityMenu(appState)
                if (liveConnected) {
                    ModeSwitch(control) { live.setControl(it) }
                } else if (state.status != LiveStatus.Connecting) {
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
                            live, state.width, state.height,
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
                    DesktopCanvas(appState, Modifier.fillMaxSize())
                    if (control) mark?.let { TouchMarker(it, state.width, state.height, scale) }
                }
            }

            if (liveConnected) Readout(appState.stream, state.width, state.height)

            if (control) KeysBar(live) {
                keyboardFocus.requestFocus()
                keyboard?.show()
            }

            Text(
                when {
                    control -> "Tap = click · long-press = right-click · drag · two fingers: scroll or pinch to zoom"
                    liveConnected -> "View only · pinch to zoom · switch to Control to use the Mac"
                    state.status == LiveStatus.Connecting -> "View only · connecting the live view…"
                    else -> "View only snapshot · " + state.detail.ifBlank { "live view not connected" }
                },
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().padding(8.dp),
            )
        }
        if (control) KeyboardSink(live, keyboardFocus)
    }
}

/** Live frames when the live view is up (WebRTC or the WebSocket), otherwise /screen snapshots. */
@Composable
private fun DesktopCanvas(appState: AppState, modifier: Modifier) {
    val live by appState.live.collectAsState()
    val state by live.state.collectAsState()
    val liveConnected = state.status == LiveStatus.Connected
    var snapshot by remember { mutableStateOf<Bitmap?>(null) }
    var snapshotEtag by remember { mutableStateOf<String?>(null) }
    var snapshotError by remember { mutableStateOf<String?>(null) }
    var snapshotAt by remember { mutableStateOf<java.time.LocalTime?>(null) }
    // Snapshots are fetched at the view's width in pixels; a new width starts a new download.
    var viewWidth by remember { mutableIntStateOf(0) }
    val snapshotWidth = if (viewWidth > 0) devicePixels(viewWidth) else 0

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
    LaunchedEffect(liveConnected, needsPairing, activeId, snapshotWidth) {
        if (liveConnected || snapshotWidth == 0) return@LaunchedEffect
        if (needsPairing) {
            snapshotError = NEEDS_PAIRING
            return@LaunchedEffect
        }
        val client = appState.apiClient
        while (isActive) {
            try {
                val snap = client.screen(snapshotWidth, 65, snapshotEtag.takeIf { snapshot != null })
                if (snap != null) {
                    snapshot = snap.bitmap
                    snapshotEtag = snap.etag
                }
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
            delay(3000)
        }
    }

    Box(modifier.background(Color.Black).onSizeChanged { viewWidth = it.width }, contentAlignment = Alignment.Center) {
        // The live video (WebRTC or the WebSocket) fills a box of the stream's aspect ratio, centred like the snapshot below.
        if (liveConnected && state.width > 0 && state.height > 0) {
            StreamVideo(appState.stream, Modifier.aspectRatio(state.width.toFloat() / state.height))
        }
        Canvas(Modifier.fillMaxSize()) {
            // While live, the video above shows the desktop; otherwise the last snapshot.
            val bmp = (if (liveConnected) null else snapshot) ?: return@Canvas
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
        if (!liveConnected && snapshot == null) {
            val text = when (state.status) {
                LiveStatus.Connecting -> "Connecting…"
                else -> listOfNotNull(state.detail.takeIf { it.isNotBlank() }, snapshotError?.let { "Snapshot: $it" })
                    .joinToString("\n").ifBlank { "Office desktop is not connected" }
            }
            Text(text, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
        }
        if (!liveConnected) SnapshotLabel(snapshotAt.takeIf { snapshot != null }, snapshotError, Modifier.align(Alignment.BottomStart))
    }
}

/** "Snapshot · 17:33:05", or mobd's reason snapshots fail (locked, asleep, no Screen Recording). */
@Composable
private fun SnapshotLabel(at: java.time.LocalTime?, error: String?, modifier: Modifier) {
    val text = when {
        error != null && at != null -> "Snapshot · ${at.format(SNAPSHOT_TIME)} · not updating"
        error != null -> "Snapshot unavailable: ${error.removePrefix("screen unavailable: ")}"
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
    val live by appState.live.collectAsState()
    val state by live.state.collectAsState()
    val (color, label) = when (state.status) {
        LiveStatus.Connected -> Live to (if (state.control) "Controlling" else "Live")
        LiveStatus.Connecting -> Waiting to "Connecting"
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

/** Live view quality for the active machine; picking one reconnects the stream, keeping control. */
@Composable
private fun QualityMenu(appState: AppState) {
    val machines by appState.machines.collectAsState()
    val activeId by appState.activeMachineId.collectAsState()
    val machine = machines.firstOrNull { it.id == activeId } ?: return
    val value = LiveQuality.of(machine.liveQuality)
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Icon(Icons.Filled.Speed, null, tint = Color.White, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(value.label, color = Color.White)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            LiveQuality.entries.forEach { q ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(q.label + if (q == value) "  ✓" else "", style = MaterialTheme.typography.bodyMedium)
                            Text(q.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    onClick = {
                        open = false
                        if (q != value) appState.setLiveQuality(machine.id, q)
                    },
                )
            }
        }
    }
}

/** What arrives, once a second: signal bars from mobd's loss and the measured round trip, then the readout. */
@Composable
private fun Readout(stream: StreamSession, width: Int, height: Int) {
    val stats by stream.stats.collectAsState()
    val bars = signalBars(stats.loss, stats.readout?.rttMs)
    val on = when (bars) { 3 -> Live; 2 -> Waiting; else -> Color(0xFFEF4444) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.Bottom) {
        (1..3).forEach { i ->
            Box(Modifier.padding(end = 2.dp).width(3.dp).height((i * 4).dp).background(if (i <= bars) on else Color.White.copy(alpha = 0.2f), RoundedCornerShape(1.dp)))
        }
        Spacer(Modifier.width(6.dp))
        Text(readoutText(width, height, stats.readout), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
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
    session: LiveSession,
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
                    session.click(last.x, last.y, LiveKeys.BUTTON_RIGHT)
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
                        Gesture.Scroll -> if (session.pixelScroll) {
                            // mobd scrolls by desktop pixels: the fingers' travel, through the zoom.
                            val (scale, _) = view()
                            val s = min(size.width.toFloat() / fw, size.height.toFloat() / fh) * scale
                            val at = remote(event.calculateCentroid())
                            session.scrollPixels(at.x, at.y, -pan.x / s, -pan.y / s)
                        } else {
                            // Natural scrolling: fingers moving up scroll the page down.
                            wheel -= pan
                            val dx = (wheel.x / step).toInt()
                            val dy = (wheel.y / step).toInt()
                            if (dx != 0 || dy != 0) {
                                val at = remote(event.calculateCentroid())
                                session.scroll(at.x, at.y, dx, dy)
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
                    session.pointer(start.x, start.y, 0)
                    session.pointer(start.x, start.y, LiveKeys.BUTTON_LEFT)
                }
                if (mode == Gesture.Drag) {
                    last = remote(change.position)
                    session.pointer(last.x, last.y, LiveKeys.BUTTON_LEFT)
                    onTouch(last)
                    change.consume()
                }
            }
            when (mode) {
                Gesture.Undecided -> {
                    last = remote(down.position)
                    session.click(last.x, last.y)
                    onTouch(last)
                }
                Gesture.Drag -> session.pointer(last.x, last.y, 0)
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
private fun KeysBar(session: LiveSession, onKeyboard: () -> Unit) {
    val mods by session.modifiers.collectAsState()
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
            "⌘" to LiveKeys.XK_META_L, "⌥" to LiveKeys.XK_ALT_L,
            "⌃" to LiveKeys.XK_CONTROL_L, "⇧" to LiveKeys.XK_SHIFT_L,
        ).forEach { (label, sym) -> Key(label, "Hold $label for the next key", sym in mods) { session.toggleModifier(sym) } }
        Key("esc", "Escape") { session.tapKey(LiveKeys.XK_ESCAPE) }
        Key("tab", "Tab") { session.tapKey(LiveKeys.XK_TAB) }
        Key("←", "Left") { session.tapKey(LiveKeys.XK_LEFT) }
        Key("↑", "Up") { session.tapKey(LiveKeys.XK_UP) }
        Key("↓", "Down") { session.tapKey(LiveKeys.XK_DOWN) }
        Key("→", "Right") { session.tapKey(LiveKeys.XK_RIGHT) }
        Key("⌫", "Delete") { session.tapKey(LiveKeys.XK_BACKSPACE) }
        Key("⏎", "Return") { session.tapKey(LiveKeys.XK_RETURN) }
        Key("Paste", "Type the phone's clipboard") { clipboard.getText()?.text?.let(session::typeText) }
    }
}

/**
 * Invisible field that receives the phone keyboard. It always holds one space so a
 * backspace on an "empty" field is still seen; typed text is forwarded and cleared.
 */
@Composable
private fun KeyboardSink(session: LiveSession, focus: FocusRequester) {
    val blank = TextFieldValue(" ", TextRange(1))
    var value by remember { mutableStateOf(blank) }
    BasicTextField(
        value = value,
        onValueChange = { v ->
            val old = value.text
            val new = v.text
            when {
                new.length < old.length -> repeat(old.length - new.length) { session.tapKey(LiveKeys.XK_BACKSPACE) }
                new.startsWith(old) -> new.substring(old.length).forEach(session::type)
            }
            value = blank
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { session.tapKey(LiveKeys.XK_RETURN) }),
        // Surface forces full-size children; without wrapContentSize this invisible field covers
        // the screen and swallows every touch meant for the desktop.
        modifier = Modifier.wrapContentSize(Alignment.TopStart).size(1.dp).alpha(0f).focusRequester(focus),
    )
}
