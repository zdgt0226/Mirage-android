package com.mirage.android.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Fluid typography scale bridged from res/values/themes.xml (TextAppearance.Mirage.*).
 *
 * Mapping:
 * - Display (20sp, bold, -0.015 letterSpacing, 1.1x lineSpacing): headlineMedium / headlineSmall
 * - Title (16sp, medium, -0.01 letterSpacing, 1.15x lineSpacing): titleMedium
 * - Body (14sp, normal, 0.0 letterSpacing, 1.25x lineSpacing): bodyMedium
 * - Label (12sp-14sp, medium, 0.02 letterSpacing, 1.15x lineSpacing): labelLarge, labelMedium
 * - Caption (11sp, normal, 0.03 letterSpacing, 1.2x lineSpacing): bodySmall, labelSmall
 */
val MirageTypography = Typography().run {
    copy(
        headlineMedium = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            lineHeight = 22.sp,
            letterSpacing = (-0.015).em,
        ),
        headlineSmall = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Medium,
            fontSize = 18.sp,
            lineHeight = 21.sp,
            letterSpacing = (-0.015).em,
        ),
        titleMedium = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Medium,
            fontSize = 16.sp,
            lineHeight = 20.sp,
            letterSpacing = (-0.01).em,
        ),
        bodyMedium = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.0.em,
        ),
        labelLarge = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            lineHeight = 18.sp,
            letterSpacing = 0.01.em,
        ),
        labelMedium = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            lineHeight = 14.sp,
            letterSpacing = 0.02.em,
        ),
        bodySmall = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Normal,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            letterSpacing = 0.03.em,
        ),
        labelSmall = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            letterSpacing = 0.03.em,
        ),
    )
}
