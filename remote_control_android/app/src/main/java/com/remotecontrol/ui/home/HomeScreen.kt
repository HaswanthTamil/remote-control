package com.remotecontrol.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.remotecontrol.data.ConnectionState
import com.remotecontrol.data.RemoteSession
import com.remotecontrol.ui.components.AppTile
import com.remotecontrol.ui.components.ConnectionPill
import com.remotecontrol.ui.components.Eyebrow
import com.remotecontrol.ui.components.GhostButton
import com.remotecontrol.ui.components.SectionCard
import com.remotecontrol.ui.components.shortDisplay
import com.remotecontrol.ui.theme.MonoSmallTextStyle
import com.remotecontrol.ui.theme.MonoTextStyle
import com.remotecontrol.ui.theme.Palette

@Composable
fun HomeScreen(
    session: RemoteSession,
    onOpenTerminal: () -> Unit,
    onOpenRemote: () -> Unit,
    onOpenSettings: () -> Unit,
    onLock: () -> Unit,
) {
    val connection by session.connection.collectAsStateWithLifecycle()
    val config = session.config

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.Background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Spacer(Modifier.height(20.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                Eyebrow("Remote Control")
                Text("Home", style = MaterialTheme.typography.displaySmall, color = Palette.TextPrimary)
            }
            ConnectionPill(state = connection, host = config.relayHost)
        }

        Spacer(Modifier.height(24.dp))

        SectionCard {
            Text(
                text = "Laptop link",
                style = MaterialTheme.typography.titleMedium,
                color = Palette.TextPrimary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = when (connection) {
                    ConnectionState.READY ->
                        "Authenticated with the relay. Commands and screen control are live."

                    ConnectionState.SUPERSEDED ->
                        "Another Remote Control client took over this device slot. Close it and reconnect."

                    else -> "Waiting for the relay to authenticate this phone."
                },
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextMuted,
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = config.serverUrl,
                    style = MonoTextStyle,
                    color = Palette.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.size(12.dp))
                GhostButton(text = "Reconnect", onClick = { session.reconnect() })
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusBadge("Paired", if (session.isPaired) "yes" else "no", session.isPaired)
                StatusBadge("Device", session.deviceId.shortDisplay())
            }
        }

        Spacer(Modifier.height(26.dp))
        Eyebrow("Apps")
        Spacer(Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            AppTile(
                icon = Icons.Rounded.DesktopWindows,
                title = "Remote",
                subtitle = "Screen + input",
                accent = Palette.AccentBlue,
                onClick = onOpenRemote,
                modifier = Modifier.weight(1f),
            )
            AppTile(
                icon = Icons.Rounded.Terminal,
                title = "Terminal",
                subtitle = "Run commands",
                accent = Palette.TextPrimary,
                onClick = onOpenTerminal,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            AppTile(
                icon = Icons.Rounded.Settings,
                title = "Settings",
                subtitle = "Relay & security",
                accent = Palette.Warning,
                onClick = onOpenSettings,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.weight(1f))
        }

        Spacer(Modifier.height(26.dp))
        GhostButton(
            text = "Lock app",
            icon = Icons.Rounded.Lock,
            onClick = onLock,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun StatusBadge(label: String, value: String, active: Boolean = true) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(Palette.Surface)
            .border(1.dp, Palette.BorderSoft, RoundedCornerShape(9.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(if (active) Palette.Accent else Palette.TextGhost),
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = Palette.TextGhost)
        Text(value, style = MonoSmallTextStyle, color = Palette.TextSecondary)
    }
}