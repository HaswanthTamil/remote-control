package com.remotecontrol.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Design tokens. Every colour, radius and duration in the app comes from here -
 * components reference these names, never literal values.
 *
 * The palette descends from `remote_control_phone/css/app.css` so the native app
 * keeps the web client's identity, re-cut on a proper surface ramp.
 */
object Palette {
    /* ---- surface ramp: deepest well -> highest overlay ---- */
    val Background = Color(0xFF0A0C0F)
    val BackgroundDeep = Color(0xFF06080A) // mirrored stage, terminal well
    val Surface = Color(0xFF13171C)
    val SurfaceRaised = Color(0xFF1A1F26) // cards
    val SurfaceOverlay = Color(0xFF222933) // chips, pressed fills
    val Scrim = Color(0xCC05070A) // floating control backdrops

    val Border = Color(0xFF2C333D)
    val BorderSoft = Color(0xFF1F252D)
    val Divider = Color(0xFF1A1F26)

    /* ---- type ramp ---- */
    val TextPrimary = Color(0xFFF3F6F8)
    val TextSecondary = Color(0xFFC3CBD4)
    val TextMuted = Color(0xFF8B96A2)
    val TextFaint = Color(0xFF6B7580)
    val TextGhost = Color(0xFF5A636D)

    /* ---- semantic accents ---- */
    val Accent = Color(0xFF5FD79A) // connected / healthy
    val AccentBlue = Color(0xFF6EB6FF) // interactive / informational
    val Warning = Color(0xFFE9B44C) // connecting, degraded
    val Danger = Color(0xFFF8717A) // destructive, error
    val FocusRing = AccentBlue

    /* ---- component fills ---- */
    val TerminalText = Color(0xFFD7DDE4)
    val ChipBackground = SurfaceOverlay
    val PressedOverlay = Color(0x14FFFFFF)

    /** Tinted container behind an accent icon or dot, so accents never sit on a flat fill. */
    fun accentWash(accent: Color, alpha: Float = 0.14f): Color = accent.copy(alpha = alpha)
}

/** 4-pt spacing scale. Use these instead of ad-hoc dp values. */
object Space {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp

    /** Horizontal page gutter, used by every non-media screen. */
    val gutter = 20.dp
}

/** Corner radii, mirrored in [AppShapes] so Material components match our cards. */
object Radii {
    val chip = RoundedCornerShape(11.dp)
    val control = RoundedCornerShape(14.dp)
    val card = RoundedCornerShape(20.dp)
    val stage = RoundedCornerShape(18.dp)
    val sheet = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    val pill = RoundedCornerShape(percent = 50)
}

val AppShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Motion tokens. Short and functional - this is a remote control, not a showcase. */
object Motion {
    const val FAST = 120
    const val MEDIUM = 200
    const val SLOW = 320
}

private val RemoteColorScheme = darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Color(0xFF04140C),
    primaryContainer = Palette.accentWash(Palette.Accent),
    onPrimaryContainer = Palette.Accent,
    secondary = Palette.AccentBlue,
    onSecondary = Color(0xFF04121F),
    secondaryContainer = Palette.accentWash(Palette.AccentBlue),
    onSecondaryContainer = Palette.AccentBlue,
    background = Palette.Background,
    onBackground = Palette.TextPrimary,
    surface = Palette.Surface,
    onSurface = Palette.TextPrimary,
    surfaceVariant = Palette.SurfaceRaised,
    onSurfaceVariant = Palette.TextMuted,
    surfaceContainer = Palette.SurfaceRaised,
    surfaceContainerHigh = Palette.SurfaceOverlay,
    outline = Palette.Border,
    outlineVariant = Palette.BorderSoft,
    error = Palette.Danger,
    onError = Color(0xFF2B0B0E),
    scrim = Palette.Scrim,
)

private val SansFamily = FontFamily.SansSerif
private val MonoFamily = FontFamily.Monospace

/** Centre glyphs inside the line box so multi-line body copy sets evenly. */
private val TightLineHeight = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

val RemoteTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = SansFamily,
        fontSize = 30.sp,
        lineHeight = 34.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.8).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = SansFamily,
        fontSize = 21.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.4).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = SansFamily,
        fontSize = 18.sp,
        lineHeight = 23.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.2).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = SansFamily,
        fontSize = 16.sp,
        lineHeight = 21.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = (-0.1).sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = SansFamily,
        fontSize = 15.sp,
        lineHeight = 21.sp,
        lineHeightStyle = TightLineHeight,
    ),
    bodyMedium = TextStyle(
        fontFamily = SansFamily,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        lineHeightStyle = TightLineHeight,
    ),
    bodySmall = TextStyle(
        fontFamily = SansFamily,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        color = Palette.TextMuted,
    ),
    labelLarge = TextStyle(
        fontFamily = SansFamily,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = SansFamily,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.2.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = SansFamily,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.7.sp,
    ),
)

val TerminalTextStyle = TextStyle(
    fontFamily = MonoFamily,
    fontSize = 13.sp,
    lineHeight = 19.sp,
)

val MonoTextStyle = TextStyle(
    fontFamily = MonoFamily,
    fontSize = 14.sp,
    lineHeight = 20.sp,
    letterSpacing = (-0.1).sp,
)

val MonoSmallTextStyle = TextStyle(
    fontFamily = MonoFamily,
    fontSize = 11.sp,
    lineHeight = 15.sp,
    letterSpacing = 0.sp,
)

@Composable
fun RemoteControlTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // The web app is dark-only; keeping a single scheme avoids half-themed screens.
    MaterialTheme(
        colorScheme = RemoteColorScheme,
        typography = RemoteTypography,
        shapes = AppShapes,
        content = content,
    )
}
