package com.remotecontrol.ui.remote

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Backspace
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.KeyboardReturn
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tab
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.remotecontrol.data.ConnectionState
import com.remotecontrol.data.RemoteSession
import com.remotecontrol.data.ScreenStatus
import com.remotecontrol.input.KeyMap
import com.remotecontrol.ui.components.ConnectionPill
import com.remotecontrol.ui.components.KeyChip
import com.remotecontrol.ui.components.TopBar
import com.remotecontrol.ui.theme.MonoSmallTextStyle
import com.remotecontrol.ui.theme.MonoTextStyle
import com.remotecontrol.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAP_PRESS_MS = 60L

@Composable
fun RemoteScreen(session: RemoteSession, onBack: () -> Unit) {
    val connection by session.connection.collectAsStateWithLifecycle()
    val status by session.screenStatus.collectAsStateWithLifecycle()
    val ready = connection == ConnectionState.READY
    val scope = rememberCoroutineScope()

    var frame by remember { mutableStateOf<RemoteFrame?>(null) }
    var keyboardOpen by rememberSaveable { mutableStateOf(false) }
    var latched by remember { mutableStateOf(setOf<String>()) }

    // Newest-wins frame pipeline: decode off the main thread, always keep the
    // latest image, never build up a queue.
    LaunchedEffect(Unit) {
        session.frames.collect { bytes ->
            val decoded = withContext(Dispatchers.Default) { decodeFrame(bytes) }
            if (decoded != null) frame = decoded
        }
    }

    // Opening this screen starts the laptop's capture; the Stop chip ends it.
    LaunchedEffect(ready) {
        if (ready) session.requestCapture(true)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.Background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        TopBar(
            title = "Remote",
            onBack = onBack,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ConnectionPill(state = connection)
                    Spacer(Modifier.width(10.dp))
                    KeyChip(
                        text = if (status.active) "Stop" else "Start",
                        icon = if (status.active) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                        active = status.active,
                        contentColor = Palette.Warning,
                        enabled = ready,
                        onClick = { session.requestCapture(!status.active) },
                    )
                }
            },
        )

        ScreenStage(
            frame = frame,
            status = status,
            ready = ready,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            onMove = { normalized -> session.pointerMove(normalized.x, normalized.y) },
            onTap = {
                session.pointerButton("left", true)
                scope.launch {
                    delay(TAP_PRESS_MS)
                    session.pointerButton("left", false)
                }
            },
            onScroll = { dy -> session.pointerScroll(dy) },
            onPressStart = { session.pointerButton("left", true) },
            onPressEnd = { session.pointerButton("left", false) },
        )

        PointerBar(
            enabled = ready,
            onClick = { button ->
                session.pointerButton(button, true)
                session.pointerButton(button, false)
            },
            keyboardOpen = keyboardOpen,
            onToggleKeyboard = { keyboardOpen = !keyboardOpen },
        )

        if (keyboardOpen) {
            KeyboardPanel(
                enabled = ready,
                latched = latched,
                onLatch = { modifier ->
                    if (modifier in latched) {
                        latched = latched - modifier
                        session.key(modifier, false)
                    } else {
                        latched = latched + modifier
                        session.key(modifier, true)
                    }
                },
                onKey = { key ->
                    if (latched.isEmpty()) {
                        session.tapKey(key)
                    } else {
                        session.sendCombo(latched.toList(), key)
                        latched = emptySet()
                    }
                },
                onSendRaw = { raw -> sendPlan(session, raw) },
            )
        }

        Spacer(Modifier.height(6.dp))
    }
}

/** Mirrors `remote.js`'s combo bar: a combination, a single key, or typed text. */
private fun sendPlan(session: RemoteSession, raw: String) {
    when (val plan = KeyMap.plan(raw)) {
        is KeyMap.Plan.Tap -> session.tapKey(plan.key)
        is KeyMap.Plan.Combination -> session.sendCombo(plan.combo.modifiers, plan.combo.key)
        is KeyMap.Plan.Typing -> session.typeText(raw)
        null -> Unit
    }
}

@Composable
private fun ScreenStage(
    frame: RemoteFrame?,
    status: ScreenStatus,
    ready: Boolean,
    modifier: Modifier = Modifier,
    onMove: (Offset) -> Unit,
    onTap: () -> Unit,
    onScroll: (Float) -> Unit,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit,
) {
    var stageSize by remember { mutableStateOf(IntSize.Zero) }
    val dragThresholdPx = with(LocalDensity.current) { 8.dp.toPx() }

    val imageBox: Rect = remember(stageSize, frame?.width, frame?.height) {
        val box = ScreenGeometry.containRect(
            frameWidth = frame?.width ?: 0,
            frameHeight = frame?.height ?: 0,
            stageWidth = stageSize.width,
            stageHeight = stageSize.height,
        )
        Rect(
            offset = Offset(box.left, box.top),
            size = Size(box.width, box.height),
        )
    }

    // Touches are only mapped from the rectangle we actually draw into; the
    // letterbox around a non-matching aspect ratio is inert.
    val toNormalized: (Offset) -> NormalizedPoint? = { position ->
        val box = ScreenGeometry.containRect(
            frameWidth = frame?.width ?: 0,
            frameHeight = frame?.height ?: 0,
            stageWidth = stageSize.width,
            stageHeight = stageSize.height,
        )
        ScreenGeometry.normalize(position.x, position.y, box)
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.BackgroundDeep)
            .border(1.dp, Palette.BorderSoft, RoundedCornerShape(16.dp))
            .onSizeChanged { stageSize = it }
            .laptopScreenGestures(
                dragThresholdPx = dragThresholdPx,
                mappable = { position -> toNormalized(position) != null },
                handler = ScreenGestureHandler(
                    onMove = { position -> toNormalized(position)?.let { onMove(Offset(it.x, it.y)) } },
                    onTap = { position ->
                        toNormalized(position)?.let {
                            onMove(Offset(it.x, it.y))
                            onTap()
                        }
                    },
                    onScroll = onScroll,
                    onPressStart = onPressStart,
                    onPressEnd = onPressEnd,
                ),
            ),
    ) {
        if (frame != null) {
            Canvas(Modifier.fillMaxSize()) { drawFrame(frame, imageBox) }
        }

        val hint = when {
            !ready -> "Waiting for the relay…"
            frame != null -> null
            status.active -> "Starting capture…"
            else -> status.message.ifEmpty { "Start capture to see the laptop screen" }
        }
        if (hint != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Rounded.Terminal,
                        contentDescription = null,
                        tint = Palette.TextGhost.copy(alpha = 0.4f),
                        modifier = Modifier.size(34.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.TextGhost,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        if (frame != null) {
            Text(
                text = "${frame.width}x${frame.height}",
                style = MonoSmallTextStyle,
                color = Palette.TextMuted,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xC20B0D10))
                    .padding(horizontal = 9.dp, vertical = 4.dp),
            )
        }
    }
}

private fun DrawScope.drawFrame(frame: RemoteFrame, imageBox: Rect) {
    drawImage(
        image = frame.bitmap.asImageBitmap(),
        dstOffset = IntOffset(imageBox.left.toInt(), imageBox.top.toInt()),
        dstSize = IntSize(imageBox.width.toInt(), imageBox.height.toInt()),
    )
}

@Composable
private fun PointerBar(
    enabled: Boolean,
    onClick: (String) -> Unit,
    keyboardOpen: Boolean,
    onToggleKeyboard: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KeyChip(text = "Left", onClick = { onClick("left") }, enabled = enabled)
        KeyChip(text = "Right", onClick = { onClick("right") }, enabled = enabled)
        KeyChip(text = "Middle", onClick = { onClick("middle") }, enabled = enabled)
        Spacer(Modifier.weight(1f))
        KeyChip(
            text = "Keys",
            icon = Icons.Rounded.Keyboard,
            active = keyboardOpen,
            contentColor = Palette.AccentBlue,
            onClick = onToggleKeyboard,
        )
    }
}

private val MODIFIER_LABELS = listOf(
    KeyMap.CTRL to "Ctrl",
    KeyMap.ALT to "Alt",
    KeyMap.SHIFT to "Shift",
    KeyMap.SUPER to "Super",
)

/** Keys a phone keyboard cannot reliably produce on its own. */
private val SPECIAL_KEYS = listOf(
    "ESC",
    "TAB",
    "ENTER",
    "BACKSPACE",
    "SPACE",
    "UP",
    "DOWN",
    "LEFT",
    "RIGHT",
    "HOME",
    "END",
    "PAGEUP",
    "PAGEDOWN",
    "DELETE",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeyboardPanel(
    enabled: Boolean,
    latched: Set<String>,
    onLatch: (String) -> Unit,
    onKey: (String) -> Unit,
    onSendRaw: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.SurfaceRaised)
            .border(1.dp, Palette.BorderSoft, RoundedCornerShape(16.dp))
            .padding(10.dp),
    ) {
        Text(
            text = "Tap a modifier, then a key to build a combo (e.g. Ctrl + C)",
            style = MaterialTheme.typography.labelSmall,
            color = Palette.TextGhost,
        )

        Spacer(Modifier.height(8.dp))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            maxItemsInEachRow = 4,
        ) {
            MODIFIER_LABELS.forEach { (modifier, label) ->
                KeyChip(
                    text = label,
                    active = modifier in latched,
                    contentColor = Palette.AccentBlue,
                    enabled = enabled,
                    onClick = { onLatch(modifier) },
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            maxItemsInEachRow = 4,
        ) {
            SPECIAL_KEYS.forEach { key ->
                KeyChip(text = key, onClick = { onKey(key) }, enabled = enabled)
            }
        }

        Spacer(Modifier.height(10.dp))
        ComboBar(enabled = enabled, onSend = onSendRaw)
    }
}

@Composable
private fun ComboBar(
    enabled: Boolean,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by rememberSaveable { mutableStateOf("") }

    fun submit() {
        val value = text.trim()
        if (!enabled || value.isEmpty()) return
        onSend(value)
        text = ""
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.Surface)
            .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.Keyboard,
            contentDescription = null,
            tint = Palette.TextGhost,
            modifier = Modifier.padding(end = 8.dp),
        )
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            singleLine = true,
            placeholder = {
                Text(
                    text = "Type text or keys (CTRL+ALT+T)",
                    style = MonoTextStyle,
                    color = Palette.TextGhost,
                )
            },
            textStyle = MonoTextStyle,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                imeAction = ImeAction.Send,
            ),
            keyboardActions = KeyboardActions(onSend = { submit() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Palette.Border,
                unfocusedBorderColor = Palette.Border,
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedTextColor = Palette.TextPrimary,
                unfocusedTextColor = Palette.TextPrimary,
                cursorColor = Palette.AccentBlue,
            ),
        )
        KeyChip(
            text = "Send",
            enabled = enabled && text.isNotBlank(),
            active = true,
            contentColor = Palette.AccentBlue,
            onClick = { submit() },
        )
    }
}