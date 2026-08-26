package com.mirage.android

import com.mirage.android.data.model.AppFilterConfig
import com.mirage.android.data.model.AppFilterMode
import com.mirage.android.data.model.AppInfo
import com.mirage.android.data.repository.AppFilterManager
import org.junit.Assert.*
import org.junit.Test

class PerAppFilterTest {

    @Test
    fun testScenario1_DisallowModeFiltering() {
        val config = AppFilterConfig(
            enabled = true,
            mode = AppFilterMode.DISALLOW,
            selectedPackages = setOf("com.tencent.mm", "com.eg.android.AlipayGphone", "com.mirage.android")
        )
        val installed = listOf("com.tencent.mm", "com.eg.android.AlipayGphone", "com.android.chrome")
        
        val effectiveDisallowed = AppFilterManager.computeEffectiveDisallowed(config, installed)
        
        assertTrue("微信必须在黑名单列表中", effectiveDisallowed.contains("com.tencent.mm"))
        assertTrue("支付宝必须在黑名单列表中", effectiveDisallowed.contains("com.eg.android.AlipayGphone"))
        assertFalse("Chrome 绝不能在黑名单列表中", effectiveDisallowed.contains("com.android.chrome"))
    }

    @Test
    fun testScenario2_AllowModeFiltering_SelfExcluded() {
        val config = AppFilterConfig(
            enabled = true,
            mode = AppFilterMode.ALLOW,
            selectedPackages = setOf("com.android.chrome", "org.telegram.messenger", "com.mirage.android")
        )
        val installed = listOf("com.android.chrome", "org.telegram.messenger", "com.tencent.mm", "com.mirage.android")
        
        val effectiveAllowed = AppFilterManager.computeEffectiveAllowed(config, installed)
        
        assertTrue("Chrome 必须在白名单列表中", effectiveAllowed.contains("com.android.chrome"))
        assertTrue("Telegram 必须在白名单列表中", effectiveAllowed.contains("org.telegram.messenger"))
        assertFalse("自身包名必须被剔除出白名单", effectiveAllowed.contains("com.mirage.android"))
        assertFalse("未勾选的微信绝不能在白名单中", effectiveAllowed.contains("com.tencent.mm"))
    }

    @Test
    fun testScenario3_DisabledGlobalProxy() {
        val config = AppFilterConfig(enabled = false, mode = AppFilterMode.DISALLOW, selectedPackages = setOf("com.tencent.mm"))
        val installed = listOf("com.tencent.mm", "com.android.chrome")
        
        val effectiveAllowed = AppFilterManager.computeEffectiveAllowed(config, installed)
        val effectiveDisallowed = AppFilterManager.computeEffectiveDisallowed(config, installed)
        
        assertTrue("未启用分应用代理时允许列表为空", effectiveAllowed.isEmpty())
        assertTrue("未启用分应用代理时绕过列表为空", effectiveDisallowed.isEmpty())
    }

    @Test
    fun testScenario4_FilterOutUninstalledPackages() {
        val config = AppFilterConfig(
            enabled = true,
            mode = AppFilterMode.ALLOW,
            selectedPackages = setOf("com.android.chrome", "com.uninstalled.ghost.app")
        )
        val installed = listOf("com.android.chrome") // 无 ghost app
        
        val effectiveAllowed = AppFilterManager.computeEffectiveAllowed(config, installed)
        
        assertEquals(1, effectiveAllowed.size)
        assertTrue(effectiveAllowed.contains("com.android.chrome"))
        assertFalse(effectiveAllowed.contains("com.uninstalled.ghost.app"))
    }

    @Test
    fun testScenario5_AdaptiveWarmPoolSizeCalculation() {
        // 前台亮屏状态 -> 16
        assertEquals(16, AppFilterManager.calculateAdaptivePoolSize(screenOn = true, hasActiveHighTraffic = false))
        assertEquals(16, AppFilterManager.calculateAdaptivePoolSize(screenOn = true, hasActiveHighTraffic = true))
        
        // 息屏且无活跃高吞吐流量 -> 缩容至 4
        assertEquals(4, AppFilterManager.calculateAdaptivePoolSize(screenOn = false, hasActiveHighTraffic = false))
        
        // 息屏但有后台大流量下载 (如正在下载文件) -> 维持 16
        assertEquals(16, AppFilterManager.calculateAdaptivePoolSize(screenOn = false, hasActiveHighTraffic = true))
    }
}
