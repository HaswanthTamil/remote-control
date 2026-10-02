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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.remotecontrol.data.ConnectionState
import com.remotecontrol.data.RemoteSession
import com.remotecontrol.ui.components.ActionRow
import com.remotecontrol.ui.components.ConnectionPill
import com.remotecontrol.ui.components.Eyebrow
import com.remotecontrol.ui.components.GhostButton
import com.remotecontrol.ui.components.SectionCard
import com.remotecontrol.ui.components.SectionHeader
import com.remotecontrol.ui.components.StatusDot
import com.remotecontrol.ui.components.shortDisplay
import com.remotecontrol.ui.theme.MonoSmallTextStyle
import com.remotecontrol.ui.theme.MonoTextStyle
import com.remotecontrol.ui.theme.Palette
import com.remotecontrol.ui.theme.Radii
import com.remotecontrol.ui.theme.Space

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
            .padding(horizontal = Space.gutter),
    ) {
        Spacer(Modifier.height(Space.lg))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Eyebrow("Remote Control")
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Your laptop",
                    style = MaterialTheme.typography.displaySmall,
                    color = Palette.TextPrimary,
                )
            }
            ConnectionPill(state = connection)
        }

        Spacer(Modifier.height(Space.xl))

        /* ---------------- link status ---------------- */

        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(Radii.chip)
                        .background(Palette.accentWash(Palette.AccentBlue))
                        .border(1.dp, Palette.AccentBlue.copy(alpha = 0.22f), Radii.chip),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.material3.Icon(
                        Icons.Rounded.Link,
                        contentDescription = null,
                        tint = Palette.AccentBlue,
                        modifier = Modifier.size(19.dp),
                    )
                }
                Spacer(Modifier.size(Space.md))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = when (connection) {
                            ConnectionState.READY -> "Linked"
                            ConnectionState.SUPERSEDED -> "Slot taken"
                            ConnectionState.OFFLINE -> "Not linked"
                            else -> "Linking"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = Palette.TextPrimary,
                    )
                    Text(
                        text = when (connection) {
                            ConnectionState.READY -> "Commands and screen control are live."
                            ConnectionState.SUPERSEDED ->
                                "Another Remote Control client claimed this slot. Close it, then reconnect."

                            ConnectionState.OFFLINE -> "Waiting for the relay to answer this phone."
                            else -> "Signing the relay challenge."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.TextMuted,
                    )
                }
            }

            Spacer(Modifier.height(Space.lg))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(Radii.chip)
                    .background(Palette.Surface)
                    .padding(horizontal = Space.md, vertical = Space.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = config.serverUrl,
                    style = MonoTextStyle,
                    color = Palette.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(Space.sm))
                androidx.compose.material3.Icon(
                    Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = Palette.TextGhost,
                    modifier = Modifier.size(16.dp),
                )
            }

            Spacer(Modifier.height(Space.md))

            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                StatusBadge(
                    icon = Icons.Rounded.VpnKey,
                    label = "Identity",
                    value = session.deviceId.shortDisplay(),
                    active = session.deviceId.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                )
                StatusBadge(
                    icon = if (session.isPaired) Icons.Rounded.RadioButtonChecked else Icons.Rounded.RadioButtonUnchecked,
                    label = "Paired",
                    value = if (session.isPaired) "yes" else "no",
                    active = session.isPaired,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Spacer(Modifier.height(Space.xxl))

        /* ---------------- actions ---------------- */

        SectionHeader("Control")
        SectionCard(modifier = Modifier.padding(horizontal = 0.dp)) {
            ActionRow(
                icon = Icons.Rounded.Cast,
                title = "Remote screen",
                subtitle = "Watch the laptop and control it with touch",
                accent = Palette.AccentBlue,
                onClick = onOpenRemote,
                trailingText = "Landscape fills best",
            )
            HairDivider()
            ActionRow(
                icon = Icons.Rounded.Terminal,
                title = "Terminal",
                subtitle = "Run commands on the remote shell",
                accent = Palette.Accent,
                onClick = onOpenTerminal,
            )
            HairDivider()
            ActionRow(
                icon = Icons.Rounded.Settings,
                title = "Settings",
                subtitle = "Relay, pairing, security",
                accent = Palette.Warning,
                onClick = onOpenSettings,
            )
        }

        Spacer(Modifier.height(Space.xxl))

        /* ---------------- session ---------------- */

        SectionHeader("Session")
        GhostButton(
            text = "Lock now",
            icon = Icons.Rounded.Lock,
            onClick = onLock,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(Space.md))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            GhostButton(
                text = "Reconnect",
                onClick = { session.reconnect() },
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(Space.xxxl))
    }
}

@Composable
private fun HairDivider() {
    Box(
        Modifier
            .padding(start = Space.md)
            .fillMaxWidth()
            .height(1.dp)
            .background(Palette.Divider),
    )
}

@Composable
private fun StatusBadge(
    icon: ImageVector,
    label: String,
    value: String,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(11.dp))
            .background(Palette.Surface)
            .border(1.dp, Palette.BorderSoft, RoundedCornerShape(11.dp))
            .padding(horizontal = Space.sm, vertical = Space.sm),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(color = if (active) Palette.Accent else Palette.TextGhost, size = 6.dp)
        androidx.compose.material3.Icon(
            icon,
            contentDescription = null,
            tint = if (active) Palette.Accent else Palette.TextGhost,
            modifier = Modifier.size(14.dp),
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = Palette.TextGhost)
        Text(
            text = value,
            style = MonoSmallTextStyle,
            color = Palette.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
