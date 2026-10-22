package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val NothingGlyphColorScheme = darkColorScheme(
    primary = NothingLedBright,
    secondary = NothingLedMid,
    tertiary = NothingAccentRed,
    background = NothingPitchBlack,
    surface = NothingChassisDark,
    onPrimary = NothingPitchBlack,
    onSecondary = NothingPitchBlack,
    onBackground = NothingLedBright,
    onSurface = NothingLedBright
)

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = NothingGlyphColorScheme,
        typography = Typography,
        content = content
    )
}
