package com.replayer.ui.theme

import androidx.compose.runtime.Composable
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

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
