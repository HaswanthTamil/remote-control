package com.remotecontrol.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.remotecontrol.data.ConnectionState
import com.remotecontrol.ui.theme.Palette

data class ConnectionUi(
    val label: String,
    val dot: Color,
)

fun ConnectionState.present(): ConnectionUi = when (this) {
    ConnectionState.READY -> ConnectionUi("Ready", Palette.Accent)
    ConnectionState.CONNECTING -> ConnectionUi("Connecting", Palette.Warning)
    ConnectionState.AUTHENTICATING -> ConnectionUi("Authenticating", Palette.Warning)
    ConnectionState.OFFLINE -> ConnectionUi("Offline", Palette.TextGhost)
    ConnectionState.SUPERSEDED -> ConnectionUi("Superseded", Palette.TextGhost)
}

@Composable
fun ConnectionPill(
    state: ConnectionState,
    modifier: Modifier = Modifier,
    host: String? = null,
) {
    val ui = state.present()
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(ui.dot),
        )
        Column {
            Text(
                text = ui.label,
                style = MaterialTheme.typography.labelMedium,
                color = Palette.TextMuted,
            )
            if (host != null) {
                Text(
                    text = host,
                    style = MaterialTheme.typography.labelSmall,
                    color = Palette.TextGhost,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = Palette.TextGhost,
        modifier = modifier,
    )
}

@Composable
fun AppTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, label = "tileScale")

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .scale(scale)
                .clip(RoundedCornerShape(22.dp))
                .background(Palette.Surface)
                .border(1.dp, Palette.BorderSoft, RoundedCornerShape(22.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = Palette.TextPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = Palette.TextGhost,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Palette.SurfaceRaised)
            .border(1.dp, Palette.BorderSoft, RoundedCornerShape(18.dp))
            .padding(16.dp),
        content = content,
    )
}

@Composable
fun InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Palette.TextSecondary,
    monospace: Boolean = false,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.TextMuted,
            modifier = Modifier.width(120.dp),
        )
        Text(
            text = value,
            style = if (monospace) {
                com.remotecontrol.ui.theme.MonoTextStyle
            } else {
                MaterialTheme.typography.bodyMedium
            },
            color = valueColor,
        )
    }
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    container: Color = Palette.TextPrimary,
    contentColor: Color = Palette.Background,
) {
    val background by animateColorAsState(
        if (enabled) container else Palette.ChipBackground,
        label = "primaryButtonBg",
    )
    val foreground by animateColorAsState(
        if (enabled) contentColor else Palette.TextGhost,
        label = "primaryButtonFg",
    )
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = foreground, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = foreground)
    }
}

@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    contentColor: Color = Palette.TextSecondary,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, Palette.Border, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = contentColor)
    }
}

/** Small pill used for pointer buttons, modifiers and quick actions. */
@Composable
fun KeyChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    contentColor: Color? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) 0.94f else 1f, label = "chipScale")
    val activeColor = contentColor ?: Palette.TextPrimary
    val background = when {
        active -> activeColor.copy(alpha = 0.18f)
        else -> Palette.ChipBackground
    }
    val labelColor = when {
        !enabled -> Palette.TextGhost
        active -> activeColor
        else -> Palette.TextSecondary
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(11.dp))
            .background(background)
            .border(
                width = 1.dp,
                color = if (active) activeColor.copy(alpha = 0.55f) else Palette.Border,
                shape = RoundedCornerShape(11.dp),
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .scale(scale)
            .padding(horizontal = 13.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = labelColor,
                modifier = Modifier.size(16.dp),
            )
        }
        if (text.isNotEmpty()) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = labelColor,
            )
        }
    }
}

@Composable
fun TopBar(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = Palette.TextSecondary,
                )
            }
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = LocalContentColor.current,
            modifier = Modifier.weight(1f),
        )
        trailing()
    }
}
/** Placeholder while the identity is still being generated/loaded. */
internal fun String.orPlaceholder(): String = ifEmpty { "Generating…" }

/** Short form for badges: first 12 hex characters, or a placeholder. */
internal fun String.shortDisplay(): String = ifEmpty { "Generating…" }.let {
    if (it.length <= 12) it else it.take(12) + "…"
}
