package com.flowww.android.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

val FlowwwDarkBackground = Color(0xFF101010)
val FlowwwSurface = Color(0xFF191919)
val FlowwwSurfaceElevated = Color(0xFF222222)
val FlowwwSurfaceVariant = Color(0xFF2A2A2A)
val FlowwwOnBackground = Color(0xFFF5F5F5)
val FlowwwOnSurface = Color(0xFFF5F5F5)
val FlowwwOnSurfaceVariant = Color(0xFFA1A1A1)
val FlowwwOutline = Color(0xFF303030)
val FlowwwOutlineVariant = Color(0xFF404040)

val FlowwwOrangeAccent = Color(0xFFFF7A1D)
val FlowwwOrangeAccentContainer = Color(0xFF2E2718)
val FlowwwOrangeAccentDim = Color(0xFFE05A00)

val FlowwwSuccess = Color(0xFF4A9E4A)
val FlowwwWarning = Color(0xFFD9A52E)
val FlowwwError = Color(0xFFD95A5A)

val DarkColorScheme = darkColorScheme(
    background = FlowwwDarkBackground,
    surface = FlowwwSurface,
    surfaceVariant = FlowwwSurfaceVariant,
    onBackground = FlowwwOnBackground,
    onSurface = FlowwwOnSurface,
    onSurfaceVariant = FlowwwOnSurfaceVariant,
    primary = FlowwwOrangeAccent,
    onPrimary = Color.White,
    primaryContainer = FlowwwOrangeAccentContainer,
    onPrimaryContainer = Color.White,
    secondary = FlowwwOutlineVariant,
    onSecondary = FlowwwOnSurface,
    secondaryContainer = FlowwwSurfaceVariant,
    onSecondaryContainer = FlowwwOnSurfaceVariant,
    tertiary = FlowwwOutlineVariant,
    onTertiary = FlowwwOnSurface,
    error = FlowwwError,
    onError = Color.White,
    outline = FlowwwOutline
)

val LightColorScheme = lightColorScheme(
    primary = FlowwwOrangeAccent,
    onPrimary = Color.White,
    primaryContainer = FlowwwOrangeAccentContainer,
    onPrimaryContainer = Color.White,
    secondary = FlowwwOutlineVariant,
    onSecondary = FlowwwOnSurface,
    background = FlowwwDarkBackground,
    onBackground = FlowwwOnBackground,
    surface = FlowwwSurface,
    onSurface = FlowwwOnSurface,
    surfaceVariant = FlowwwSurfaceVariant,
    onSurfaceVariant = FlowwwOnSurfaceVariant,
    error = FlowwwError,
    onError = Color.White,
    outline = FlowwwOutline
)
