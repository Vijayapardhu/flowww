package com.flowww.android.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Flowww design tokens.
 *
 * The palette is intentionally monochrome with a single orange accent. Every
 * screen reads its colours from here (MaterialTheme for the standard roles,
 * [LocalFlowwwColors] for the semantic roles Material 3 does not model:
 * success, warning, muted text and borders).
 */

/** Dark palette — the default Flowww look. */
object FlowwwDarkPalette {
    val Background = Color(0xFF101010)
    val Surface = Color(0xFF191919)
    val SurfaceElevated = Color(0xFF222222)
    val TextPrimary = Color(0xFFF5F5F5)
    val TextSecondary = Color(0xFFA1A1A1)
    val TextMuted = Color(0xFF707070)
    val Border = Color(0xFF303030)
    val Accent = Color(0xFFFF7A1D)
    val AccentContainer = Color(0xFF2E2718)
    val Success = Color(0xFF4A9E4A)
    val Warning = Color(0xFFD9A52E)
    val Error = Color(0xFFD95A5A)
}

/** Light palette — same structure, tuned for light surfaces. */
object FlowwwLightPalette {
    val Background = Color(0xFFF7F7F7)
    val Surface = Color(0xFFFFFFFF)
    val SurfaceElevated = Color(0xFFF0F0F0)
    val TextPrimary = Color(0xFF151515)
    val TextSecondary = Color(0xFF5A5A5A)
    val TextMuted = Color(0xFF8A8A8A)
    val Border = Color(0xFFDEDEDE)
    val Accent = Color(0xFFD9630A)
    val AccentContainer = Color(0xFFFFE7D2)
    val Success = Color(0xFF3E8A3E)
    val Warning = Color(0xFFA87C1C)
    val Error = Color(0xFFC44545)
}

/**
 * Semantic colours that Material 3's [androidx.compose.material3.ColorScheme]
 * does not expose.
 */
@Immutable
data class FlowwwColors(
    val background: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val border: Color,
    val accent: Color,
    val accentContainer: Color,
    val success: Color,
    val warning: Color,
    val error: Color
) {
    val isDark: Boolean get() = background == FlowwwDarkPalette.Background
}

val DarkFlowwwColors = FlowwwColors(
    background = FlowwwDarkPalette.Background,
    surface = FlowwwDarkPalette.Surface,
    surfaceElevated = FlowwwDarkPalette.SurfaceElevated,
    textPrimary = FlowwwDarkPalette.TextPrimary,
    textSecondary = FlowwwDarkPalette.TextSecondary,
    textMuted = FlowwwDarkPalette.TextMuted,
    border = FlowwwDarkPalette.Border,
    accent = FlowwwDarkPalette.Accent,
    accentContainer = FlowwwDarkPalette.AccentContainer,
    success = FlowwwDarkPalette.Success,
    warning = FlowwwDarkPalette.Warning,
    error = FlowwwDarkPalette.Error
)

val LightFlowwwColors = FlowwwColors(
    background = FlowwwLightPalette.Background,
    surface = FlowwwLightPalette.Surface,
    surfaceElevated = FlowwwLightPalette.SurfaceElevated,
    textPrimary = FlowwwLightPalette.TextPrimary,
    textSecondary = FlowwwLightPalette.TextSecondary,
    textMuted = FlowwwLightPalette.TextMuted,
    border = FlowwwLightPalette.Border,
    accent = FlowwwLightPalette.Accent,
    accentContainer = FlowwwLightPalette.AccentContainer,
    success = FlowwwLightPalette.Success,
    warning = FlowwwLightPalette.Warning,
    error = FlowwwLightPalette.Error
)

val LocalFlowwwColors = staticCompositionLocalOf { DarkFlowwwColors }

val DarkColorScheme = darkColorScheme(
    background = FlowwwDarkPalette.Background,
    surface = FlowwwDarkPalette.Surface,
    surfaceVariant = FlowwwDarkPalette.SurfaceElevated,
    surfaceContainer = FlowwwDarkPalette.Surface,
    surfaceContainerHigh = FlowwwDarkPalette.SurfaceElevated,
    surfaceContainerHighest = FlowwwDarkPalette.SurfaceElevated,
    onBackground = FlowwwDarkPalette.TextPrimary,
    onSurface = FlowwwDarkPalette.TextPrimary,
    onSurfaceVariant = FlowwwDarkPalette.TextSecondary,
    primary = FlowwwDarkPalette.Accent,
    onPrimary = Color.White,
    primaryContainer = FlowwwDarkPalette.AccentContainer,
    onPrimaryContainer = FlowwwDarkPalette.Accent,
    secondary = FlowwwDarkPalette.TextSecondary,
    onSecondary = FlowwwDarkPalette.TextPrimary,
    secondaryContainer = FlowwwDarkPalette.SurfaceElevated,
    onSecondaryContainer = FlowwwDarkPalette.TextPrimary,
    tertiary = FlowwwDarkPalette.TextMuted,
    onTertiary = FlowwwDarkPalette.TextPrimary,
    error = FlowwwDarkPalette.Error,
    onError = Color.White,
    outline = FlowwwDarkPalette.Border,
    outlineVariant = FlowwwDarkPalette.Border,
    scrim = Color(0xCC000000)
)

val LightColorScheme = lightColorScheme(
    background = FlowwwLightPalette.Background,
    surface = FlowwwLightPalette.Surface,
    surfaceVariant = FlowwwLightPalette.SurfaceElevated,
    surfaceContainer = FlowwwLightPalette.Surface,
    surfaceContainerHigh = FlowwwLightPalette.SurfaceElevated,
    surfaceContainerHighest = FlowwwLightPalette.SurfaceElevated,
    onBackground = FlowwwLightPalette.TextPrimary,
    onSurface = FlowwwLightPalette.TextPrimary,
    onSurfaceVariant = FlowwwLightPalette.TextSecondary,
    primary = FlowwwLightPalette.Accent,
    onPrimary = Color.White,
    primaryContainer = FlowwwLightPalette.AccentContainer,
    onPrimaryContainer = FlowwwLightPalette.Accent,
    secondary = FlowwwLightPalette.TextSecondary,
    onSecondary = FlowwwLightPalette.TextPrimary,
    secondaryContainer = FlowwwLightPalette.SurfaceElevated,
    onSecondaryContainer = FlowwwLightPalette.TextPrimary,
    tertiary = FlowwwLightPalette.TextMuted,
    onTertiary = FlowwwLightPalette.TextPrimary,
    error = FlowwwLightPalette.Error,
    onError = Color.White,
    outline = FlowwwLightPalette.Border,
    outlineVariant = FlowwwLightPalette.Border,
    scrim = Color(0x99000000)
)

// Backwards-compatible aliases used by existing screens and previews.
val FlowwwDarkBackground = FlowwwDarkPalette.Background
val FlowwwSurface = FlowwwDarkPalette.Surface
val FlowwwSurfaceElevated = FlowwwDarkPalette.SurfaceElevated
val FlowwwOnBackground = FlowwwDarkPalette.TextPrimary
val FlowwwOnSurface = FlowwwDarkPalette.TextPrimary
val FlowwwOnSurfaceVariant = FlowwwDarkPalette.TextSecondary
val FlowwwOutline = FlowwwDarkPalette.Border
val FlowwwOrangeAccent = FlowwwDarkPalette.Accent
val FlowwwOrangeAccentContainer = FlowwwDarkPalette.AccentContainer
val FlowwwSuccess = FlowwwDarkPalette.Success
val FlowwwWarning = FlowwwDarkPalette.Warning
val FlowwwError = FlowwwDarkPalette.Error
