package com.mirage.android.data.repository

import com.mirage.android.data.model.AppFilterConfig
import com.mirage.android.data.model.AppFilterMode

object AppFilterManager {
    const val SELF_PACKAGE = "com.mirage.android"

    /**
     * 计算实际生效的允许代理包名列表 (白名单模式)
     */
    fun computeEffectiveAllowed(config: AppFilterConfig, installedPackages: Collection<String>): Set<String> {
        if (!config.enabled || config.mode != AppFilterMode.ALLOW) {
            return emptySet()
        }
        val installedSet = installedPackages.toSet()
        return config.selectedPackages
            .filter { it != SELF_PACKAGE && installedSet.contains(it) }
            .toSet()
    }

    /**
     * 计算实际生效的绕过代理包名列表 (黑名单模式)
     */
    fun computeEffectiveDisallowed(config: AppFilterConfig, installedPackages: Collection<String>): Set<String> {
        if (!config.enabled || config.mode != AppFilterMode.DISALLOW) {
            return emptySet()
        }
        val installedSet = installedPackages.toSet()
        return config.selectedPackages
            .filter { it != SELF_PACKAGE && installedSet.contains(it) }
            .toSet()
    }

    /**
     * 计算自适应连接池目标容量 (息屏低功耗 vs 活跃全速)
     */
    fun calculateAdaptivePoolSize(screenOn: Boolean, hasActiveHighTraffic: Boolean): Int {
        return if (screenOn || hasActiveHighTraffic) {
            16
        } else {
            4
        }
    }
}
