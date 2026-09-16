package com.mirage.android

import com.mirage.android.data.model.AppFilterConfig
import com.mirage.android.data.model.AppFilterMode
import org.junit.Assert.*
import org.junit.Test

class CoreServiceStateTest {

    @Test
    fun testServiceConfigDefaults() {
        val cfg = CoreService.ServiceConfig()
        assertEquals("", cfg.uri)
        assertEquals(-1, cfg.poolSize)
        assertFalse(cfg.bypassLan)
        assertFalse(cfg.ipv6Enabled)
        assertEquals(1500, cfg.mtu)
        assertNull(cfg.appFilterConfig)
        assertEquals("223.5.5.5", cfg.directDns)
        assertEquals("1.1.1.1", cfg.remoteDns)
        assertTrue(cfg.blockQuic)
        assertTrue(cfg.udpMux)
        assertTrue(cfg.autoReconnect)
        assertEquals(15, cfg.checkIntervalSec)
        assertEquals("best", cfg.failoverMode)
        assertTrue(cfg.nodes.isEmpty())
        assertEquals(0, cfg.outboundMode)
    }

    @Test
    fun testServiceConfigCustomization() {
        val appConfig = AppFilterConfig(
            enabled = true,
            mode = AppFilterMode.DISALLOW,
            selectedPackages = setOf("com.example.app")
        )
        val cfg = CoreService.ServiceConfig(
            uri = "mirage://pass@1.1.1.1:443?sni=sni.test",
            poolSize = 8,
            bypassLan = true,
            ipv6Enabled = true,
            mtu = 1400,
            appFilterConfig = appConfig,
            directDns = "119.29.29.29",
            remoteDns = "8.8.8.8",
            blockQuic = false,
            udpMux = false,
            autoReconnect = false,
            checkIntervalSec = 30,
            failoverMode = "next",
            outboundMode = 1
        )

        assertEquals("mirage://pass@1.1.1.1:443?sni=sni.test", cfg.uri)
        assertEquals(8, cfg.poolSize)
        assertTrue(cfg.bypassLan)
        assertTrue(cfg.ipv6Enabled)
        assertEquals(1400, cfg.mtu)
        assertNotNull(cfg.appFilterConfig)
        assertTrue(cfg.appFilterConfig!!.enabled)
        assertEquals("119.29.29.29", cfg.directDns)
        assertEquals("8.8.8.8", cfg.remoteDns)
        assertFalse(cfg.blockQuic)
        assertFalse(cfg.udpMux)
        assertFalse(cfg.autoReconnect)
        assertEquals(30, cfg.checkIntervalSec)
        assertEquals("next", cfg.failoverMode)
        assertEquals(1, cfg.outboundMode)
    }

    @Test
    fun testHttpsUrlValidationForGeo() {
        val validHttps = "https://raw.githubusercontent.com/Loyalsoldier/v2ray-rules-dat/release/geosite.dat"
        val invalidHttp = "http://raw.githubusercontent.com/Loyalsoldier/v2ray-rules-dat/release/geosite.dat"
        val fileScheme = "file:///sdcard/geosite.dat"

        assertTrue(validHttps.startsWith("https://", ignoreCase = true))
        assertFalse(invalidHttp.startsWith("https://", ignoreCase = true))
        assertFalse(fileScheme.startsWith("https://", ignoreCase = true))
    }
}
