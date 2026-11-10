package com.replayer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val RePlayerColorScheme = darkColorScheme(
    background = DarkBackground,
    surface = SurfaceDark,
    surfaceVariant = CardBackground,
    primary = AccentCyan,
    secondary = AccentOrange,
    error = RedAccent,
    onPrimary = DarkBackground,
    onSecondary = DarkBackground,
    onBackground = TextPrimary,
    onSurface = TextPrimary
)

@Composable
fun RePlayerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = RePlayerColorScheme,
        typography = TvTypography,
        content = content
    )
}
