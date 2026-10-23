package com.replayer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val RePlayerColorScheme = darkColorScheme(
    background = DarkBackground,
    surface = SurfaceDark,
    primary = AccentCyan,
    onPrimary = TextPrimary,
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
