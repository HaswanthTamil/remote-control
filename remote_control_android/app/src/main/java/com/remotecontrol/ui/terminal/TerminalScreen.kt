package com.remotecontrol.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.remotecontrol.data.ConnectionState
import com.remotecontrol.data.LineKind
import com.remotecontrol.data.RemoteSession
import com.remotecontrol.data.TerminalLine
import com.remotecontrol.ui.components.CenteredEmptyState
import com.remotecontrol.ui.components.ConnectionPill
import com.remotecontrol.ui.components.HairLine
import com.remotecontrol.ui.components.IconAction
import com.remotecontrol.ui.components.KeyChip
import com.remotecontrol.ui.components.TopBar
import com.remotecontrol.ui.theme.Palette
import com.remotecontrol.ui.theme.Radii
import com.remotecontrol.ui.theme.Space
import com.remotecontrol.ui.theme.TerminalTextStyle

private val QUICK_COMMANDS = listOf("pwd", "ls -la", "uname -a", "uptime", "free -h", "df -h", "top -b -n1")

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun TerminalScreen(session: RemoteSession, onBack: () -> Unit) {
    val connection by session.connection.collectAsStateWithLifecycle()
    val lines by session.lines.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }

    var input by rememberSaveable { mutableStateOf("") }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var historyIndex by rememberSaveable { mutableIntStateOf(-1) }
    var draft by rememberSaveable { mutableStateOf("") }
    val history = remember(session) { session.settings.commandHistory() }
    val connected = connection == ConnectionState.READY

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
    }

    LaunchedEffect(connected) {
        if (connected) runCatching { focusRequester.requestFocus() }
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
            title = "Terminal",
            subtitle = when (connection) {
                ConnectionState.READY -> "Remote shell · ready"
                ConnectionState.SUPERSEDED -> "Slot taken by another client"
                else -> "Not connected"
            },
            compact = true,
            onBack = onBack,
            modifier = Modifier.padding(horizontal = Space.md),
            trailing = { ConnectionPill(state = connection) },
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Palette.BackgroundDeep)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(lines) { _, line -> TerminalRow(line) }
            }

            if (lines.isEmpty()) {
                CenteredEmptyState(
                    icon = Icons.Rounded.Terminal,
                    title = "Nothing has run yet",
                    body = if (connected) {
                        "Commands you send appear here, with their output."
                    } else {
                        "Waiting for the relay to authenticate this phone."
                    },
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Space.sm)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Space.md),
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            QUICK_COMMANDS.forEach { quick ->
                KeyChip(
                    text = quick,
                    onClick = { input = quick },
                    contentColor = Palette.AccentBlue,
                )
            }
        }

        if (showHistory) {
            ModalBottomSheet(
                onDismissRequest = { showHistory = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = Palette.SurfaceRaised,
                contentColor = Palette.TextPrimary,
                dragHandle = { HairLine(modifier = Modifier.padding(vertical = Space.sm)) },
            ) {
                HistoryPanel(
                    history = history,
                    onPick = {
                        input = it
                        showHistory = false
                    },
                )
            }
        }

        CommandBar(
            value = input,
            onValueChange = { input = it },
            enabled = connected,
            historyLabel = if (historyIndex >= 0) "${historyIndex + 1}/${history.size}" else null,
            onSubmit = {
                session.runCommand(input)
                input = ""
                historyIndex = -1
                draft = ""
            },
            onHistory = { direction ->
                if (history.isEmpty()) return@CommandBar
                if (historyIndex < 0) draft = input
                historyIndex = (historyIndex + direction).coerceIn(-1, history.lastIndex)
                input = if (historyIndex < 0) draft else history[historyIndex]
            },
            onToggleHistory = { showHistory = !showHistory },
            historyOpen = showHistory,
            onClear = { session.clearTerminal() },
            onInterrupt = { session.interrupt() },
            focusRequester = focusRequester,
        )

        Spacer(Modifier.height(Space.sm))
    }
}

@Composable
private fun TerminalRow(line: TerminalLine) {
    val color = when (line.kind) {
        LineKind.Output -> Palette.TerminalText
        LineKind.Error -> Palette.Danger
        LineKind.Command -> Palette.AccentBlue
        LineKind.Muted -> Palette.TextGhost
        LineKind.System -> Palette.Warning
    }
    Row {
        if (line.kind == LineKind.Command) {
            Text("$ ", style = TerminalTextStyle, color = Palette.TextMuted)
        }
        Text(text = line.text, style = TerminalTextStyle, color = color)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryPanel(history: List<String>, onPick: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 380.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Space.md)
            .padding(bottom = Space.xxl),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.History,
                contentDescription = null,
                tint = Palette.TextGhost,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(Space.sm))
            Text(
                text = "Command history",
                style = MaterialTheme.typography.titleMedium,
                color = Palette.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (history.isEmpty()) "empty" else "${history.size} saved",
                style = MaterialTheme.typography.labelSmall,
                color = Palette.TextGhost,
            )
        }

        if (history.isEmpty()) {
            Text(
                text = "Commands you run are kept here so you can reuse them.",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextGhost,
                modifier = Modifier.padding(vertical = Space.lg),
            )
        }

        history.forEachIndexed { index, command ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(Radii.chip)
                    .clickable { onPick(command) }
                    .padding(horizontal = Space.md, vertical = Space.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${history.size - index}",
                    style = TerminalTextStyle,
                    color = Palette.TextGhost,
                    modifier = Modifier.width(28.dp),
                )
                Text("$ ", style = TerminalTextStyle, color = Palette.TextGhost)
                Text(
                    text = command,
                    style = TerminalTextStyle,
                    color = Palette.TextSecondary,
                    maxLines = 1,
                )
            }
            HairLine()
        }
    }
}

@Composable
private fun CommandBar(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    historyLabel: String?,
    onSubmit: () -> Unit,
    onHistory: (Int) -> Unit,
    onToggleHistory: () -> Unit,
    historyOpen: Boolean,
    onClear: () -> Unit,
    onInterrupt: () -> Unit,
    focusRequester: FocusRequester,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.md, vertical = Space.sm)
            .clip(Radii.card)
            .background(Palette.SurfaceRaised)
            .border(1.dp, Palette.BorderSoft, Radii.card)
            .padding(Space.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$", style = TerminalTextStyle, color = Palette.Accent)
            Spacer(Modifier.width(Space.sm))
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
                singleLine = true,
                placeholder = {
                    Text(
                        text = if (enabled) "Type a command…" else "Waiting for the relay…",
                        style = TerminalTextStyle,
                        color = Palette.TextGhost,
                    )
                },
                textStyle = TerminalTextStyle,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { if (enabled && value.isNotBlank()) onSubmit() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Palette.Border,
                    unfocusedBorderColor = Palette.Border,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedTextColor = Palette.TextPrimary,
                    unfocusedTextColor = Palette.TextPrimary,
                    cursorColor = Palette.Accent,
                ),
            )
        }

        Spacer(Modifier.height(Space.sm))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            KeyChip(text = "↑", onClick = { onHistory(-1) }, icon = Icons.Rounded.KeyboardArrowUp)
            KeyChip(text = "↓", onClick = { onHistory(1) }, icon = Icons.Rounded.KeyboardArrowDown)
            KeyChip(
                text = historyLabel ?: "History",
                onClick = onToggleHistory,
                icon = Icons.Rounded.History,
                active = historyOpen,
            )
            Spacer(Modifier.weight(1f))
            KeyChip(text = "Clear", onClick = onClear, icon = Icons.Rounded.DeleteSweep)
            KeyChip(
                text = "SIGINT",
                onClick = onInterrupt,
                icon = Icons.Rounded.Stop,
                active = enabled,
                contentColor = Palette.Warning,
            )
            KeyChip(
                text = "Run",
                icon = Icons.AutoMirrored.Rounded.Send,
                enabled = enabled && value.isNotBlank(),
                active = true,
                contentColor = Palette.Accent,
                onClick = onSubmit,
            )
        }
    }
}