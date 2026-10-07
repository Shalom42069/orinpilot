package de.marlon.orinpilot.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object OP {
    val Green = Color(0xFF76B900)
    val GreenDim = Color(0xFF4E7A00)
    val Bg = Color(0xFF0E1110)
    val Surface = Color(0xFF161A18)
    val SurfaceHi = Color(0xFF1E2421)
    val Outline = Color(0xFF2C3430)
    val Text = Color(0xFFE6ECE7)
    val TextDim = Color(0xFF94A097)
    val Amber = Color(0xFFF2B33D)
    val Red = Color(0xFFEF5B5B)
    val Cyan = Color(0xFF4CC9F0)
    val Violet = Color(0xFFB28DFF)
}

private val scheme = darkColorScheme(
    primary = OP.Green,
    onPrimary = Color(0xFF0B1400),
    primaryContainer = Color(0xFF243A00),
    onPrimaryContainer = Color(0xFFC8F27A),
    secondary = OP.Cyan,
    onSecondary = Color(0xFF00202B),
    secondaryContainer = Color(0xFF173642),
    onSecondaryContainer = Color(0xFFBFEAFF),
    tertiary = OP.Amber,
    background = OP.Bg,
    onBackground = OP.Text,
    surface = OP.Surface,
    onSurface = OP.Text,
    surfaceVariant = OP.SurfaceHi,
    onSurfaceVariant = OP.TextDim,
    surfaceContainer = OP.Surface,
    surfaceContainerHigh = OP.SurfaceHi,
    surfaceContainerHighest = Color(0xFF252C28),
    surfaceContainerLow = Color(0xFF131715),
    surfaceContainerLowest = OP.Bg,
    outline = OP.Outline,
    outlineVariant = Color(0xFF232A26),
    error = OP.Red,
    onError = Color(0xFF2B0000),
)

@Composable
fun OrinTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
