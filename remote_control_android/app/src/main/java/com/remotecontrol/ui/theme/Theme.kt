package com.remotecontrol.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Palette lifted straight from `remote_control_phone/css/app.css` so the native
 * app feels like the web one.
 */
object Palette {
    val Background = Color(0xFF0B0D10)
    val BackgroundDeep = Color(0xFF080A0C)
    val Surface = Color(0xFF15191F)
    val SurfaceRaised = Color(0xFF101419)
    val Border = Color(0xFF2B323B)
    val BorderSoft = Color(0xFF252B33)
    val Divider = Color(0xFF222830)

    val TextPrimary = Color(0xFFF3F5F7)
    val TextSecondary = Color(0xFFC9CFD6)
    val TextMuted = Color(0xFF8B95A1)
    val TextFaint = Color(0xFF646D78)
    val TextGhost = Color(0xFF59616B)

    val Accent = Color(0xFF61D095)
    val AccentBlue = Color(0xFF6EC6FF)
    val Warning = Color(0xFFE8B34B)
    val Danger = Color(0xFFF87171)

    val TerminalText = Color(0xFFD7DDE4)
    val ChipBackground = Color(0xFF1C222A)
    val PressedOverlay = Color(0x1FFFFFFF)
}

private val RemoteColorScheme = darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Palette.Background,
    secondary = Palette.AccentBlue,
    onSecondary = Palette.Background,
    background = Palette.Background,
    onBackground = Palette.TextPrimary,
    surface = Palette.Surface,
    onSurface = Palette.TextPrimary,
    surfaceVariant = Palette.SurfaceRaised,
    onSurfaceVariant = Palette.TextMuted,
    error = Palette.Danger,
    onError = Palette.Background,
    outline = Palette.Border,
    outlineVariant = Palette.BorderSoft,
)

private val MonoFamily = FontFamily.Monospace

val RemoteTypography = Typography(
    displaySmall = TextStyle(fontSize = 30.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.8).sp),
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp),
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
        content = content,
    )
}