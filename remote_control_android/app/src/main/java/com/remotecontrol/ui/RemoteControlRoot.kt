package com.remotecontrol.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.remotecontrol.data.RemoteSession
import com.remotecontrol.ui.home.HomeScreen
import com.remotecontrol.ui.lock.LockScreen
import com.remotecontrol.ui.lock.rememberBiometricGate
import com.remotecontrol.ui.remote.RemoteScreen
import com.remotecontrol.ui.settings.SettingsScreen
import com.remotecontrol.ui.terminal.TerminalScreen
import com.remotecontrol.ui.theme.RemoteControlTheme

enum class Screen { HOME, TERMINAL, REMOTE, SETTINGS }

@Composable
fun RemoteControlRoot(
    session: RemoteSession,
    locked: Boolean,
    onUnlocked: () -> Unit,
    onLock: () -> Unit,
) {
    val config by session.settings.config.collectAsStateWithLifecycle()
    val gate = rememberBiometricGate()

    RemoteControlTheme {
        if (locked) {
            LockScreen(config = config, gate = gate, onUnlocked = onUnlocked)
            return@RemoteControlTheme
        }

        val backStack = rememberBackStack()
        val current = backStack.last()

        // Re-establish the relay socket after a background lock/unlock cycle.
        LaunchedEffect(Unit) {
            if (!session.isReady) session.connect()
        }

        BackHandler(enabled = backStack.size > 1) { backStack.pop() }
        BackHandler(enabled = backStack.size == 1) { onLock() }

        AnimatedContent(
            targetState = current,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "screen",
            modifier = Modifier.fillMaxSize(),
        ) { screen ->
            when (screen) {
                Screen.HOME -> HomeScreen(
                    session = session,
                    onOpenTerminal = { backStack.push(Screen.TERMINAL) },
                    onOpenRemote = { backStack.push(Screen.REMOTE) },
                    onOpenSettings = { backStack.push(Screen.SETTINGS) },
                    onLock = onLock,
                )

                Screen.TERMINAL -> TerminalScreen(
                    session = session,
                    onBack = { backStack.pop() },
                )

                Screen.REMOTE -> RemoteScreen(
                    session = session,
                    onBack = { backStack.pop() },
                )

                Screen.SETTINGS -> SettingsScreen(
                    session = session,
                    onBack = { backStack.pop() },
                )
            }
        }
    }
}

@Composable
private fun rememberBackStack(): SnapshotStateList<Screen> =
    rememberSaveable(
        saver = listSaver(
            save = { it.map { screen -> screen.name } },
            restore = { restored ->
                mutableStateListOf<Screen>().apply {
                    addAll(restored.mapNotNull { name -> Screen.entries.firstOrNull { it.name == name } })
                    if (isEmpty()) add(Screen.HOME)
                }
            },
        ),
    ) { mutableStateListOf(Screen.HOME) }

private fun SnapshotStateList<Screen>.push(screen: Screen) {
    if (lastOrNull() != screen) add(screen)
}

private fun SnapshotStateList<Screen>.pop() {
    if (size > 1) removeAt(lastIndex)
}