package com.mirage.android.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Mirage design tokens ported from res/values/colors.xml and res/values-night/colors.xml.
 */
@Immutable
data class MirageColors(
    val blue: Color,
    val ginger: Color,
    val navy: Color,
    val ink: Color,
    val inkSecondary: Color,
    val canvas: Color,
    val card: Color,
    val connected: Color,
    val upload: Color,
    val download: Color,
    val outline: Color,
    val disconnected: Color,
    val error: Color,
    val accentTint: Color,
    val nodeRowSelected: Color,
    val nodeRowDefault: Color,
    val avatarBg: Color,
    val pillBg: Color,
    val pillStroke: Color,
)

val MirageLightColors = MirageColors(
    blue = Color(0xFF2481CC),
    ginger = Color(0xFFFF9500),
    navy = Color(0xFF1C6CA8),
    ink = Color(0xFF17212B),
    inkSecondary = Color(0xFF707579),
    canvas = Color(0xFFF0F2F5),
    card = Color(0xFFFFFFFF),
    connected = Color(0xFF34C759),
    upload = Color(0xFF2481CC),
    download = Color(0xFF2AA3EF),
    outline = Color(0xFFE3E7EB),
    disconnected = Color(0xFF8E959E),
    error = Color(0xFFFF3B30),
    accentTint = Color(0xFFE8F2FA),
    nodeRowSelected = Color(0xFFE8F1FC),
    nodeRowDefault = Color(0xFFFFFFFF),
    avatarBg = Color(0xFFE8F2FA),
    pillBg = Color(0xFFF0F4F8),
    pillStroke = Color(0xFFE2E8F0),
)

val MirageDarkColors = MirageColors(
    blue = Color(0xFF2CB5FF),
    ginger = Color(0xFFFFB340),
    navy = Color(0xFF9CC5FF),
    ink = Color(0xFFE8F2FA),
    inkSecondary = Color(0xFF707579), // Not covered in values-night, fallback to values/colors.xml
    canvas = Color(0xFF0B1720),
    card = Color(0xFF152430),
    connected = Color(0xFF33C377),
    upload = Color(0xFFFFB84D),
    download = Color(0xFF2CB5FF),
    outline = Color(0xFF2C4A60),
    disconnected = Color(0xFF5A7484),
    error = Color(0xFFFF3B30),        // Not covered in values-night, fallback to values/colors.xml
    accentTint = Color(0xFFE8F2FA),   // Not covered in values-night, fallback to values/colors.xml
    nodeRowSelected = Color(0xFF1B3348),
    nodeRowDefault = Color(0xFF152430),
    avatarBg = Color(0xFF1E3346),
    pillBg = Color(0xFF1B2B38),
    pillStroke = Color(0xFF2C4A60),
)

val LocalMirageColors = staticCompositionLocalOf<MirageColors> {
    error("LocalMirageColors not provided")
}
