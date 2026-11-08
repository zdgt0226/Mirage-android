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
    // ---- surfaceContainer 系列：不定义就会漏出 M3 基线紫 ----
    //
    // Material3 1.3 起，菜单 (DropdownMenu)、弹层、Card 等容器取的是
    // surfaceContainer* 而**不是** surface。这几个角色没覆盖时，M3 回落到它自己
    // 那套基线紫调色板 —— 真机实测（2026-11-09，SO-02K）：首页节点下拉的底色是
    // 淡紫，与全应用的 Telegram 蓝完全脱节。这与 C 阶段记过的「管理内核按钮
    // 显示 M3 默认紫」是同一个根因，当时只修了按钮没修容器。
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = c.card,
    surfaceContainer = c.card,
    surfaceContainerHigh = c.pillBg,
    surfaceContainerHighest = c.pillBg,
    surfaceBright = Color.White,
    surfaceDim = c.canvas,
    inverseSurface = c.ink,
    inverseOnSurface = c.canvas,
    scrim = Color(0x99000000),
    // 高度染色关掉：M3 会按 elevation 把 primary 叠到容器上，叠出来的仍然偏紫。
    // 层级由 surfaceContainer 系列自身的明度差表达，不靠染色。
    surfaceTint = Color.Transparent,
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
    // 同上。暗色侧不覆盖的后果更重：浅紫底配浅色字，菜单基本读不了。
    surfaceContainerLowest = c.canvas,
    surfaceContainerLow = c.canvas,
    surfaceContainer = c.card,
    surfaceContainerHigh = c.avatarBg,
    surfaceContainerHighest = c.nodeRowSelected,
    surfaceBright = c.card,
    surfaceDim = c.canvas,
    inverseSurface = c.ink,
    inverseOnSurface = c.canvas,
    scrim = Color(0xB3000000),
    surfaceTint = Color.Transparent,
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
