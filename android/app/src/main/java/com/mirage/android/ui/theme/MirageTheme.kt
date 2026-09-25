package com.mirage.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val MirageShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

private fun lightScheme(c: MirageColors): ColorScheme = lightColorScheme(
    primary = c.blue,
    onPrimary = Color.White,
    primaryContainer = c.accentTint,
    onPrimaryContainer = c.ink,
    secondary = c.download,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8F8FF),
    onSecondaryContainer = c.ink,
    tertiary = c.navy,
    onTertiary = Color.White,
    background = c.canvas,
    onBackground = c.ink,
    surface = c.card,
    onSurface = c.ink,
    surfaceVariant = Color(0xFFF8FAFC),
    onSurfaceVariant = c.inkSecondary,
    outline = c.outline,
    outlineVariant = c.outline,
    error = c.error,
    onError = Color.White,
)

private fun darkScheme(c: MirageColors): ColorScheme = darkColorScheme(
    primary = c.blue,
    onPrimary = Color.White,
    primaryContainer = c.nodeRowSelected,
    onPrimaryContainer = c.ink,
    secondary = c.download,
    onSecondary = Color.White,
    secondaryContainer = c.avatarBg,
    onSecondaryContainer = c.ink,
    tertiary = c.navy,
    onTertiary = Color.White,
    background = c.canvas,
    onBackground = c.ink,
    surface = c.card,
    onSurface = c.ink,
    surfaceVariant = c.card,
    onSurfaceVariant = c.inkSecondary,
    outline = c.outline,
    outlineVariant = c.outline,
    error = c.error,
    onError = Color.White,
)

@Composable
fun MirageTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) MirageDarkColors else MirageLightColors
    val colorScheme = if (darkTheme) darkScheme(colors) else lightScheme(colors)

    CompositionLocalProvider(LocalMirageColors provides colors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = MirageTypography,
            shapes = MirageShapes,
            content = content,
        )
    }
}

val MaterialTheme.mirage: MirageColors
    @Composable
    @ReadOnlyComposable
    get() = LocalMirageColors.current
