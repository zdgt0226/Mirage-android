package com.mirage.android.data.model

import android.graphics.drawable.Drawable

enum class AppFilterMode {
    ALLOW,     // 仅代理选中的应用 (白名单)
    DISALLOW   // 绕过选中的应用 (黑名单)
}

data class AppFilterConfig(
    val enabled: Boolean = false,
    val mode: AppFilterMode = AppFilterMode.DISALLOW,
    val selectedPackages: Set<String> = emptySet()
)

data class AppInfo(
    val name: String,
    val packageName: String,
    val isSystemApp: Boolean = false,
    val isSelected: Boolean = false
)
