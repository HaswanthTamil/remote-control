package com.remotecontrol.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.remotecontrol.data.ConnectionState
import com.remotecontrol.ui.theme.MonoTextStyle
import com.remotecontrol.ui.theme.Motion
import com.remotecontrol.ui.theme.Palette
import com.remotecontrol.ui.theme.Radii
import com.remotecontrol.ui.theme.Space

/* ------------------------------------------------------------------ *
 * Shared interaction state
 * ------------------------------------------------------------------ */

private data class Press(val interaction: MutableInteractionSource, val pressed: Boolean, val focused: Boolean)

@Composable
private fun rememberPressState(): Press {
    val interaction = remember { MutableInteractionSource() }
    return Press(
        interaction = interaction,
        pressed = interaction.collectIsPressedAsState().value,
        focused = interaction.collectIsFocusedAsState().value,
    )
}

/** Scale-down on press, plus a 2dp accent ring for keyboard/dpad focus. */
@Composable
private fun Modifier.pressable(state: Press, shape: androidx.compose.ui.graphics.Shape): Modifier = this
    .clip(shape)
    .then(
        if (state.focused) {
            Modifier.border(2.dp, Palette.FocusRing, shape)
        } else {
            Modifier
        },
    )
    .scale(if (state.pressed) 0.97f else 1f)

/* ------------------------------------------------------------------ *
 * Status
 * ------------------------------------------------------------------ */

data class ConnectionUi(
    val label: String,
    val dot: Color,
    val detail: String,
)

fun ConnectionState.present(): ConnectionUi = when (this) {
    ConnectionState.READY -> ConnectionUi("Ready", Palette.Accent, "connected")
    ConnectionState.CONNECTING -> ConnectionUi("Connecting", Palette.Warning, "opening socket")
    ConnectionState.AUTHENTICATING -> ConnectionUi("Authenticating", Palette.Warning, "signing the challenge")
    ConnectionState.OFFLINE -> ConnectionUi("Offline", Palette.TextGhost, "no connection")
    ConnectionState.SUPERSEDED -> ConnectionUi("Superseded", Palette.Danger, "another client took the slot")
}

/** A dot that breathes while the connection is in progress. */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 8.dp, pulse: Boolean = false) {
    val transition = rememberInfiniteTransition(label = "dot")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(Motion.SLOW * 2), RepeatMode.Reverse),
        label = "dotAlpha",
    )
    Box(
        modifier
            .size(size)
            .alpha(if (pulse) alpha else 1f)
            .clip(CircleShape)
            .background(color),
    )
}

/**
 * Connection status. Compact by default; pass [container] for a filled pill
 * (the media screens float it over the mirrored image).
 */
@Composable
fun ConnectionPill(
    state: ConnectionState,
    modifier: Modifier = Modifier,
    host: String? = null,
    container: Boolean = false,
) {
    val ui = state.present()
    val busy = state == ConnectionState.CONNECTING || state == ConnectionState.AUTHENTICATING

    if (!container) {
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            StatusDot(color = ui.dot, pulse = busy)
            Column {
                Text(
                    text = ui.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = Palette.TextSecondary,
                )
                if (host != null) {
                    Text(
                        text = host,
                        style = MaterialTheme.typography.labelSmall,
                        color = Palette.TextGhost,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        return
    }

    Row(
        modifier = modifier
            .clip(Radii.pill)
            .background(Palette.Scrim)
            .border(1.dp, Palette.BorderSoft, Radii.pill)
            .padding(horizontal = 11.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        StatusDot(color = ui.dot, pulse = busy)
        Text(
            text = ui.label,
            style = MaterialTheme.typography.labelMedium,
            color = Palette.TextPrimary,
        )
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
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Eyebrow(title, Modifier.weight(1f))
        trailing?.invoke()
    }
}

/* ------------------------------------------------------------------ *
 * Containers
 * ------------------------------------------------------------------ */

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(Radii.card)
            .background(Palette.SurfaceRaised)
            .border(1.dp, Palette.BorderSoft, Radii.card)
            .padding(Space.lg),
        content = content,
    )
}

@Composable
fun HairLine(modifier: Modifier = Modifier, inset: androidx.compose.ui.unit.Dp = 0.dp) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(start = inset)
            .height(1.dp)
            .background(Palette.Divider),
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
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.TextMuted,
            modifier = Modifier.width(112.dp),
        )
        Text(
            text = value,
            style = if (monospace) MonoTextStyle else MaterialTheme.typography.bodyMedium,
            color = valueColor,
            modifier = Modifier.weight(1f),
        )
    }
}

/* ------------------------------------------------------------------ *
 * Navigation rows
 * ------------------------------------------------------------------ */

/**
 * Icon + title + subtitle in a tappable row with a chevron - the Material 3
 * list idiom used across Home and Settings.
 */
@Composable
fun ActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = Palette.AccentBlue,
    enabled: Boolean = true,
    trailingText: String? = null,
) {
    val press = rememberPressState()
    val contentAlpha by animateFloatAsState(if (enabled) 1f else 0.45f, label = "rowAlpha")

    Row(
        modifier = modifier
            .fillMaxWidth()
            .pressable(press, Radii.control)
            .background(if (press.pressed) Palette.SurfaceOverlay else Color.Transparent)
            .clickable(
                interactionSource = press.interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .alpha(contentAlpha)
            .defaultMinSize(minHeight = 64.dp)
            .padding(horizontal = Space.md, vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(Radii.chip)
                .background(Palette.accentWash(accent))
                .border(1.dp, accent.copy(alpha = 0.22f), Radii.chip),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(21.dp))
        }
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Palette.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (trailingText != null) {
            Text(
                text = trailingText,
                style = MaterialTheme.typography.labelMedium,
                color = Palette.TextGhost,
            )
            Spacer(Modifier.width(Space.xs))
        }
        Icon(
            Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = Palette.TextGhost,
            modifier = Modifier.size(20.dp),
        )
    }
}

/* ------------------------------------------------------------------ *
 * Buttons
 * ------------------------------------------------------------------ */

enum class ButtonTone { Neutral, Accent, Blue, Warning, Danger }

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    container: Color = Palette.TextPrimary,
    contentColor: Color = Palette.Background,
) {
    val press = rememberPressState()
    val active = enabled && !loading
    val background by animateColorAsState(
        when {
            !enabled || loading -> Palette.SurfaceOverlay
            press.pressed -> container.copy(alpha = 0.86f)
            else -> container
        },
        label = "primaryBg",
    )
    val foreground by animateColorAsState(
        if (active) contentColor else Palette.TextGhost,
        label = "primaryFg",
    )

    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .pressable(press, Radii.pill)
            .background(background)
            .clickable(
                interactionSource = press.interaction,
                indication = null,
                enabled = active,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = Space.xl, vertical = Space.md),
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            loading -> CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = foreground,
            )

            icon != null -> Icon(icon, contentDescription = null, tint = foreground, modifier = Modifier.size(18.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = foreground,
            maxLines = 1,
        )
    }
}

@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    tone: ButtonTone = ButtonTone.Neutral,
    contentColor: Color = when (tone) {
        ButtonTone.Neutral -> Palette.TextSecondary
        ButtonTone.Accent -> Palette.Accent
        ButtonTone.Blue -> Palette.AccentBlue
        ButtonTone.Warning -> Palette.Warning
        ButtonTone.Danger -> Palette.Danger
    },
) {
    val press = rememberPressState()
    val background by animateColorAsState(
        if (press.pressed && enabled) Palette.PressedOverlay else Color.Transparent,
        label = "ghostBg",
    )
    val foreground by animateColorAsState(
        if (enabled) contentColor else Palette.TextGhost,
        label = "ghostFg",
    )

    Row(
        modifier = modifier
            .heightIn(min = 44.dp)
            .pressable(press, Radii.control)
            .background(background)
            .border(1.dp, if (enabled) Palette.Border else Palette.BorderSoft, Radii.control)
            .clickable(
                interactionSource = press.interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = Space.lg, vertical = Space.md),
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = foreground, modifier = Modifier.size(18.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = foreground,
            maxLines = 1,
        )
    }
}

/** Square icon-only control for app bars and floating media overlays. */
@Composable
fun IconAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = Palette.TextPrimary,
    container: Color = Palette.SurfaceRaised,
    borderColor: Color = Palette.BorderSoft,
    size: androidx.compose.ui.unit.Dp = 40.dp,
) {
    val press = rememberPressState()
    val background by animateColorAsState(
        when {
            !enabled -> Palette.Surface.copy(alpha = 0.6f)
            press.pressed -> container.copy(alpha = 0.75f)
            else -> container
        },
        label = "iconActionBg",
    )
    val foreground by animateColorAsState(if (enabled) tint else Palette.TextGhost, label = "iconActionFg")

    Box(
        modifier = modifier
            .size(size)
            .pressable(press, CircleShape)
            .background(background, CircleShape)
            .border(BorderStroke(1.dp, borderColor), CircleShape)
            .clickable(
                interactionSource = press.interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = foreground, modifier = Modifier.size(19.dp))
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
    val press = rememberPressState()
    val accent = contentColor ?: Palette.TextPrimary
    val background by animateColorAsState(
        when {
            active -> Palette.accentWash(accent, 0.18f)
            press.pressed -> Palette.SurfaceOverlay
            else -> Palette.ChipBackground
        },
        label = "chipBg",
    )
    val labelColor by animateColorAsState(
        when {
            !enabled -> Palette.TextGhost
            active -> accent
            press.pressed -> Palette.TextPrimary
            else -> Palette.TextSecondary
        },
        label = "chipFg",
    )

    Row(
        modifier = modifier
            .pressable(press, Radii.chip)
            .background(background)
            .border(
                width = 1.dp,
                color = if (active) accent.copy(alpha = 0.55f) else Palette.BorderSoft,
                shape = Radii.chip,
            )
            .clickable(
                interactionSource = press.interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = Space.md, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = labelColor, modifier = Modifier.size(15.dp))
        }
        if (text.isNotEmpty()) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = labelColor,
                maxLines = 1,
            )
        }
    }
}

/* ------------------------------------------------------------------ *
 * App bar
 * ------------------------------------------------------------------ */

/**
 * Compact app bar. Media screens ([compact]) drop the large title and let the
 * screen run full-bleed underneath.
 */
@Composable
fun TopBar(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    subtitle: String? = null,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconAction(
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Back",
                onClick = onBack,
                container = Color.Transparent,
                borderColor = Color.Transparent,
            )
            Spacer(Modifier.width(Space.sm))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = if (compact) {
                    MaterialTheme.typography.titleLarge
                } else {
                    MaterialTheme.typography.headlineSmall
                },
                color = LocalContentColor.current,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = Palette.TextGhost,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing()
    }
}

/* ------------------------------------------------------------------ *
 * Small utilities
 * ------------------------------------------------------------------ */

@Composable
fun CenteredEmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(Space.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = Palette.TextGhost.copy(alpha = 0.5f),
            modifier = Modifier.size(32.dp),
        )
        Spacer(Modifier.height(Space.md))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = Palette.TextSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Space.xs))
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = Palette.TextGhost,
            textAlign = TextAlign.Center,
        )
    }
}

/** Placeholder while the identity is still being generated/loaded. */
internal fun String.orPlaceholder(): String = ifEmpty { "Generating…" }

/** Short form for badges: first 12 hex characters, or a placeholder. */
internal fun String.shortDisplay(): String = ifEmpty { "Generating…" }.let {
    if (it.length <= 12) it else it.take(12) + "…"
}
