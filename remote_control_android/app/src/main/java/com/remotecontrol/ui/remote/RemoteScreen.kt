package com.remotecontrol.ui.remote

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
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
import com.remotecontrol.ui.components.IconAction
import com.remotecontrol.ui.components.KeyChip
import com.remotecontrol.ui.components.TopBar
import com.remotecontrol.ui.theme.MonoSmallTextStyle
import com.remotecontrol.ui.theme.MonoTextStyle
import com.remotecontrol.ui.theme.Palette
import com.remotecontrol.ui.theme.Radii
import com.remotecontrol.ui.theme.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAP_PRESS_MS = 60L

/** Below this share of the stage, the frame is worth rotating for. */
private const val LOW_FILL = 0.62f

@Composable
fun RemoteScreen(session: RemoteSession, onBack: () -> Unit) {
    val connection by session.connection.collectAsStateWithLifecycle()
    val status by session.screenStatus.collectAsStateWithLifecycle()
    val ready = connection == ConnectionState.READY
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    var frame by remember { mutableStateOf<RemoteFrame?>(null) }
    var keyboardOpen by rememberSaveable { mutableStateOf(false) }
    var latched by remember { mutableStateOf(setOf<String>()) }
    var forceLandscape by rememberSaveable { mutableStateOf(false) }
    var hintDismissed by rememberSaveable { mutableStateOf(false) }
    var fill by remember { mutableFloatStateOf(1f) }

    val configuration = LocalConfiguration.current
    val wide = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    PinLandscape(forceLandscape)

    // Newest-wins frame pipeline: decode off the main thread, always keep the
    // latest image, never build up a queue.
    LaunchedEffect(Unit) {
        session.frames.collect { bytes ->
            val decoded = withContext(Dispatchers.Default) { decodeFrame(bytes) }
            if (decoded != null) frame = decoded
        }
    }

    // Opening this screen starts the laptop's capture; the Stop control ends it.
    LaunchedEffect(ready) {
        if (ready) session.requestCapture(true)
    }

    fun tick() = haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)

    val onMove: (Offset) -> Unit = { normalized -> session.pointerMove(normalized.x, normalized.y) }
    val onTap: () -> Unit = {
        tick()
        session.pointerButton("left", true)
        scope.launch {
            delay(TAP_PRESS_MS)
            session.pointerButton("left", false)
        }
    }
    val onScroll: (Float) -> Unit = { dy -> session.pointerScroll(dy) }
    val onPressStart: () -> Unit = { tick(); session.pointerButton("left", true) }
    val onPressEnd: () -> Unit = { session.pointerButton("left", false) }
    val onPointerClick: (String) -> Unit = { button ->
        tick()
        session.pointerButton(button, true)
        session.pointerButton(button, false)
    }
    val onToggleCapture: () -> Unit = {
        tick()
        session.requestCapture(!status.active)
    }
    val onToggleKeyboard: () -> Unit = {
        tick()
        keyboardOpen = !keyboardOpen
    }
    val onLatch: (String) -> Unit = { modifier ->
        tick()
        if (modifier in latched) {
            latched = latched - modifier
            session.key(modifier, false)
        } else {
            latched = latched + modifier
            session.key(modifier, true)
        }
    }
    val onKey: (String) -> Unit = { key ->
        tick()
        if (latched.isEmpty()) {
            session.tapKey(key)
        } else {
            session.sendCombo(latched.toList(), key)
            latched = emptySet()
        }
    }
    val onSendRaw: (String) -> Unit = { raw -> sendPlan(session, raw) }

    if (wide) {
        LandscapeStage(
            frame = frame,
            status = status,
            ready = ready,
            connection = connection,
            forceLandscape = forceLandscape,
            onBack = onBack,
            onToggleCapture = onToggleCapture,
            onToggleOrientation = { forceLandscape = !forceLandscape },
            onToggleKeyboard = onToggleKeyboard,
            keyboardOpen = keyboardOpen,
            onPointerClick = onPointerClick,
            onMove = onMove,
            onTap = onTap,
            onScroll = onScroll,
            onPressStart = onPressStart,
            onPressEnd = onPressEnd,
            onFillChange = { fill = it },
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
        ) {
            KeyboardPanel(
                enabled = ready,
                latched = latched,
                onLatch = onLatch,
                onKey = onKey,
                onSendRaw = onSendRaw,
                wide = true,
            )
        }
    } else {
        PortraitStage(
            frame = frame,
            status = status,
            ready = ready,
            connection = connection,
            onBack = onBack,
            onToggleCapture = onToggleCapture,
            onMove = onMove,
            onTap = onTap,
            onScroll = onScroll,
            onPressStart = onPressStart,
            onPressEnd = onPressEnd,
            onFillChange = { fill = it },
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
            showRotateHint = frame != null && fill < LOW_FILL && !hintDismissed,
            onRotate = { forceLandscape = true },
            onDismissHint = { hintDismissed = true },
            onToggleKeyboard = { keyboardOpen = !keyboardOpen },
            keyboardOpen = keyboardOpen,
        ) {
            Column {
                KeyboardPanel(
                    enabled = ready,
                    latched = latched,
                    onLatch = onLatch,
                    onKey = onKey,
                    onSendRaw = onSendRaw,
                )
            }
        }
    }
}

/* ------------------------------------------------------------------ *
 * Orientation control
 * ------------------------------------------------------------------ */

/**
 * Pins the activity to landscape while [enabled]. Released on dispose so
 * leaving the mirror restores whatever the user had before.
 */
@Composable
private fun PinLandscape(enabled: Boolean) {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity, enabled) {
        activity?.requestedOrientation = if (enabled) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/* ------------------------------------------------------------------ *
 * Portrait
 * ------------------------------------------------------------------ */

@Composable
private fun PortraitStage(
    frame: RemoteFrame?,
    status: ScreenStatus,
    ready: Boolean,
    connection: ConnectionState,
    onBack: () -> Unit,
    onToggleCapture: () -> Unit,
    onMove: (Offset) -> Unit,
    onTap: () -> Unit,
    onScroll: (Float) -> Unit,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit,
    onFillChange: (Float) -> Unit,
    showRotateHint: Boolean,
    onRotate: () -> Unit,
    onDismissHint: () -> Unit,
    onToggleKeyboard: () -> Unit,
    modifier: Modifier = Modifier,
    keyboardOpen: Boolean,
    bottomPanel: @Composable () -> Unit,
) {
    Column(modifier) {
        TopBar(
            title = "Remote",
            subtitle = status.caption(),
            onBack = onBack,
            compact = true,
            modifier = Modifier.padding(horizontal = Space.md),
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ConnectionPill(state = connection)
                    Spacer(Modifier.width(Space.sm))
                    IconAction(
                        icon = Icons.Rounded.Keyboard,
                        contentDescription = if (keyboardOpen) "Hide keyboard" else "Show keyboard",
                        onClick = onToggleKeyboard,
                        enabled = ready,
                        tint = if (keyboardOpen) Palette.Accent else Palette.TextPrimary,
                    )
                    Spacer(Modifier.width(Space.sm))
                    IconAction(
                        icon = if (status.active) Icons.Rounded.StopCircle else Icons.Rounded.PlayArrow,
                        contentDescription = if (status.active) "Stop capture" else "Start capture",
                        onClick = onToggleCapture,
                        enabled = ready,
                        tint = if (status.active) Palette.Warning else Palette.Accent,
                    )
                }
            },
        )

        ScreenStage(
            frame = frame,
            status = status,
            ready = ready,
            onMove = onMove,
            onTap = onTap,
            onScroll = onScroll,
            onPressStart = onPressStart,
            onPressEnd = onPressEnd,
            onFillChange = onFillChange,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = Space.md),
        )

        AnimatedVisibility(
            visible = showRotateHint,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.md, vertical = Space.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                KeyChip(
                    text = "Rotate for a bigger view",
                    icon = Icons.Rounded.ScreenRotation,
                    onClick = onRotate,
                    contentColor = Palette.AccentBlue,
                    modifier = Modifier.weight(1f),
                )
                IconAction(
                    icon = Icons.Rounded.Close,
                    contentDescription = "Dismiss",
                    onClick = onDismissHint,
                    size = 36.dp,
                    container = Color.Transparent,
                    borderColor = Color.Transparent,
                    tint = Palette.TextGhost,
                )
            }
        }

        AnimatedVisibility(
            visible = keyboardOpen,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            bottomPanel()
        }
        Spacer(Modifier.height(Space.sm))
    }
}

/* ------------------------------------------------------------------ *
 * Landscape - the mirror runs full-bleed, controls float on top
 * ------------------------------------------------------------------ */

@Composable
private fun LandscapeStage(
    frame: RemoteFrame?,
    status: ScreenStatus,
    ready: Boolean,
    connection: ConnectionState,
    forceLandscape: Boolean,
    onBack: () -> Unit,
    onToggleCapture: () -> Unit,
    onToggleOrientation: () -> Unit,
    onToggleKeyboard: () -> Unit,
    keyboardOpen: Boolean,
    onPointerClick: (String) -> Unit,
    onMove: (Offset) -> Unit,
    onTap: () -> Unit,
    onScroll: (Float) -> Unit,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit,
    onFillChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    bottomPanel: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .background(Palette.BackgroundDeep),
    ) {
        ScreenStage(
            frame = frame,
            status = status,
            ready = ready,
            onMove = onMove,
            onTap = onTap,
            onScroll = onScroll,
            onPressStart = onPressStart,
            onPressEnd = onPressEnd,
            onFillChange = onFillChange,
            modifier = Modifier.fillMaxSize(),
            fillScreen = true,
        )

        /* top-left: back */
        IconAction(
            icon = Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = "Back",
            onClick = onBack,
            container = Palette.Scrim,
            borderColor = Palette.BorderSoft,
            modifier = Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(Space.sm),
        )

        /* top-right: state, capture, orientation */
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(Space.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            ConnectionPill(state = connection, container = true)
            IconAction(
                icon = if (status.active) Icons.Rounded.StopCircle else Icons.Rounded.PlayArrow,
                contentDescription = if (status.active) "Stop capture" else "Start capture",
                onClick = onToggleCapture,
                enabled = ready,
                tint = if (status.active) Palette.Warning else Palette.Accent,
                container = Palette.Scrim,
                borderColor = Palette.BorderSoft,
            )
            IconAction(
                icon = Icons.Rounded.ScreenRotation,
                contentDescription = if (forceLandscape) "Back to portrait" else "Rotate to landscape",
                onClick = onToggleOrientation,
                container = Palette.Scrim,
                borderColor = Palette.BorderSoft,
                tint = Palette.TextSecondary,
            )
        }

        /* bottom: pointer bar, key panel above it */
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AnimatedVisibility(visible = keyboardOpen, enter = fadeIn(), exit = fadeOut()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Space.sm, vertical = Space.xs),
                ) {
                    bottomPanel()
                }
            }

            Row(
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(Space.sm)
                    .clip(Radii.pill)
                    .background(Palette.Scrim)
                    .border(1.dp, Palette.BorderSoft, Radii.pill)
                    .padding(horizontal = Space.sm, vertical = Space.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.xs),
            ) {
                KeyChip(text = "Left", onClick = { onPointerClick("left") }, enabled = ready)
                KeyChip(text = "Right", onClick = { onPointerClick("right") }, enabled = ready)
                KeyChip(text = "Middle", onClick = { onPointerClick("middle") }, enabled = ready)
                Spacer(Modifier.width(Space.xs))
                KeyChip(
                    text = "",
                    icon = Icons.Rounded.Keyboard,
                    active = keyboardOpen,
                    contentColor = Palette.AccentBlue,
                    onClick = onToggleKeyboard,
                )
            }
        }
    }
}

/* ------------------------------------------------------------------ *
 * The mirrored frame
 * ------------------------------------------------------------------ */

@Composable
private fun ScreenStage(
    frame: RemoteFrame?,
    status: ScreenStatus,
    ready: Boolean,
    onMove: (Offset) -> Unit,
    onTap: () -> Unit,
    onScroll: (Float) -> Unit,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit,
    onFillChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    fillScreen: Boolean = false,
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
        Rect(offset = Offset(box.left, box.top), size = Size(box.width, box.height))
    }

    LaunchedEffect(imageBox, stageSize) {
        if (stageSize.height > 0 && imageBox.height > 0f) {
            onFillChange(imageBox.height / stageSize.height.toFloat())
        }
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
            .clip(if (fillScreen) RoundedCornerShape(0.dp) else Radii.stage)
            .background(Palette.BackgroundDeep)
            .then(
                if (fillScreen) {
                    Modifier
                } else {
                    Modifier.border(1.dp, Palette.BorderSoft, Radii.stage)
                },
            )
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
                    .padding(Space.xl),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Rounded.TouchApp,
                        contentDescription = null,
                        tint = Palette.TextGhost.copy(alpha = 0.45f),
                        modifier = Modifier.size(30.dp),
                    )
                    Spacer(Modifier.height(Space.md))
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.TextGhost,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        if (frame != null && !fillScreen) {
            Text(
                text = "${frame.width}×${frame.height}",
                style = MonoSmallTextStyle,
                color = Palette.TextMuted,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(Space.sm)
                    .clip(Radii.chip)
                    .background(Palette.Scrim)
                    .padding(horizontal = Space.sm, vertical = Space.xs),
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

private fun ScreenStatus.caption(): String = when {
    active && message.isNotEmpty() -> message
    active -> "Capturing"
    else -> "Not capturing"
}

/* ------------------------------------------------------------------ *
 * Keys and pointer bar
 * ------------------------------------------------------------------ */

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
    modifier: Modifier = Modifier,
    wide: Boolean = false,
) {
    val hint = if (latched.isEmpty()) {
        "Latch a modifier, then tap a key to send the combo"
    } else {
        "Combo armed: ${latched.joinToString(" + ")} — now tap a key"
    }

    Column(
        modifier = modifier
            .padding(horizontal = Space.md, vertical = Space.sm)
            .clip(Radii.card)
            .background(Palette.SurfaceRaised)
            .border(1.dp, Palette.BorderSoft, Radii.card)
            .padding(Space.md)
            .heightIn(max = if (wide) 172.dp else 320.dp),
    ) {
        Text(
            text = hint,
            style = MaterialTheme.typography.labelSmall,
            color = if (latched.isEmpty()) Palette.TextGhost else Palette.AccentBlue,
            modifier = Modifier.padding(bottom = Space.sm),
        )

        if (wide) {
            /* Landscape: keys on the left, text entry on the right, so the
             * panel never eats the mirror. */
            Row(
                horizontalArrangement = Arrangement.spacedBy(Space.md),
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                ) {
                    ModifierGrid(enabled = enabled, latched = latched, onLatch = onLatch)
                    Spacer(Modifier.height(Space.sm))
                    HairDivider()
                    Spacer(Modifier.height(Space.sm))
                    SpecialGrid(enabled = enabled, onKey = onKey)
                }
                Column(
                    modifier = Modifier.width(190.dp),
                    verticalArrangement = Arrangement.spacedBy(Space.sm),
                ) {
                    ComboBar(enabled = enabled, onSend = onSendRaw)
                    Text(
                        text = "Type a shortcut like CTRL+ALT+T",
                        style = MaterialTheme.typography.labelSmall,
                        color = Palette.TextGhost,
                    )
                }
            }
        } else {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                ModifierGrid(enabled = enabled, latched = latched, onLatch = onLatch)
                Spacer(Modifier.height(Space.md))
                HairDivider()
                Spacer(Modifier.height(Space.md))
                SpecialGrid(enabled = enabled, onKey = onKey)
                Spacer(Modifier.height(Space.md))
                ComboBar(enabled = enabled, onSend = onSendRaw)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModifierGrid(enabled: Boolean, latched: Set<String>, onLatch: (String) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
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
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpecialGrid(enabled: Boolean, onKey: (String) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        SPECIAL_KEYS.forEach { key ->
            KeyChip(text = key, onClick = { onKey(key) }, enabled = enabled)
        }
    }
}

@Composable
private fun HairDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Palette.Divider),
    )
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
            .clip(Radii.control)
            .background(Palette.Surface)
            .border(1.dp, Palette.BorderSoft, Radii.control)
            .padding(start = Space.md, end = Space.xs, top = Space.xs, bottom = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Icon(
            Icons.Rounded.Terminal,
            contentDescription = null,
            tint = Palette.TextGhost,
            modifier = Modifier
                .padding(end = Space.sm)
                .size(17.dp),
        )
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            singleLine = true,
            placeholder = {
                Text(
                    text = "Type text or keys — CTRL+ALT+T",
                    style = MonoTextStyle,
                    color = Palette.TextGhost,
                    maxLines = 1,
                )
            },
            textStyle = MonoTextStyle,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                imeAction = ImeAction.Send,
            ),
            keyboardActions = KeyboardActions(onSend = { submit() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedTextColor = Palette.TextPrimary,
                unfocusedTextColor = Palette.TextPrimary,
                cursorColor = Palette.AccentBlue,
            ),
        )
        Spacer(Modifier.width(Space.xs))
        IconAction(
            icon = Icons.AutoMirrored.Rounded.Send,
            contentDescription = "Send",
            onClick = { submit() },
            enabled = enabled && text.isNotBlank(),
            size = 38.dp,
            container = if (enabled && text.isNotBlank()) Palette.accentWash(Palette.AccentBlue) else Palette.Surface,
            tint = Palette.AccentBlue,
            borderColor = Color.Transparent,
        )
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
