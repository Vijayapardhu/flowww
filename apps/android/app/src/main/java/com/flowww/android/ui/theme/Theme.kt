package com.flowww.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import com.flowww.android.domain.models.ThemeMode

/**
 * Applies the Flowww design system.
 *
 * @param darkTheme when false the light palette is used.
 */
@Composable
fun FlowwwTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val flowwwColors = if (darkTheme) DarkFlowwwColors else LightFlowwwColors
    CompositionLocalProvider(LocalFlowwwColors provides flowwwColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = FlowwwTypography,
            shapes = FlowwwShapes,
            content = content
        )
    }
}

/** Resolves the effective dark/light flag for a [ThemeMode]. */
fun ThemeMode.resolveDarkTheme(systemInDarkTheme: Boolean): Boolean = when (this) {
    ThemeMode.SYSTEM -> systemInDarkTheme
    ThemeMode.DARK -> true
    ThemeMode.LIGHT -> false
}

/** The semantic Flowww colours for the current composition. */
val flowwwColors: FlowwwColors
    @Composable @ReadOnlyComposable
    get() = LocalFlowwwColors.current
